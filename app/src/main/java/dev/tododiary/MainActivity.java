package dev.tododiary;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 10;
    private static final int REQUEST_EXPORT_STORAGE = 11;
    private static final int BG = 0xff101418;
    private static final int PANEL = 0xff1b2229;
    private static final int TEXT = 0xfff4f7f8;
    private static final int MUTED = 0xffb6c2c7;
    private static final int ACCENT = 0xff6ee7b7;

    private static final int[] WEEKDAY_VALUES = {
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY
    };
    private static final String[] WEEKDAY_LABELS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] BLOCK_LABELS = {
            "00-02", "02-04", "04-06", "06-08", "08-10", "10-12",
            "12-14", "14-16", "16-18", "18-20", "20-22", "22-24"
    };

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat exportFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private AppDatabase database;
    private LinearLayout content;
    private boolean pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new AppDatabase(this);
        TodoScheduler.createChannel(this);
        buildShell();
        requestNotificationPermissionIfNeeded();
        showTodos();
    }

    @Override
    protected void onDestroy() {
        if (database != null) {
            database.close();
        }
        super.onDestroy();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));
        root.setBackgroundColor(BG);

        TextView title = text("TodoDiary", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, fullWidth());

        LinearLayout tabsTop = new LinearLayout(this);
        tabsTop.setOrientation(LinearLayout.HORIZONTAL);
        tabsTop.setGravity(Gravity.CENTER);
        tabsTop.addView(tab("To-do", view -> showTodos()), tabParams());
        tabsTop.addView(tab("Diary", view -> showDiary(currentDate())), tabParams());
        tabsTop.addView(tab("Sleep", view -> showSleep(currentDate())), tabParams());
        tabsTop.addView(tab("Water", view -> showWater(currentDate())), tabParams());
        root.addView(tabsTop, fullWidth());

        LinearLayout tabsBottom = new LinearLayout(this);
        tabsBottom.setOrientation(LinearLayout.HORIZONTAL);
        tabsBottom.setGravity(Gravity.CENTER);
        tabsBottom.addView(tab("Events", view -> showEvents(currentDate())), tabParams());
        tabsBottom.addView(tab("Analysis", view -> showAnalysis(currentDate())), tabParams());
        tabsBottom.addView(tab("Export", view -> showExport()), tabParams());
        tabsBottom.addView(tab("Guide", view -> showGuide()), tabParams());
        root.addView(tabsBottom, fullWidth());

        ScrollView scrollView = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(20));
        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);
    }

    private void showTodos() {
        content.removeAllViews();
        content.addView(sectionTitle("New To-do"));

        EditText titleInput = input("Title");
        EditText notesInput = input("Notes");
        EditText timeInput = input("Reminder time HH:mm");
        timeInput.setText("09:00");
        content.addView(titleInput, fullWidth());
        content.addView(notesInput, fullWidth());
        content.addView(timeInput, fullWidth());
        content.addView(timeControls(timeInput, true), fullWidth());

        TextView weekdaysLabel = text("Reminder weekdays", 14, MUTED);
        content.addView(weekdaysLabel, fullWidth());
        CheckBox[] boxes = weekdayBoxes(true);
        content.addView(weekdayRow(boxes, 0, 4), fullWidth());
        content.addView(weekdayRow(boxes, 4, 7), fullWidth());

        Button add = button("Add to-do");
        add.setOnClickListener(view -> {
            String title = titleInput.getText().toString().trim();
            String time = normalizeTimeInput(timeInput.getText().toString().trim());
            if (title.isEmpty()) {
                toast("Title required");
                return;
            }
            if (time == null) {
                toast("Use time as HH:mm or TV time like 28");
                return;
            }
            String weekdays = selectedWeekdays(boxes);
            if (weekdays.isEmpty()) {
                toast("Select at least one weekday");
                return;
            }
            long id = database.addTodo(title, notesInput.getText().toString().trim(), time, weekdays);
            TodoScheduler.scheduleAll(this);
            toast("Added to-do #" + id);
            showTodos();
        });
        content.addView(add, fullWidth());

        content.addView(sectionTitle("To-do List"));
        Cursor cursor = database.getTodos();
        try {
            if (!cursor.moveToFirst()) {
                content.addView(text("No to-dos yet.", 15, MUTED), fullWidth());
                return;
            }
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                String title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
                String notes = cursor.getString(cursor.getColumnIndexOrThrow("notes"));
                String time = cursor.getString(cursor.getColumnIndexOrThrow("time"));
                String weekdays = cursor.getString(cursor.getColumnIndexOrThrow("weekdays"));
                LinearLayout item = panel();
                item.addView(text(title, 18, TEXT), fullWidth());
                item.addView(text(time + "  " + formatWeekdays(weekdays), 14, ACCENT), fullWidth());
                if (notes != null && !notes.isEmpty()) {
                    item.addView(text(notes, 14, MUTED), fullWidth());
                }
                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                boolean doneToday = database.hasTodoCompletion(id, currentDate());
                Button done = button(doneToday ? "Done today" : "Success today");
                done.setEnabled(!doneToday);
                done.setOnClickListener(view -> {
                    boolean saved = database.markTodoDone(id, System.currentTimeMillis(), currentDate());
                    toast(saved ? "Marked success" : "Already done today");
                    showTodos();
                });
                Button delete = button("Delete");
                delete.setOnClickListener(view -> {
                    TodoScheduler.cancelTodo(this, id);
                    database.deleteTodo(id);
                    TodoScheduler.scheduleAll(this);
                    toast("Deleted");
                    showTodos();
                });
                actions.addView(done, tabParams());
                actions.addView(delete, tabParams());
                item.addView(actions, fullWidth());
                content.addView(item, fullWidth());
            } while (cursor.moveToNext());
        } finally {
            cursor.close();
        }
    }

    private void showDiary(String date) {
        content.removeAllViews();
        content.addView(sectionTitle("Diary"));
        content.addView(text("Daily notes, lunch, nap, and workload. Wake and sleep fields are optional here; use Sleep for sleep-duration analysis.", 15, MUTED), fullWidth());

        EditText dateInput = input("Date yyyy-MM-dd");
        dateInput.setText(date);
        EditText wakeInput = input("Wake up time optional HH:mm");
        EditText sleepInput = input("Sleep time optional HH:mm");
        EditText napInput = input("Nap time optional HH:mm or text");
        EditText lunchInput = input("Lunch");
        EditText notesInput = input("Diary notes");
        EditText[] blockInputs = new EditText[BLOCK_LABELS.length];

        content.addView(dateInput, fullWidth());
        content.addView(dateControls(dateInput), fullWidth());
        content.addView(wakeInput, fullWidth());
        content.addView(timeControls(wakeInput, true), fullWidth());
        content.addView(sleepInput, fullWidth());
        content.addView(timeControls(sleepInput, true), fullWidth());
        content.addView(napInput, fullWidth());
        content.addView(timeControls(napInput, true), fullWidth());
        content.addView(lunchInput, fullWidth());
        content.addView(notesInput, fullWidth());

        content.addView(text("Workload by 2-hour block", 15, MUTED), fullWidth());
        for (int i = 0; i < BLOCK_LABELS.length; i++) {
            EditText block = input(BLOCK_LABELS[i] + " workload");
            blockInputs[i] = block;
            content.addView(block, fullWidth());
        }

        loadDiaryInto(date, wakeInput, sleepInput, napInput, lunchInput, notesInput, blockInputs);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button load = button("Load");
        load.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            showDiary(chosen);
        });
        Button autoFill = button("Auto fill");
        autoFill.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            autoFillWorkload(chosen, blockInputs);
            toast("Filled from successful to-dos");
        });
        actions.addView(load, tabParams());
        actions.addView(autoFill, tabParams());
        content.addView(actions, fullWidth());

        Button save = button("Save diary");
        save.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            String wakeRaw = wakeInput.getText().toString().trim();
            String sleepRaw = sleepInput.getText().toString().trim();
            String wake = wakeRaw.isEmpty() ? "" : normalizeTimeInput(wakeRaw);
            String sleep = sleepRaw.isEmpty() ? "" : normalizeTimeInput(sleepRaw);
            String nap = normalizeOptionalTimeOrText(napInput.getText().toString().trim());
            String lunch = lunchInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            if (wake == null || sleep == null) {
                toast("Optional wake/sleep must be HH:mm or TV time like 28");
                return;
            }
            if (lunch.isEmpty()) {
                toast("Lunch required");
                return;
            }
            JSONArray workload = new JSONArray();
            for (EditText blockInput : blockInputs) {
                workload.put(blockInput.getText().toString().trim());
            }
            database.saveDiary(
                    chosen,
                    wake,
                    sleep,
                    nap,
                    lunch,
                    workload.toString(),
                    notesInput.getText().toString().trim()
            );
            toast("Diary saved");
        });
        content.addView(save, fullWidth());
    }

    private void showSleep(String date) {
        content.removeAllViews();
        content.addView(sectionTitle("Sleep"));
        content.addView(text("Record the date you woke up, your wake time, and when last night's sleep started. This page calculates sleep duration for Analysis.", 15, MUTED), fullWidth());

        EditText dateInput = input("Wake date yyyy-MM-dd");
        dateInput.setText(date);
        EditText wakeInput = input("Wake time HH:mm or TV time");
        EditText sleepInput = input("Last night sleep time HH:mm or TV time");
        EditText noteInput = input("Sleep note optional");
        TextView durationView = panelText("No sleep record saved for " + date);

        content.addView(dateInput, fullWidth());
        content.addView(dateControls(dateInput), fullWidth());
        content.addView(wakeInput, fullWidth());
        content.addView(timeControls(wakeInput, true), fullWidth());
        content.addView(sleepInput, fullWidth());
        content.addView(timeControls(sleepInput, true), fullWidth());
        content.addView(noteInput, fullWidth());

        Cursor sleep = database.getSleepRecord(date);
        try {
            if (sleep.moveToFirst()) {
                wakeInput.setText(sleep.getString(sleep.getColumnIndexOrThrow("wake_time")));
                sleepInput.setText(sleep.getString(sleep.getColumnIndexOrThrow("last_sleep_time")));
                String note = sleep.getString(sleep.getColumnIndexOrThrow("note"));
                noteInput.setText(note == null ? "" : note);
                int minutes = sleep.getInt(sleep.getColumnIndexOrThrow("sleep_minutes"));
                durationView.setText("Saved duration: " + formatDuration(minutes));
            }
        } finally {
            sleep.close();
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button load = button("Load day");
        load.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            showSleep(chosen);
        });
        Button save = button("Save sleep");
        save.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            String wake = normalizeTimeInput(wakeInput.getText().toString().trim());
            String lastSleep = normalizeTimeInput(sleepInput.getText().toString().trim());
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            if (wake == null || lastSleep == null) {
                toast("Wake and last sleep must be HH:mm or TV time like 28");
                return;
            }
            int minutes = calculateSleepMinutes(chosen, wake, lastSleep);
            if (minutes <= 0 || minutes > 24 * 60) {
                toast("Sleep duration must be between 1 minute and 24 hours");
                return;
            }
            database.saveSleepRecord(
                    chosen,
                    wake,
                    lastSleep,
                    minutes,
                    noteInput.getText().toString().trim()
            );
            toast("Sleep saved: " + formatDuration(minutes));
            showSleep(chosen);
        });
        actions.addView(load, tabParams());
        actions.addView(save, tabParams());
        content.addView(actions, fullWidth());
        content.addView(durationView, fullWidth());
    }

    private void showWater(String date) {
        content.removeAllViews();
        content.addView(sectionTitle("Water Check"));

        EditText dateInput = input("Date yyyy-MM-dd");
        dateInput.setText(date);
        EditText timeInput = input("Time HH:mm");
        timeInput.setText(currentTime());
        content.addView(dateInput, fullWidth());
        content.addView(dateControls(dateInput), fullWidth());
        content.addView(timeInput, fullWidth());
        content.addView(timeControls(timeInput, true), fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = button("+250 ml");
        add.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            String time = normalizeTimeInput(timeInput.getText().toString().trim());
            if (!isDate(chosen) || time == null) {
                toast("Use yyyy-MM-dd and HH:mm, or TV time like 28");
                return;
            }
            database.addWater(chosen, parseDateTime(chosen, time));
            toast("Water saved");
            showWater(chosen);
        });
        Button load = button("Load day");
        load.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            showWater(chosen);
        });
        actions.addView(add, tabParams());
        actions.addView(load, tabParams());
        content.addView(actions, fullWidth());

        Cursor cursor = database.getWaterForDate(date);
        int total = 0;
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                int amount = cursor.getInt(cursor.getColumnIndexOrThrow("amount_ml"));
                long timeMs = cursor.getLong(cursor.getColumnIndexOrThrow("time_ms"));
                total += amount;
                LinearLayout row = panel();
                row.addView(text(formatTimeForDate(timeMs, date) + "  " + amount + " ml", 16, TEXT), fullWidth());
                Button delete = button("Delete wrong entry");
                delete.setOnClickListener(view -> {
                    database.deleteWater(id);
                    toast("Water entry deleted");
                    showWater(date);
                });
                row.addView(delete, fullWidth());
                content.addView(row, fullWidth());
            }
        } finally {
            cursor.close();
        }
        content.addView(text("Total: " + total + " ml", 18, ACCENT), fullWidth());
    }

    private void showEvents(String date) {
        content.removeAllViews();
        content.addView(sectionTitle("Special Events"));

        content.addView(text("Register event names first, then log each occurrence as many times per day as needed.", 15, MUTED), fullWidth());
        EditText nameInput = input("Registered event name");
        EditText typeNoteInput = input("Event type note optional");
        content.addView(nameInput, fullWidth());
        content.addView(typeNoteInput, fullWidth());
        Button register = button("Register event name");
        register.setOnClickListener(view -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                toast("Event name required");
                return;
            }
            long id = database.addEventType(name, typeNoteInput.getText().toString().trim());
            toast(id < 0L ? "Name already registered" : "Registered");
            showEvents(date);
        });
        content.addView(register, fullWidth());

        List<EventType> eventTypes = loadEventTypes();
        if (eventTypes.isEmpty()) {
            content.addView(panelText("No registered event names yet."), fullWidth());
            return;
        }

        content.addView(sectionTitle("Log Occurrence"));
        EditText dateInput = input("Date yyyy-MM-dd");
        dateInput.setText(date);
        EditText timeInput = input("Time HH:mm");
        timeInput.setText(currentTime());
        EditText eventNoteInput = input("Occurrence note optional");
        Spinner eventSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                eventTypeNames(eventTypes)
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        eventSpinner.setAdapter(adapter);

        content.addView(dateInput, fullWidth());
        content.addView(dateControls(dateInput), fullWidth());
        content.addView(timeInput, fullWidth());
        content.addView(timeControls(timeInput, true), fullWidth());
        content.addView(eventSpinner, fullWidth());
        content.addView(eventNoteInput, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button log = button("Log event");
        log.setOnClickListener(view -> {
            String chosenDate = dateInput.getText().toString().trim();
            String chosenTime = normalizeTimeInput(timeInput.getText().toString().trim());
            if (!isDate(chosenDate) || chosenTime == null) {
                toast("Use yyyy-MM-dd and HH:mm, or TV time like 28");
                return;
            }
            EventType selected = eventTypes.get(Math.max(0, eventSpinner.getSelectedItemPosition()));
            database.addSpecialEvent(
                    selected.id,
                    selected.name,
                    eventNoteInput.getText().toString().trim(),
                    parseDateTime(chosenDate, chosenTime),
                    chosenDate
            );
            toast("Event logged");
            showEvents(chosenDate);
        });
        Button load = button("Load day");
        load.setOnClickListener(view -> {
            String chosenDate = dateInput.getText().toString().trim();
            if (!isDate(chosenDate)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            showEvents(chosenDate);
        });
        actions.addView(log, tabParams());
        actions.addView(load, tabParams());
        content.addView(actions, fullWidth());

        content.addView(sectionTitle("Registered Names"));
        for (EventType eventType : eventTypes) {
            LinearLayout row = panel();
            row.addView(text(eventType.name, 17, TEXT), fullWidth());
            if (!eventType.note.isEmpty()) {
                row.addView(text(eventType.note, 14, MUTED), fullWidth());
            }
            Button delete = button("Remove name");
            delete.setOnClickListener(view -> {
                database.deleteEventType(eventType.id);
                toast("Name removed; old logged events keep their copied name");
                showEvents(date);
            });
            row.addView(delete, fullWidth());
            content.addView(row, fullWidth());
        }

        content.addView(sectionTitle("Events On " + date));
        Cursor cursor = database.getSpecialEventsForDate(date);
        try {
            if (!cursor.moveToFirst()) {
                content.addView(text("No events logged for this date.", 15, MUTED), fullWidth());
            } else {
                do {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                    String eventName = cursor.getString(cursor.getColumnIndexOrThrow("event_name"));
                    String note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
                    long timeMs = cursor.getLong(cursor.getColumnIndexOrThrow("time_ms"));
                    LinearLayout row = panel();
                    row.addView(text(formatTimeForDate(timeMs, date) + "  " + eventName, 16, TEXT), fullWidth());
                    if (note != null && !note.isEmpty()) {
                        row.addView(text(note, 14, MUTED), fullWidth());
                    }
                    Button delete = button("Delete wrong event");
                    delete.setOnClickListener(view -> {
                        database.deleteSpecialEvent(id);
                        toast("Event entry deleted");
                        showEvents(date);
                    });
                    row.addView(delete, fullWidth());
                    content.addView(row, fullWidth());
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }

        content.addView(sectionTitle("Event Frequency"));
        StringBuilder summary = new StringBuilder();
        appendEventFrequency(summary, date, date, "Today");
        appendEventFrequency(summary, dateOffset(date, -6), date, "Last 7 days");
        content.addView(panelText(summary.toString()), fullWidth());
    }

    private void showAnalysis(String date) {
        content.removeAllViews();
        content.addView(sectionTitle("Analysis"));
        EditText dateInput = input("Date yyyy-MM-dd");
        dateInput.setText(date);
        Button refresh = button("Refresh");
        refresh.setOnClickListener(view -> {
            String chosen = dateInput.getText().toString().trim();
            if (!isDate(chosen)) {
                toast("Use date as yyyy-MM-dd");
                return;
            }
            showAnalysis(chosen);
        });
        content.addView(dateInput, fullWidth());
        content.addView(dateControls(dateInput), fullWidth());
        content.addView(refresh, fullWidth());

        String weekStart = dateOffset(date, -6);
        int waterToday = database.sumWaterBetween(date, date);
        int waterEntriesToday = database.countWaterBetween(date, date);
        int waterWeek = database.sumWaterBetween(weekStart, date);
        int doneToday = database.countCompletionsBetween(date, date);
        int doneWeek = database.countCompletionsBetween(weekStart, date);
        int workloadBlocks = database.countDiaryWorkloadBlocks(date);
        int eventsToday = database.countSpecialEventsBetween(date, date);
        int eventsWeek = database.countSpecialEventsBetween(weekStart, date);
        int sleepToday = database.getSleepMinutes(date);
        int sleepWeek = database.sumSleepMinutesBetween(weekStart, date);
        int sleepDays = database.countSleepRecordsBetween(weekStart, date);

        StringBuilder summary = new StringBuilder();
        summary.append("Date: ").append(date).append('\n');
        summary.append("Water today: ").append(waterToday).append(" ml in ").append(waterEntriesToday).append(" checks\n");
        summary.append("Water 7 days: ").append(waterWeek).append(" ml, avg ").append(waterWeek / 7).append(" ml/day\n");
        summary.append("Successful to-dos today: ").append(doneToday).append('\n');
        summary.append("Successful to-dos 7 days: ").append(doneWeek).append('\n');
        summary.append("Sleep today: ").append(sleepToday > 0 ? formatDuration(sleepToday) : "not recorded").append('\n');
        summary.append("Sleep 7 days: ");
        if (sleepDays > 0) {
            summary.append(formatDuration(sleepWeek / sleepDays)).append(" avg across ").append(sleepDays).append(" records\n");
        } else {
            summary.append("not recorded\n");
        }
        summary.append("Special events today: ").append(eventsToday).append('\n');
        summary.append("Special events 7 days: ").append(eventsWeek).append('\n');
        summary.append("Workload blocks filled today: ").append(workloadBlocks).append("/12\n");
        addDiarySummary(date, summary);
        summary.append('\n');
        appendEventFrequency(summary, date, date, "Event frequency today");
        appendEventFrequency(summary, weekStart, date, "Event frequency 7 days");
        content.addView(panelText(summary.toString()), fullWidth());
    }

    private void showExport() {
        content.removeAllViews();
        content.addView(sectionTitle("Export"));
        content.addView(text("Export writes all to-dos, completions, diary entries, sleep records, water checks, and special events as JSON to Download/TodoDiary.", 15, MUTED), fullWidth());
        Button export = button("Export JSON");
        export.setOnClickListener(view -> exportData());
        content.addView(export, fullWidth());
    }

    private void showGuide() {
        content.removeAllViews();
        content.addView(sectionTitle("Guide"));
        String guide = ""
                + "To-do\n"
                + "Create a task, choose weekdays, and set reminder time. Use Pick time for normal clock time, or enter TV time manually: 28 means 28:00, which is tomorrow 04:00 for the selected weekday. Android will show reminder notifications if notification permission is allowed. Each task can be marked Success only once per day.\n\n"
                + "Diary\n"
                + "Open Diary for the date, use Pick date or -1d/+1d to edit nearby days, then fill optional wake/sleep notes, optional nap, lunch, notes, and workload blocks. Auto fill copies successful to-dos into the matching two-hour workload blocks.\n\n"
                + "Sleep\n"
                + "Open Sleep for the wake date, enter wake time and last night's sleep start, then save. Analysis calculates daily sleep duration and 7-day average from these records. TV time works here too: if the wake date is Monday, 28 means Tuesday 04:00 while still grouped with Monday.\n\n"
                + "Water\n"
                + "Each +250 ml button creates one timestamped water check. Pick date/time or enter TV time manually. If the time or entry is wrong, delete it and add it again.\n\n"
                + "Events\n"
                + "Register a special event name first, such as headache, medicine, mood dip, workout, coffee, or pain. Then log every occurrence with date, time, and optional note. The same event can happen many times in one day, including TV-time entries like 28.\n\n"
                + "Analysis\n"
                + "Use Analysis to check water totals, successful to-dos, sleep duration, workload blocks, and special-event frequency for the selected day and last 7 days.\n\n"
                + "Export\n"
                + "Export writes a local JSON backup to Download/TodoDiary. Data stays offline on the phone unless you move or share the exported file yourself.";
        content.addView(panelText(guide), fullWidth());
    }

    private List<EventType> loadEventTypes() {
        List<EventType> eventTypes = new ArrayList<>();
        Cursor cursor = database.getEventTypes();
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
                eventTypes.add(new EventType(id, name, note == null ? "" : note));
            }
        } finally {
            cursor.close();
        }
        return eventTypes;
    }

    private List<String> eventTypeNames(List<EventType> eventTypes) {
        List<String> names = new ArrayList<>();
        for (EventType eventType : eventTypes) {
            names.add(eventType.name);
        }
        return names;
    }

    private void appendEventFrequency(StringBuilder summary, String startDate, String endDate, String label) {
        summary.append(label).append(":\n");
        Cursor cursor = database.getSpecialEventFrequency(startDate, endDate);
        try {
            if (!cursor.moveToFirst()) {
                summary.append("  none\n");
                return;
            }
            do {
                summary.append("  ")
                        .append(cursor.getString(cursor.getColumnIndexOrThrow("event_name")))
                        .append(": ")
                        .append(cursor.getInt(cursor.getColumnIndexOrThrow("frequency")))
                        .append('\n');
            } while (cursor.moveToNext());
        } finally {
            cursor.close();
        }
    }

    private void loadDiaryInto(String date, EditText wake, EditText sleep, EditText nap, EditText lunch, EditText notes, EditText[] blocks) {
        Cursor cursor = database.getDiary(date);
        try {
            if (!cursor.moveToFirst()) {
                return;
            }
            wake.setText(cursor.getString(cursor.getColumnIndexOrThrow("wake")));
            sleep.setText(cursor.getString(cursor.getColumnIndexOrThrow("sleep")));
            nap.setText(cursor.getString(cursor.getColumnIndexOrThrow("nap")));
            lunch.setText(cursor.getString(cursor.getColumnIndexOrThrow("lunch")));
            notes.setText(cursor.getString(cursor.getColumnIndexOrThrow("notes")));
            JSONArray workload = new JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("workload")));
            for (int i = 0; i < blocks.length; i++) {
                blocks[i].setText(workload.optString(i, ""));
            }
        } catch (JSONException ignored) {
        } finally {
            cursor.close();
        }
    }

    private void autoFillWorkload(String date, EditText[] blocks) {
        StringBuilder[] values = new StringBuilder[BLOCK_LABELS.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = new StringBuilder();
        }
        Cursor cursor = database.getCompletionsForDate(date);
        try {
            while (cursor.moveToNext()) {
                String title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
                long completedMs = cursor.getLong(cursor.getColumnIndexOrThrow("completed_ms"));
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(completedMs);
                int block = Math.min(11, Math.max(0, calendar.get(Calendar.HOUR_OF_DAY) / 2));
                if (values[block].length() > 0) {
                    values[block].append(", ");
                }
                values[block].append(title);
            }
        } finally {
            cursor.close();
        }
        for (int i = 0; i < blocks.length; i++) {
            if (values[i].length() > 0) {
                blocks[i].setText(values[i].toString());
            }
        }
    }

    private void addDiarySummary(String date, StringBuilder summary) {
        Cursor cursor = database.getDiary(date);
        try {
            if (cursor.moveToFirst()) {
                String wake = cursor.getString(cursor.getColumnIndexOrThrow("wake"));
                String sleep = cursor.getString(cursor.getColumnIndexOrThrow("sleep"));
                if (wake != null && !wake.isEmpty()) {
                    summary.append("Diary wake: ").append(wake).append('\n');
                }
                if (sleep != null && !sleep.isEmpty()) {
                    summary.append("Diary sleep note: ").append(sleep).append('\n');
                }
                String nap = cursor.getString(cursor.getColumnIndexOrThrow("nap"));
                if (nap != null && !nap.isEmpty()) {
                    summary.append("Nap: ").append(nap).append('\n');
                }
                summary.append("Lunch: ").append(cursor.getString(cursor.getColumnIndexOrThrow("lunch"))).append('\n');
            } else {
                summary.append("Diary: not saved for this date\n");
            }
        } finally {
            cursor.close();
        }
        addSleepRecordSummary(date, summary);
    }

    private void addSleepRecordSummary(String date, StringBuilder summary) {
        Cursor cursor = database.getSleepRecord(date);
        try {
            if (!cursor.moveToFirst()) {
                summary.append("Sleep record: not saved for this date\n");
                return;
            }
            summary.append("Sleep record: ")
                    .append(cursor.getString(cursor.getColumnIndexOrThrow("last_sleep_time")))
                    .append(" -> ")
                    .append(cursor.getString(cursor.getColumnIndexOrThrow("wake_time")))
                    .append(", ")
                    .append(formatDuration(cursor.getInt(cursor.getColumnIndexOrThrow("sleep_minutes"))))
                    .append('\n');
            String note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
            if (note != null && !note.isEmpty()) {
                summary.append("Sleep note: ").append(note).append('\n');
            }
        } finally {
            cursor.close();
        }
    }

    private void exportData() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXPORT_STORAGE);
            return;
        }
        try {
            JSONObject json = database.exportJson();
            String fileName = "tododiary-export-" + exportFormat.format(new Date()) + ".json";
            Uri uri = writeExport(fileName, json.toString(2));
            toast("Exported " + uri);
        } catch (IOException | JSONException e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private Uri writeExport(String fileName, String content) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TodoDiary");
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Cannot create export file");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Cannot open export file");
                }
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return uri;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TodoDiary");
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return Uri.fromFile(file);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_EXPORT_STORAGE && pendingExport) {
            pendingExport = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportData();
            } else {
                toast("Storage permission denied");
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout dateControls(EditText dateInput) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = button("Pick date");
        pick.setOnClickListener(view -> showDatePicker(dateInput));
        Button previous = button("-1d");
        previous.setOnClickListener(view -> shiftDateInput(dateInput, -1));
        Button next = button("+1d");
        next.setOnClickListener(view -> shiftDateInput(dateInput, 1));
        row.addView(pick, tabParams());
        row.addView(previous, tabParams());
        row.addView(next, tabParams());
        return row;
    }

    private LinearLayout timeControls(EditText timeInput, boolean allowTvTime) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = button("Pick time");
        pick.setOnClickListener(view -> showTimePicker(timeInput));
        Button now = button("Now");
        now.setOnClickListener(view -> timeInput.setText(currentTime()));
        row.addView(pick, tabParams());
        row.addView(now, tabParams());
        if (allowTvTime) {
            Button tv = button("+24 TV");
            tv.setOnClickListener(view -> applyTvTimeOffset(timeInput));
            row.addView(tv, tabParams());
        }
        return row;
    }

    private void showDatePicker(EditText dateInput) {
        Calendar calendar = calendarFromDate(dateInput.getText().toString().trim());
        new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    dateInput.setText(dateFormat.format(selected.getTime()));
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void showTimePicker(EditText timeInput) {
        TimeValue current = parseFlexibleTime(timeInput.getText().toString().trim());
        Calendar now = Calendar.getInstance();
        int hour = current == null ? now.get(Calendar.HOUR_OF_DAY) : current.hour % 24;
        int minute = current == null ? now.get(Calendar.MINUTE) : current.minute;
        new TimePickerDialog(
                this,
                (view, hourOfDay, selectedMinute) ->
                        timeInput.setText(String.format(Locale.US, "%02d:%02d", hourOfDay, selectedMinute)),
                hour,
                minute,
                true
        ).show();
    }

    private void shiftDateInput(EditText dateInput, int days) {
        Calendar calendar = calendarFromDate(dateInput.getText().toString().trim());
        calendar.add(Calendar.DAY_OF_YEAR, days);
        dateInput.setText(dateFormat.format(calendar.getTime()));
    }

    private void applyTvTimeOffset(EditText timeInput) {
        TimeValue current = parseFlexibleTime(timeInput.getText().toString().trim());
        if (current == null) {
            current = parseFlexibleTime(currentTime());
        }
        if (current == null) {
            return;
        }
        int hour = current.hour < 24 ? current.hour + 24 : current.hour;
        timeInput.setText(String.format(Locale.US, "%02d:%02d", hour, current.minute));
    }

    private CheckBox[] weekdayBoxes(boolean weekdaysOnly) {
        CheckBox[] boxes = new CheckBox[WEEKDAY_LABELS.length];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = checkbox(WEEKDAY_LABELS[i]);
            boxes[i].setChecked(!weekdaysOnly || i < 5);
        }
        return boxes;
    }

    private LinearLayout weekdayRow(CheckBox[] boxes, int start, int end) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = start; i < end; i++) {
            row.addView(boxes[i], tabParams());
        }
        return row;
    }

    private String selectedWeekdays(CheckBox[] boxes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].isChecked()) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(WEEKDAY_VALUES[i]);
            }
        }
        return builder.toString();
    }

    private String formatWeekdays(String weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < WEEKDAY_VALUES.length; i++) {
            if (containsDay(weekdays, WEEKDAY_VALUES[i])) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(WEEKDAY_LABELS[i]);
            }
        }
        return builder.toString();
    }

    private boolean containsDay(String weekdays, int day) {
        String[] parts = weekdays.split(",");
        for (String part : parts) {
            if (part.trim().equals(String.valueOf(day))) {
                return true;
            }
        }
        return false;
    }

    private boolean isDate(String value) {
        dateFormat.setLenient(false);
        try {
            dateFormat.parse(value);
            return true;
        } catch (ParseException ignored) {
            return false;
        }
    }

    private boolean isTime(String value) {
        return parseFlexibleTime(value) != null;
    }

    private long parseDateTime(String date, String time) {
        Calendar calendar = calendarFromDate(date);
        TimeValue timeValue = parseFlexibleTime(time);
        if (timeValue == null) {
            return System.currentTimeMillis();
        }
        calendar.add(Calendar.DAY_OF_YEAR, timeValue.hour / 24);
        calendar.set(Calendar.HOUR_OF_DAY, timeValue.hour % 24);
        calendar.set(Calendar.MINUTE, timeValue.minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private int calculateSleepMinutes(String date, String wake, String lastSleep) {
        TimeValue sleepValue = parseFlexibleTime(lastSleep);
        if (sleepValue == null) {
            return -1;
        }
        long wakeMs = parseDateTime(date, wake);
        Calendar sleepCalendar = calendarFromDate(date);
        if (sleepValue.hour >= 24) {
            sleepCalendar.add(Calendar.DAY_OF_YEAR, sleepValue.hour / 24);
        }
        sleepCalendar.set(Calendar.HOUR_OF_DAY, sleepValue.hour % 24);
        sleepCalendar.set(Calendar.MINUTE, sleepValue.minute);
        sleepCalendar.set(Calendar.SECOND, 0);
        sleepCalendar.set(Calendar.MILLISECOND, 0);

        long sleepMs = sleepCalendar.getTimeInMillis();
        if (sleepValue.hour < 24 && sleepMs >= wakeMs) {
            sleepCalendar.add(Calendar.DAY_OF_YEAR, -1);
            sleepMs = sleepCalendar.getTimeInMillis();
        }
        long minutes = (wakeMs - sleepMs) / (60L * 1000L);
        if (minutes < Integer.MIN_VALUE || minutes > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) minutes;
    }

    private Calendar calendarFromDate(String date) {
        Calendar calendar = Calendar.getInstance();
        try {
            Date parsed = dateFormat.parse(date);
            if (parsed != null) {
                calendar.setTime(parsed);
            }
        } catch (ParseException ignored) {
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private TimeValue parseFlexibleTime(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String hourPart;
        String minutePart;
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", -1);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return null;
            }
            hourPart = parts[0];
            minutePart = parts[1];
        } else {
            hourPart = trimmed;
            minutePart = "0";
        }

        if (!hourPart.matches("\\d{1,2}") || !minutePart.matches("\\d{1,2}")) {
            return null;
        }

        int hour;
        int minute;
        try {
            hour = Integer.parseInt(hourPart);
            minute = Integer.parseInt(minutePart);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (hour < 0 || hour > 47 || minute < 0 || minute > 59) {
            return null;
        }
        return new TimeValue(hour, minute);
    }

    private String normalizeTimeInput(String value) {
        TimeValue timeValue = parseFlexibleTime(value);
        if (timeValue == null) {
            return null;
        }
        return String.format(Locale.US, "%02d:%02d", timeValue.hour, timeValue.minute);
    }

    private String normalizeOptionalTimeOrText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String normalized = normalizeTimeInput(value);
        return normalized == null ? value.trim() : normalized;
    }

    private String currentDate() {
        return dateFormat.format(new Date());
    }

    private String currentTime() {
        return timeFormat.format(new Date());
    }

    private String formatTime(long ms) {
        return timeFormat.format(new Date(ms));
    }

    private String formatDuration(int minutes) {
        if (minutes <= 0) {
            return "not recorded";
        }
        int hours = minutes / 60;
        int remain = minutes % 60;
        if (hours == 0) {
            return remain + "m";
        }
        if (remain == 0) {
            return hours + "h";
        }
        return hours + "h " + remain + "m";
    }

    private String formatTimeForDate(long ms, String date) {
        Calendar start = calendarFromDate(date);
        long diffMs = ms - start.getTimeInMillis();
        long minuteMs = 60L * 1000L;
        if (diffMs >= 0L && diffMs < 48L * 60L * minuteMs) {
            long totalMinutes = diffMs / minuteMs;
            long hour = totalMinutes / 60L;
            long minute = totalMinutes % 60L;
            return String.format(Locale.US, "%02d:%02d", hour, minute);
        }
        return formatTime(ms);
    }

    private String dateOffset(String date, int days) {
        Calendar calendar = Calendar.getInstance();
        try {
            Date parsed = dateFormat.parse(date);
            if (parsed != null) {
                calendar.setTime(parsed);
            }
        } catch (ParseException ignored) {
        }
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return dateFormat.format(calendar.getTime());
    }

    private TextView sectionTitle(String value) {
        TextView textView = text(value, 20, TEXT);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setPadding(0, dp(14), 0, dp(6));
        return textView;
    }

    private TextView panelText(String value) {
        TextView textView = text(value, 16, TEXT);
        textView.setBackgroundColor(PANEL);
        textView.setPadding(dp(12), dp(12), dp(12), dp(12));
        return textView;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackgroundColor(PANEL);
        return panel;
    }

    private TextView text(String value, int size, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(false);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(0xff77858d);
        return editText;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private Button tab(String value, View.OnClickListener listener) {
        Button button = button(value);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        return button;
    }

    private CheckBox checkbox(String value) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(value);
        checkBox.setTextSize(13);
        checkBox.setTextColor(TEXT);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        return checkBox;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(2), dp(3), dp(2), dp(3));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static final class EventType {
        private final long id;
        private final String name;
        private final String note;

        private EventType(long id, String name, String note) {
            this.id = id;
            this.name = name;
            this.note = note;
        }
    }

    private static final class TimeValue {
        private final int hour;
        private final int minute;

        private TimeValue(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }
    }
}
