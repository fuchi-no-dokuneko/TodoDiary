package dev.tododiary;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 10;
    private static final int REQUEST_EXPORT_STORAGE = 11;
    private static final int REQUEST_IMPORT_JSON = 12;
    private static final int REQUEST_COMPLETION_IMAGE = 13;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_BACKUP_BYTES = 150 * 1024 * 1024;
    private static final int EVENT_MODE_HISTORY = 0;
    private static final int EVENT_MODE_LOG = 1;
    private static final int EVENT_MODE_TYPES = 2;
    private static final int BG = 0xff101418;
    private static final int TODAY_BG = 0xff173329;
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
    private static final String[] DEBUG_TABLES = {
            "todos", "todo_completions", "diary", "water",
            "event_types", "special_events", "sleep_records"
    };

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat exportFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final SimpleDateFormat displayDateFormat = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US);

    private AppDatabase database;
    private LinearLayout content;
    private boolean pendingExport;
    private SpecialEvent pendingDeletedEvent;
    private TodoCompletion pendingImageCompletion;
    private String pendingImageReturnDate;

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

        LinearLayout tabsMiddle = new LinearLayout(this);
        tabsMiddle.setOrientation(LinearLayout.HORIZONTAL);
        tabsMiddle.setGravity(Gravity.CENTER);
        tabsMiddle.addView(tab("Events", view -> showEvents(currentDate())), tabParams());
        tabsMiddle.addView(tab("Analysis", view -> showAnalysis(currentDate())), tabParams());
        root.addView(tabsMiddle, fullWidth());

        LinearLayout tabsBottom = new LinearLayout(this);
        tabsBottom.setOrientation(LinearLayout.HORIZONTAL);
        tabsBottom.setGravity(Gravity.CENTER);
        tabsBottom.addView(tab("Backup", view -> showExport()), tabParams());
        tabsBottom.addView(tab("Debug", view -> showDebug(false)), tabParams());
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
        showTodos(currentDate());
    }

    private void showTodos(String date) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        content.addView(sectionTitle("To-do Records"));
        addImmediateDateNavigation(selectedDate, this::showTodos);
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
        CheckBox allowImage = checkbox("Allow an image on each daily completion");
        content.addView(allowImage, fullWidth());

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
            long id = database.addTodo(
                    title,
                    notesInput.getText().toString().trim(),
                    time,
                    weekdays,
                    allowImage.isChecked()
            );
            TodoScheduler.scheduleAll(this);
            toast("Added to-do #" + id);
            showTodos(selectedDate);
        });
        content.addView(add, fullWidth());

        content.addView(sectionTitle("To-do Schedules"));
        Cursor cursor = database.getTodos();
        try {
            if (!cursor.moveToFirst()) {
                content.addView(panelText("No to-do schedules yet."), fullWidth());
            } else {
                do {
                    TodoDefinition todo = new TodoDefinition(
                            cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            cursor.getString(cursor.getColumnIndexOrThrow("notes")),
                            cursor.getString(cursor.getColumnIndexOrThrow("time")),
                            cursor.getString(cursor.getColumnIndexOrThrow("weekdays")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("image_enabled")) == 1
                    );
                    LinearLayout item = panel();
                    item.addView(text(todo.title, 18, TEXT), fullWidth());
                    item.addView(text(todo.time + "  " + formatWeekdays(todo.weekdays), 14, ACCENT), fullWidth());
                    if (todo.notes != null && !todo.notes.isEmpty()) {
                        item.addView(text(todo.notes, 14, MUTED), fullWidth());
                    }
                    item.addView(text(
                            "Completion image: " + (todo.imageEnabled ? "allowed" : "disabled"),
                            14,
                            todo.imageEnabled ? ACCENT : MUTED
                    ), fullWidth());
                    boolean completed = database.hasTodoCompletion(todo.id, selectedDate);
                    Button claim = button(completed
                            ? "Completion claimed on " + selectedDate
                            : "Claim completion on " + selectedDate);
                    claim.setEnabled(!completed);
                    claim.setOnClickListener(view -> {
                        boolean saved = database.markTodoDone(
                                todo.id,
                                parseDateTime(selectedDate, currentTime()),
                                selectedDate
                        );
                        toast(saved ? "Completion saved for " + selectedDate : "Already claimed for " + selectedDate);
                        showTodos(selectedDate);
                    });
                    item.addView(claim, fullWidth());

                    LinearLayout scheduleActions = new LinearLayout(this);
                    scheduleActions.setOrientation(LinearLayout.HORIZONTAL);
                    Button edit = button("Edit schedule");
                    edit.setOnClickListener(view -> showEditTodoDialog(todo, selectedDate));
                    Button delete = button("Delete schedule");
                    delete.setOnClickListener(view -> confirmDeleteTodo(todo, selectedDate));
                    scheduleActions.addView(edit, tabParams());
                    scheduleActions.addView(delete, tabParams());
                    item.addView(scheduleActions, fullWidth());
                    content.addView(item, fullWidth());
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }

        content.addView(sectionTitle("Completed on " + humanDate(selectedDate)));
        Cursor completions = database.getCompletionsForDate(selectedDate);
        try {
            if (!completions.moveToFirst()) {
                content.addView(panelText("No completion records for " + selectedDate + "."), fullWidth());
            } else {
                do {
                    TodoCompletion completion = new TodoCompletion(
                            completions.getLong(completions.getColumnIndexOrThrow("id")),
                            completions.getLong(completions.getColumnIndexOrThrow("todo_id")),
                            completions.getString(completions.getColumnIndexOrThrow("title")),
                            completions.getLong(completions.getColumnIndexOrThrow("completed_ms")),
                            completions.getString(completions.getColumnIndexOrThrow("date")),
                            completions.getInt(completions.getColumnIndexOrThrow("image_allowed")) == 1,
                            completions.getString(completions.getColumnIndexOrThrow("image_path")),
                            completions.getString(completions.getColumnIndexOrThrow("image_mime"))
                    );
                    LinearLayout row = panel();
                    row.addView(text(completion.title, 17, TEXT), fullWidth());
                    row.addView(text(
                            humanDate(completion.date) + " | " + completion.date + " | " +
                                    formatTimeForDate(completion.completedMs, completion.date),
                            14,
                            ACCENT
                    ), fullWidth());
                    addCompletionImageControls(row, completion, selectedDate);
                    LinearLayout actions = new LinearLayout(this);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    Button edit = button("Edit completion");
                    edit.setOnClickListener(view -> showEditTodoCompletionDialog(completion, selectedDate));
                    Button delete = button("Delete completion");
                    delete.setOnClickListener(view -> confirmDeleteTodoCompletion(completion, selectedDate));
                    actions.addView(edit, tabParams());
                    actions.addView(delete, tabParams());
                    row.addView(actions, fullWidth());
                    content.addView(row, fullWidth());
                } while (completions.moveToNext());
            }
        } finally {
            completions.close();
        }
    }

    private void showEditTodoDialog(TodoDefinition todo, String returnDate) {
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText titleInput = input("Title");
        titleInput.setText(todo.title);
        EditText notesInput = input("Notes");
        notesInput.setText(todo.notes == null ? "" : todo.notes);
        EditText timeInput = input("Reminder time HH:mm");
        timeInput.setText(todo.time);
        CheckBox[] boxes = weekdayBoxes(false);
        for (int i = 0; i < boxes.length; i++) {
            boxes[i].setChecked(containsDay(todo.weekdays, WEEKDAY_VALUES[i]));
        }
        editor.addView(titleInput, fullWidth());
        editor.addView(notesInput, fullWidth());
        editor.addView(timeInput, fullWidth());
        editor.addView(timeControls(timeInput, true), fullWidth());
        editor.addView(text("Reminder weekdays", 14, MUTED), fullWidth());
        editor.addView(weekdayRow(boxes, 0, 4), fullWidth());
        editor.addView(weekdayRow(boxes, 4, 7), fullWidth());
        CheckBox allowImage = checkbox("Allow an image on each daily completion");
        allowImage.setChecked(todo.imageEnabled);
        editor.addView(allowImage, fullWidth());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(editor);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit to-do schedule")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = titleInput.getText().toString().trim();
            String time = normalizeTimeInput(timeInput.getText().toString().trim());
            String weekdays = selectedWeekdays(boxes);
            if (title.isEmpty()) {
                toast("Title required");
                return;
            }
            if (time == null) {
                toast("Use time as HH:mm or TV time like 28");
                return;
            }
            if (weekdays.isEmpty()) {
                toast("Select at least one weekday");
                return;
            }
            database.updateTodo(
                    todo.id,
                    title,
                    notesInput.getText().toString().trim(),
                    time,
                    weekdays,
                    allowImage.isChecked()
            );
            TodoScheduler.scheduleAll(this);
            dialog.dismiss();
            toast("To-do schedule updated");
            showTodos(returnDate);
        }));
        dialog.show();
    }

    private void confirmDeleteTodo(TodoDefinition todo, String returnDate) {
        new AlertDialog.Builder(this)
                .setTitle("Delete to-do schedule?")
                .setMessage(
                        "DELETE MODE: ONE SCHEDULE ONLY\n\n" + todo.title +
                                "\n\nPast completion records will remain editable and will not be deleted."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete schedule", (dialog, which) -> {
                    TodoScheduler.cancelTodo(this, todo.id);
                    database.deleteTodo(todo.id);
                    TodoScheduler.scheduleAll(this);
                    toast("Schedule deleted; completion history kept");
                    showTodos(returnDate);
                })
                .show();
    }

    private void showEditTodoCompletionDialog(TodoCompletion completion, String returnDate) {
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText titleInput = input("Completion title");
        titleInput.setText(completion.title);
        EditText dateInput = input("Completion date yyyy-MM-dd");
        dateInput.setText(completion.date);
        EditText timeInput = input("Completion time HH:mm");
        timeInput.setText(formatTimeForDate(completion.completedMs, completion.date));
        editor.addView(titleInput, fullWidth());
        editor.addView(dateInput, fullWidth());
        editor.addView(dateControls(dateInput), fullWidth());
        editor.addView(timeInput, fullWidth());
        editor.addView(timeControls(timeInput, true), fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit completion record")
                .setView(editor)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = titleInput.getText().toString().trim();
            String chosenDate = dateInput.getText().toString().trim();
            String chosenTime = normalizeTimeInput(timeInput.getText().toString().trim());
            if (title.isEmpty()) {
                toast("Completion title required");
                return;
            }
            if (!isDate(chosenDate) || chosenTime == null) {
                toast("Use yyyy-MM-dd and HH:mm, or TV time like 28");
                return;
            }
            boolean updated = database.updateTodoCompletion(
                    completion.id,
                    title,
                    parseDateTime(chosenDate, chosenTime),
                    chosenDate
            );
            if (!updated) {
                toast("That to-do already has a completion on the chosen date");
                return;
            }
            dialog.dismiss();
            toast("Completion record updated");
            showTodos(chosenDate);
        }));
        dialog.show();
    }

    private void confirmDeleteTodoCompletion(TodoCompletion completion, String returnDate) {
        new AlertDialog.Builder(this)
                .setTitle("Delete one completion record?")
                .setMessage(
                        "DELETE MODE: ONE COMPLETION ONLY\n\n" + completion.title + "\n" +
                                humanDate(completion.date) + " (" + completion.date + ")\n" +
                                formatTimeForDate(completion.completedMs, completion.date) +
                                "\n\nThe to-do schedule and other completion records will remain."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete one completion", (dialog, which) -> {
                    database.deleteTodoCompletion(completion.id);
                    toast("One completion record deleted");
                    showTodos(returnDate);
                })
                .show();
    }

    private void addCompletionImageControls(LinearLayout row, TodoCompletion completion, String returnDate) {
        if (completion.imagePath != null && !completion.imagePath.isEmpty()) {
            ImageView preview = completionImagePreview(completion.imagePath);
            if (preview != null) {
                row.addView(preview, fullWidth());
            } else {
                row.addView(text("Saved image file is missing or unreadable.", 14, MUTED), fullWidth());
            }
        }
        if (!completion.imageAllowed) {
            return;
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button attach = button(completion.imagePath == null || completion.imagePath.isEmpty()
                ? "Attach image"
                : "Replace image");
        attach.setOnClickListener(view -> chooseCompletionImage(completion, returnDate));
        actions.addView(attach, tabParams());
        if (completion.imagePath != null && !completion.imagePath.isEmpty()) {
            Button remove = button("Remove image");
            remove.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Remove completion image?")
                    .setMessage(
                            "IMAGE MODE: THIS COMPLETION ONLY\n\n" + completion.title + "\n" +
                                    humanDate(completion.date) + " (" + completion.date + ")\n\n" +
                                    "The completion record and all other images will remain."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove this image", (dialog, which) -> {
                        database.removeTodoCompletionImage(completion.id);
                        toast("Completion image removed");
                        showTodos(returnDate);
                    })
                    .show());
            actions.addView(remove, tabParams());
        }
        row.addView(actions, fullWidth());
    }

    private ImageView completionImagePreview(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 800) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) {
            return null;
        }
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setMaxHeight(dp(240));
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageView.setContentDescription("Image attached to " + file.getName());
        return imageView;
    }

    private void chooseCompletionImage(TodoCompletion completion, String returnDate) {
        pendingImageCompletion = completion;
        pendingImageReturnDate = returnDate;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_COMPLETION_IMAGE);
    }

    private void showDiary(String date) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        content.addView(sectionTitle("Diary"));
        addImmediateDateNavigation(selectedDate, this::showDiary);
        content.addView(text("Daily notes, lunch, nap, and workload. Wake and sleep fields are optional here; use Sleep for sleep-duration analysis.", 15, MUTED), fullWidth());

        EditText wakeInput = input("Wake up time optional HH:mm");
        EditText sleepInput = input("Sleep time optional HH:mm");
        EditText napInput = input("Nap time optional HH:mm or text");
        EditText lunchInput = input("Lunch");
        EditText notesInput = input("Diary notes");
        EditText[] blockInputs = new EditText[BLOCK_LABELS.length];

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

        boolean diaryExists = loadDiaryInto(
                selectedDate,
                wakeInput,
                sleepInput,
                napInput,
                lunchInput,
                notesInput,
                blockInputs
        );

        Button autoFill = button("Auto fill");
        autoFill.setOnClickListener(view -> {
            autoFillWorkload(selectedDate, blockInputs);
            toast("Filled from completions on " + selectedDate);
        });
        content.addView(autoFill, fullWidth());

        Button save = button((diaryExists ? "Update diary for " : "Save diary for ") + selectedDate);
        save.setOnClickListener(view -> {
            String wakeRaw = wakeInput.getText().toString().trim();
            String sleepRaw = sleepInput.getText().toString().trim();
            String wake = wakeRaw.isEmpty() ? "" : normalizeTimeInput(wakeRaw);
            String sleep = sleepRaw.isEmpty() ? "" : normalizeTimeInput(sleepRaw);
            String nap = normalizeOptionalTimeOrText(napInput.getText().toString().trim());
            String lunch = lunchInput.getText().toString().trim();
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
                    selectedDate,
                    wake,
                    sleep,
                    nap,
                    lunch,
                    workload.toString(),
                    notesInput.getText().toString().trim()
            );
            toast("Diary saved for " + selectedDate);
            showDiary(selectedDate);
        });
        content.addView(save, fullWidth());
        if (diaryExists) {
            Button delete = button("Delete diary entry for " + selectedDate);
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Delete one diary entry?")
                    .setMessage(
                            "DELETE MODE: ONE DIARY DATE ONLY\n\n" + humanDate(selectedDate) +
                                    " (" + selectedDate + ")\n\nOther diary dates and all other history will remain."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete this diary entry", (dialog, which) -> {
                        database.deleteDiary(selectedDate);
                        toast("Diary entry deleted for " + selectedDate);
                        showDiary(selectedDate);
                    })
                    .show());
            content.addView(delete, fullWidth());
        }
    }

    private void showSleep(String date) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        content.addView(sectionTitle("Sleep"));
        addImmediateDateNavigation(selectedDate, this::showSleep);
        content.addView(text("Record the date you woke up, your wake time, and when last night's sleep started. This page calculates sleep duration for Analysis.", 15, MUTED), fullWidth());

        EditText wakeInput = input("Wake time HH:mm or TV time");
        EditText sleepInput = input("Last night sleep time HH:mm or TV time");
        EditText noteInput = input("Sleep note optional");
        TextView durationView = panelText("No sleep record saved for " + selectedDate);

        content.addView(wakeInput, fullWidth());
        content.addView(timeControls(wakeInput, true), fullWidth());
        content.addView(sleepInput, fullWidth());
        content.addView(timeControls(sleepInput, true), fullWidth());
        content.addView(noteInput, fullWidth());

        boolean sleepExists = false;
        Cursor sleep = database.getSleepRecord(selectedDate);
        try {
            if (sleep.moveToFirst()) {
                sleepExists = true;
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

        Button save = button((sleepExists ? "Update sleep for " : "Save sleep for ") + selectedDate);
        save.setOnClickListener(view -> {
            String wake = normalizeTimeInput(wakeInput.getText().toString().trim());
            String lastSleep = normalizeTimeInput(sleepInput.getText().toString().trim());
            if (wake == null || lastSleep == null) {
                toast("Wake and last sleep must be HH:mm or TV time like 28");
                return;
            }
            int minutes = calculateSleepMinutes(selectedDate, wake, lastSleep);
            if (minutes <= 0 || minutes > 24 * 60) {
                toast("Sleep duration must be between 1 minute and 24 hours");
                return;
            }
            database.saveSleepRecord(
                    selectedDate,
                    wake,
                    lastSleep,
                    minutes,
                    noteInput.getText().toString().trim()
            );
            toast("Sleep saved: " + formatDuration(minutes));
            showSleep(selectedDate);
        });
        content.addView(save, fullWidth());
        if (sleepExists) {
            Button delete = button("Delete sleep record for " + selectedDate);
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Delete one sleep record?")
                    .setMessage(
                            "DELETE MODE: ONE SLEEP DATE ONLY\n\n" + humanDate(selectedDate) +
                                    " (" + selectedDate + ")\n\nOther sleep records and all other history will remain."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete this sleep record", (dialog, which) -> {
                        database.deleteSleepRecord(selectedDate);
                        toast("Sleep record deleted for " + selectedDate);
                        showSleep(selectedDate);
                    })
                    .show());
            content.addView(delete, fullWidth());
        }
        content.addView(durationView, fullWidth());
    }

    private void showWater(String date) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        content.addView(sectionTitle("Water Check"));
        addImmediateDateNavigation(selectedDate, this::showWater);

        EditText timeInput = input("Time HH:mm");
        timeInput.setText(currentTime());
        content.addView(timeInput, fullWidth());
        content.addView(timeControls(timeInput, true), fullWidth());

        Button add = button("Add 250 ml on " + selectedDate);
        add.setOnClickListener(view -> {
            String time = normalizeTimeInput(timeInput.getText().toString().trim());
            if (time == null) {
                toast("Use HH:mm or TV time like 28");
                return;
            }
            database.addWater(selectedDate, parseDateTime(selectedDate, time));
            toast("Water saved");
            showWater(selectedDate);
        });
        content.addView(add, fullWidth());

        content.addView(sectionTitle("Water entries on " + humanDate(selectedDate)));
        Cursor cursor = database.getWaterForDate(selectedDate);
        int total = 0;
        try {
            if (!cursor.moveToFirst()) {
                content.addView(panelText("No water entries for " + selectedDate + "."), fullWidth());
            } else {
                do {
                    WaterEntry entry = new WaterEntry(
                            cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("amount_ml")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("time_ms")),
                            cursor.getString(cursor.getColumnIndexOrThrow("date"))
                    );
                    total += entry.amountMl;
                    LinearLayout row = panel();
                    row.addView(text(entry.amountMl + " ml", 17, TEXT), fullWidth());
                    row.addView(text(
                            humanDate(entry.date) + " | " + entry.date + " | " +
                                    formatTimeForDate(entry.timeMs, entry.date),
                            14,
                            ACCENT
                    ), fullWidth());
                    LinearLayout actions = new LinearLayout(this);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    Button edit = button("Edit entry");
                    edit.setOnClickListener(view -> showEditWaterDialog(entry));
                    Button delete = button("Delete entry");
                    delete.setOnClickListener(view -> confirmDeleteWater(entry, selectedDate));
                    actions.addView(edit, tabParams());
                    actions.addView(delete, tabParams());
                    row.addView(actions, fullWidth());
                    content.addView(row, fullWidth());
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        content.addView(text("Total: " + total + " ml", 18, ACCENT), fullWidth());
    }

    private void showEditWaterDialog(WaterEntry entry) {
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText amountInput = input("Amount in ml");
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        amountInput.setText(String.valueOf(entry.amountMl));
        EditText dateInput = input("Entry date yyyy-MM-dd");
        dateInput.setText(entry.date);
        EditText timeInput = input("Entry time HH:mm");
        timeInput.setText(formatTimeForDate(entry.timeMs, entry.date));
        editor.addView(amountInput, fullWidth());
        editor.addView(dateInput, fullWidth());
        editor.addView(dateControls(dateInput), fullWidth());
        editor.addView(timeInput, fullWidth());
        editor.addView(timeControls(timeInput, true), fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit water entry")
                .setView(editor)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int amount;
            try {
                amount = Integer.parseInt(amountInput.getText().toString().trim());
            } catch (NumberFormatException ignoredNumber) {
                toast("Enter a valid water amount");
                return;
            }
            String chosenDate = dateInput.getText().toString().trim();
            String chosenTime = normalizeTimeInput(timeInput.getText().toString().trim());
            if (amount <= 0) {
                toast("Water amount must be greater than zero");
                return;
            }
            if (!isDate(chosenDate) || chosenTime == null) {
                toast("Use yyyy-MM-dd and HH:mm, or TV time like 28");
                return;
            }
            database.updateWater(
                    entry.id,
                    amount,
                    chosenDate,
                    parseDateTime(chosenDate, chosenTime)
            );
            dialog.dismiss();
            toast("Water entry updated");
            showWater(chosenDate);
        }));
        dialog.show();
    }

    private void confirmDeleteWater(WaterEntry entry, String returnDate) {
        new AlertDialog.Builder(this)
                .setTitle("Delete one water entry?")
                .setMessage(
                        "DELETE MODE: ONE WATER ENTRY ONLY\n\n" + entry.amountMl + " ml\n" +
                                humanDate(entry.date) + " (" + entry.date + ")\n" +
                                formatTimeForDate(entry.timeMs, entry.date) +
                                "\n\nOther water entries and all other history will remain."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete one entry", (dialog, which) -> {
                    database.deleteWater(entry.id);
                    toast("One water entry deleted");
                    showWater(returnDate);
                })
                .show();
    }

    private void showEvents(String date) {
        showEvents(date, EVENT_MODE_HISTORY);
    }

    private void showEvents(String date, int mode) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        applyDateBackground(selectedDate);
        content.addView(sectionTitle("Special Events"));
        addEventDateHeader(selectedDate, mode);
        addEventModeTabs(selectedDate, mode);

        if (mode == EVENT_MODE_LOG) {
            showEventLog(selectedDate);
        } else if (mode == EVENT_MODE_TYPES) {
            showEventTypes(selectedDate);
        } else {
            showEventHistory(selectedDate);
        }
    }

    private void addEventDateHeader(String date, int mode) {
        String today = date.equals(currentDate()) ? "Today\n" : "";
        content.addView(panelText(today + humanDate(date) + "\n" + date), fullWidth());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("Previous");
        previous.setOnClickListener(view -> showEvents(dateOffset(date, -1), mode));
        Button pick = button("Select date");
        pick.setOnClickListener(view -> showDatePicker(date, chosen -> showEvents(chosen, mode)));
        Button todayButton = button("Today");
        todayButton.setOnClickListener(view -> showEvents(currentDate(), mode));
        Button next = button("Next");
        next.setOnClickListener(view -> showEvents(dateOffset(date, 1), mode));
        navigation.addView(previous, tabParams());
        navigation.addView(pick, tabParams());
        navigation.addView(todayButton, tabParams());
        navigation.addView(next, tabParams());
        content.addView(navigation, fullWidth());
    }

    private void addEventModeTabs(String date, int mode) {
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button history = button("History");
        Button log = button("Log event");
        Button types = button("Event types");
        history.setEnabled(mode != EVENT_MODE_HISTORY);
        log.setEnabled(mode != EVENT_MODE_LOG);
        types.setEnabled(mode != EVENT_MODE_TYPES);
        history.setOnClickListener(view -> showEvents(date, EVENT_MODE_HISTORY));
        log.setOnClickListener(view -> showEvents(date, EVENT_MODE_LOG));
        types.setOnClickListener(view -> showEvents(date, EVENT_MODE_TYPES));
        modes.addView(history, tabParams());
        modes.addView(log, tabParams());
        modes.addView(types, tabParams());
        content.addView(modes, fullWidth());
    }

    private void showEventHistory(String date) {
        addDeletedEventUndo();
        content.addView(sectionTitle("Events on " + humanDate(date)));
        Cursor cursor = database.getSpecialEventsForDate(date);
        try {
            if (!cursor.moveToFirst()) {
                content.addView(panelText("No events logged for " + date + "."), fullWidth());
            } else {
                do {
                    SpecialEvent event = new SpecialEvent(
                            cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("event_type_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("event_name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("note")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("time_ms")),
                            cursor.getString(cursor.getColumnIndexOrThrow("date"))
                    );
                    LinearLayout row = panel();
                    TextView name = text(event.name, 17, TEXT);
                    name.setTypeface(Typeface.DEFAULT_BOLD);
                    row.addView(name, fullWidth());
                    row.addView(text(
                            humanDate(event.date) + " | " + event.date + " | " +
                                    formatTimeForDate(event.timeMs, event.date),
                            14,
                            ACCENT
                    ), fullWidth());
                    if (event.note != null && !event.note.isEmpty()) {
                        row.addView(text(event.note, 14, MUTED), fullWidth());
                    }
                    LinearLayout actions = new LinearLayout(this);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    Button edit = button("Edit");
                    edit.setOnClickListener(view -> showEditEventDialog(event));
                    Button delete = button("Delete this occurrence");
                    delete.setOnClickListener(view -> confirmDeleteOccurrence(event));
                    actions.addView(edit, tabParams());
                    actions.addView(delete, tabParams());
                    row.addView(actions, fullWidth());
                    content.addView(row, fullWidth());
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }

        content.addView(sectionTitle("Event Frequency"));
        StringBuilder summary = new StringBuilder();
        appendEventFrequency(summary, date, date, "Selected date");
        appendEventFrequency(summary, dateOffset(date, -6), date, "Seven days ending here");
        content.addView(panelText(summary.toString()), fullWidth());

        content.addView(sectionTitle("Bulk Deletion"));
        Button deleteDay = button("Delete ALL events on " + date);
        deleteDay.setOnClickListener(view -> {
            int count = database.countSpecialEventsBetween(date, date);
            confirmBulkDelete(
                    "ALL EVENTS ON ONE DATE",
                    humanDate(date) + " (" + date + ")",
                    count,
                    () -> database.deleteSpecialEventsBetween(date, date),
                    date
            );
        });
        Button deleteRange = button("Delete events in a date range");
        deleteRange.setOnClickListener(view -> showDeleteDateRangeDialog(date));
        Button deleteName = button("Delete ALL occurrences by event name");
        deleteName.setOnClickListener(view -> showDeleteByNameDialog(date));
        Button deleteAll = button("Delete ALL special-event history");
        deleteAll.setOnClickListener(view -> confirmBulkDelete(
                "ALL SPECIAL-EVENT HISTORY",
                "Every logged special-event occurrence on every date",
                database.countAllSpecialEvents(),
                () -> database.deleteAllSpecialEvents(),
                date
        ));
        content.addView(deleteDay, fullWidth());
        content.addView(deleteRange, fullWidth());
        content.addView(deleteName, fullWidth());
        content.addView(deleteAll, fullWidth());
    }

    private void showEventLog(String date) {
        content.addView(sectionTitle("Log event on " + humanDate(date)));
        List<EventType> eventTypes = loadEventTypes(false);
        if (eventTypes.isEmpty()) {
            content.addView(panelText("No active event types."), fullWidth());
            Button manage = button("Open event types");
            manage.setOnClickListener(view -> showEvents(date, EVENT_MODE_TYPES));
            content.addView(manage, fullWidth());
            return;
        }

        Spinner eventSpinner = eventTypeSpinner(eventTypes, false);
        EditText timeInput = input("Occurrence time HH:mm");
        timeInput.setText(currentTime());
        EditText noteInput = input("Occurrence note optional");
        content.addView(eventSpinner, fullWidth());
        content.addView(timeInput, fullWidth());
        content.addView(timeControls(timeInput, true), fullWidth());
        content.addView(noteInput, fullWidth());

        Button save = button("Log one event on " + date);
        save.setOnClickListener(view -> {
            String time = normalizeTimeInput(timeInput.getText().toString().trim());
            if (time == null) {
                toast("Use time as HH:mm or TV time like 28");
                return;
            }
            EventType selected = eventTypes.get(Math.max(0, eventSpinner.getSelectedItemPosition()));
            database.addSpecialEvent(
                    selected.id,
                    selected.name,
                    noteInput.getText().toString().trim(),
                    parseDateTime(date, time),
                    date
            );
            toast("Logged one event on " + date);
            showEvents(date, EVENT_MODE_HISTORY);
        });
        content.addView(save, fullWidth());
    }

    private void showEventTypes(String date) {
        content.addView(sectionTitle("Register or Restore Event Type"));
        EditText nameInput = input("Event type name");
        EditText noteInput = input("Event type note optional");
        Button register = button("Save as active event type");
        register.setOnClickListener(view -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                toast("Event type name required");
                return;
            }
            database.addEventType(name, noteInput.getText().toString().trim());
            toast("Event type is active");
            showEvents(date, EVENT_MODE_TYPES);
        });
        content.addView(nameInput, fullWidth());
        content.addView(noteInput, fullWidth());
        content.addView(register, fullWidth());

        List<EventType> eventTypes = loadEventTypes(true);
        content.addView(sectionTitle("Event Types"));
        if (eventTypes.isEmpty()) {
            content.addView(panelText("No event types registered."), fullWidth());
            return;
        }
        for (EventType eventType : eventTypes) {
            int historyCount = database.countSpecialEventsByTypeId(eventType.id);
            LinearLayout row = panel();
            TextView name = text(eventType.name, 17, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name, fullWidth());
            row.addView(text(
                    (eventType.active ? "Active" : "Archived") + " | " + historyCount + " historical occurrences",
                    14,
                    eventType.active ? ACCENT : MUTED
            ), fullWidth());
            if (!eventType.note.isEmpty()) {
                row.addView(text(eventType.note, 14, MUTED), fullWidth());
            }
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = button("Edit type");
            edit.setOnClickListener(view -> showEditEventTypeDialog(eventType, date));
            Button action = button(eventType.active ? "Archive type" : "Restore type");
            if (eventType.active) {
                action.setOnClickListener(view -> confirmArchiveEventType(eventType, historyCount, date));
            } else {
                action.setOnClickListener(view -> {
                    database.restoreEventType(eventType.id);
                    toast("Event type restored; history was unchanged");
                    showEvents(date, EVENT_MODE_TYPES);
                });
            }
            actions.addView(edit, tabParams());
            actions.addView(action, tabParams());
            row.addView(actions, fullWidth());
            content.addView(row, fullWidth());
        }
    }

    private void showEditEventTypeDialog(EventType eventType, String date) {
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), dp(8), dp(18), dp(4));
        editor.addView(text(
                "Editing this type changes future selections. Existing event occurrences keep their saved names.",
                14,
                MUTED
        ), fullWidth());
        EditText nameInput = input("Event type name");
        nameInput.setText(eventType.name);
        EditText noteInput = input("Event type note optional");
        noteInput.setText(eventType.note);
        editor.addView(nameInput, fullWidth());
        editor.addView(noteInput, fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit event type")
                .setView(editor)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                toast("Event type name required");
                return;
            }
            boolean updated = database.updateEventType(eventType.id, name, noteInput.getText().toString().trim());
            if (!updated) {
                toast("Another event type already uses that name");
                return;
            }
            dialog.dismiss();
            toast("Event type updated; historical occurrences unchanged");
            showEvents(date, EVENT_MODE_TYPES);
        }));
        dialog.show();
    }

    private void confirmArchiveEventType(EventType eventType, int historyCount, String date) {
        new AlertDialog.Builder(this)
                .setTitle("Archive event type?")
                .setMessage(
                        "ARCHIVE MODE: EVENT TYPE ONLY\n\n" +
                                eventType.name + " will be removed from the logging picker.\n\n" +
                                historyCount + " historical occurrences will remain. No event history will be deleted."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Archive type", (dialog, which) -> {
                    database.archiveEventType(eventType.id);
                    toast("Event type archived; history kept");
                    showEvents(date, EVENT_MODE_TYPES);
                })
                .show();
    }

    private void showEditEventDialog(SpecialEvent event) {
        List<EventType> eventTypes = loadEventTypes(true);
        int selectedIndex = -1;
        for (int i = 0; i < eventTypes.size(); i++) {
            if (eventTypes.get(i).id == event.eventTypeId) {
                selectedIndex = i;
                break;
            }
        }
        if (selectedIndex < 0) {
            eventTypes.add(0, new EventType(event.eventTypeId, event.name, "Historical type", false));
            selectedIndex = 0;
        }

        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), dp(8), dp(18), dp(4));
        editor.addView(text(
                "Editing one occurrence\n" + event.name + " | " + humanDate(event.date) + " | " +
                        formatTimeForDate(event.timeMs, event.date),
                15,
                TEXT
        ), fullWidth());
        Spinner typeSpinner = eventTypeSpinner(eventTypes, true);
        typeSpinner.setSelection(selectedIndex);
        EditText dateInput = input("Event date yyyy-MM-dd");
        dateInput.setText(event.date);
        EditText timeInput = input("Event time HH:mm");
        timeInput.setText(formatTimeForDate(event.timeMs, event.date));
        EditText noteInput = input("Occurrence note optional");
        noteInput.setText(event.note == null ? "" : event.note);
        editor.addView(typeSpinner, fullWidth());
        editor.addView(dateInput, fullWidth());
        editor.addView(dateControls(dateInput), fullWidth());
        editor.addView(timeInput, fullWidth());
        editor.addView(timeControls(timeInput, true), fullWidth());
        editor.addView(noteInput, fullWidth());
        ScrollView editorScroll = new ScrollView(this);
        editorScroll.addView(editor);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit event occurrence")
                .setView(editorScroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String chosenDate = dateInput.getText().toString().trim();
            String chosenTime = normalizeTimeInput(timeInput.getText().toString().trim());
            if (!isDate(chosenDate) || chosenTime == null) {
                toast("Use yyyy-MM-dd and HH:mm, or TV time like 28");
                return;
            }
            EventType selected = eventTypes.get(Math.max(0, typeSpinner.getSelectedItemPosition()));
            database.updateSpecialEvent(
                    event.id,
                    selected.id,
                    selected.name,
                    noteInput.getText().toString().trim(),
                    parseDateTime(chosenDate, chosenTime),
                    chosenDate
            );
            dialog.dismiss();
            toast("Event occurrence updated");
            showEvents(chosenDate, EVENT_MODE_HISTORY);
        }));
        dialog.show();
    }

    private void confirmDeleteOccurrence(SpecialEvent event) {
        new AlertDialog.Builder(this)
                .setTitle("Delete one occurrence?")
                .setMessage(
                        "DELETE MODE: ONE OCCURRENCE ONLY\n\n" +
                                event.name + "\n" + humanDate(event.date) + " (" + event.date + ")\n" +
                                formatTimeForDate(event.timeMs, event.date) +
                                "\n\nOther occurrences and event types will not be changed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete one occurrence", (dialog, which) -> {
                    database.deleteSpecialEvent(event.id);
                    pendingDeletedEvent = event;
                    showEvents(event.date, EVENT_MODE_HISTORY);
                })
                .show();
    }

    private void addDeletedEventUndo() {
        if (pendingDeletedEvent == null) {
            return;
        }
        SpecialEvent deleted = pendingDeletedEvent;
        LinearLayout undoPanel = panel();
        undoPanel.addView(text(
                "Deleted one occurrence: " + deleted.name + " | " + deleted.date + " | " +
                        formatTimeForDate(deleted.timeMs, deleted.date),
                14,
                TEXT
        ), fullWidth());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button undo = button("Undo delete");
        undo.setOnClickListener(view -> {
            database.addSpecialEvent(
                    deleted.eventTypeId,
                    deleted.name,
                    deleted.note,
                    deleted.timeMs,
                    deleted.date
            );
            pendingDeletedEvent = null;
            toast("Occurrence restored");
            showEvents(deleted.date, EVENT_MODE_HISTORY);
        });
        Button dismiss = button("Dismiss");
        dismiss.setOnClickListener(view -> {
            pendingDeletedEvent = null;
            showEvents(deleted.date, EVENT_MODE_HISTORY);
        });
        actions.addView(undo, tabParams());
        actions.addView(dismiss, tabParams());
        undoPanel.addView(actions, fullWidth());
        content.addView(undoPanel, fullWidth());
    }

    private void showDeleteDateRangeDialog(String selectedDate) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(4), dp(18), dp(4));
        EditText startInput = input("Start date yyyy-MM-dd");
        startInput.setText(selectedDate);
        EditText endInput = input("End date yyyy-MM-dd");
        endInput.setText(selectedDate);
        fields.addView(startInput, fullWidth());
        fields.addView(dateControls(startInput), fullWidth());
        fields.addView(endInput, fullWidth());
        fields.addView(dateControls(endInput), fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose deletion date range")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Review deletion", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String start = startInput.getText().toString().trim();
            String end = endInput.getText().toString().trim();
            if (!isDate(start) || !isDate(end)) {
                toast("Use dates as yyyy-MM-dd");
                return;
            }
            if (start.compareTo(end) > 0) {
                toast("Start date must be before or equal to end date");
                return;
            }
            int count = database.countSpecialEventsBetween(start, end);
            dialog.dismiss();
            confirmBulkDelete(
                    "EVENTS IN DATE RANGE",
                    start + " through " + end + " inclusive",
                    count,
                    () -> database.deleteSpecialEventsBetween(start, end),
                    selectedDate
            );
        }));
        dialog.show();
    }

    private void showDeleteByNameDialog(String selectedDate) {
        List<String> names = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Cursor cursor = database.getDistinctSpecialEventNames();
        try {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow("event_name"));
                int count = cursor.getInt(cursor.getColumnIndexOrThrow("occurrence_count"));
                names.add(name);
                labels.add(name + " | " + count + " occurrences");
            }
        } finally {
            cursor.close();
        }
        if (names.isEmpty()) {
            toast("No special-event history to delete");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose event name")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String name = names.get(which);
                    confirmBulkDelete(
                            "ALL OCCURRENCES WITH ONE EVENT NAME",
                            "Every occurrence named \"" + name + "\" on every date",
                            database.countSpecialEventsByName(name),
                            () -> database.deleteSpecialEventsByName(name),
                            selectedDate
                    );
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmBulkDelete(String mode, String scope, int count, Runnable deleteAction, String returnDate) {
        if (count <= 0) {
            toast("No matching event occurrences to delete");
            return;
        }
        String details = "DELETE MODE: " + mode + "\n\nScope: " + scope + "\n\n" +
                "This will permanently delete " + count + " event occurrences. Event types will remain.";
        new AlertDialog.Builder(this)
                .setTitle("Bulk deletion: confirmation 1 of 2")
                .setMessage(details)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (firstDialog, firstWhich) ->
                        new AlertDialog.Builder(this)
                                .setTitle("Final confirmation 2 of 2")
                                .setMessage(details + "\n\nThis bulk deletion cannot be undone.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete " + count + " occurrences", (secondDialog, secondWhich) -> {
                                    pendingDeletedEvent = null;
                                    deleteAction.run();
                                    toast("Deleted " + count + " event occurrences");
                                    showEvents(returnDate, EVENT_MODE_HISTORY);
                                })
                                .show()
                )
                .show();
    }

    private Spinner eventTypeSpinner(List<EventType> eventTypes, boolean showStatus) {
        List<String> labels = new ArrayList<>();
        for (EventType eventType : eventTypes) {
            labels.add(eventType.name + (showStatus && !eventType.active ? " (archived)" : ""));
        }
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private void showAnalysis(String date) {
        String selectedDate = isDate(date) ? date : currentDate();
        content.removeAllViews();
        content.addView(sectionTitle("Analysis"));
        addImmediateDateNavigation(selectedDate, this::showAnalysis);

        String weekStart = dateOffset(selectedDate, -6);
        int waterToday = database.sumWaterBetween(selectedDate, selectedDate);
        int waterEntriesToday = database.countWaterBetween(selectedDate, selectedDate);
        int waterWeek = database.sumWaterBetween(weekStart, selectedDate);
        int doneToday = database.countCompletionsBetween(selectedDate, selectedDate);
        int doneWeek = database.countCompletionsBetween(weekStart, selectedDate);
        int workloadBlocks = database.countDiaryWorkloadBlocks(selectedDate);
        int eventsToday = database.countSpecialEventsBetween(selectedDate, selectedDate);
        int eventsWeek = database.countSpecialEventsBetween(weekStart, selectedDate);
        int sleepToday = database.getSleepMinutes(selectedDate);
        int sleepWeek = database.sumSleepMinutesBetween(weekStart, selectedDate);
        int sleepDays = database.countSleepRecordsBetween(weekStart, selectedDate);

        StringBuilder summary = new StringBuilder();
        summary.append("Date: ").append(selectedDate).append('\n');
        summary.append("Water on selected date: ").append(waterToday).append(" ml in ").append(waterEntriesToday).append(" checks\n");
        summary.append("Water 7 days: ").append(waterWeek).append(" ml, avg ").append(waterWeek / 7).append(" ml/day\n");
        summary.append("Successful to-dos on selected date: ").append(doneToday).append('\n');
        summary.append("Successful to-dos 7 days: ").append(doneWeek).append('\n');
        summary.append("Sleep on selected date: ").append(sleepToday > 0 ? formatDuration(sleepToday) : "not recorded").append('\n');
        summary.append("Sleep 7 days: ");
        if (sleepDays > 0) {
            summary.append(formatDuration(sleepWeek / sleepDays)).append(" avg across ").append(sleepDays).append(" records\n");
        } else {
            summary.append("not recorded\n");
        }
        summary.append("Special events on selected date: ").append(eventsToday).append('\n');
        summary.append("Special events 7 days: ").append(eventsWeek).append('\n');
        summary.append("Workload blocks on selected date: ").append(workloadBlocks).append("/12\n");
        addDiarySummary(selectedDate, summary);
        summary.append('\n');
        appendEventFrequency(summary, selectedDate, selectedDate, "Event frequency on selected date");
        appendEventFrequency(summary, weekStart, selectedDate, "Event frequency 7 days");
        content.addView(panelText(summary.toString()), fullWidth());
    }

    private void showExport() {
        content.removeAllViews();
        content.setBackgroundColor(BG);
        content.addView(sectionTitle("Backup"));
        content.addView(text(
                "JSON backup includes every database row and Base64 copies of attached completion images.",
                15,
                MUTED
        ), fullWidth());
        Button export = button("Export complete JSON backup");
        export.setOnClickListener(view -> exportData());
        Button importBackup = button("Import JSON backup");
        importBackup.setOnClickListener(view -> chooseImportBackup());
        content.addView(export, fullWidth());
        content.addView(importBackup, fullWidth());
    }

    private void chooseImportBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain"});
        startActivityForResult(intent, REQUEST_IMPORT_JSON);
    }

    private void showDebug(boolean jsonMode) {
        content.removeAllViews();
        content.setBackgroundColor(BG);
        content.addView(sectionTitle("Database Debug"));
        content.addView(panelText("READ-ONLY VIEW | tododiary.db | schema version 5"), fullWidth());

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button rows = button("Plain text");
        Button json = button("Formatted JSON");
        rows.setEnabled(jsonMode);
        json.setEnabled(!jsonMode);
        rows.setOnClickListener(view -> showDebug(false));
        json.setOnClickListener(view -> showDebug(true));
        modes.addView(rows, tabParams());
        modes.addView(json, tabParams());
        content.addView(modes, fullWidth());

        Button refresh = button("Refresh database view");
        refresh.setOnClickListener(view -> showDebug(jsonMode));
        content.addView(refresh, fullWidth());

        TextView outputView = panelText("Loading database text...");
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextIsSelectable(true);
        content.addView(outputView, fullWidth());
        new Thread(() -> {
            String rendered;
            try {
                JSONObject snapshot = database.debugJson();
                rendered = jsonMode ? snapshot.toString(2) : formatDebugText(snapshot);
            } catch (Exception e) {
                rendered = "Cannot read database snapshot: " + e.getMessage();
            }
            String finalRendered = rendered;
            runOnUiThread(() -> {
                if (outputView.getParent() != null) {
                    outputView.setText(finalRendered);
                }
            });
        }, "TodoDiary-DebugReader").start();
    }

    private String formatDebugText(JSONObject snapshot) throws JSONException {
        StringBuilder output = new StringBuilder();
        for (String table : DEBUG_TABLES) {
            JSONArray rows = snapshot.optJSONArray(table);
            int rowCount = rows == null ? 0 : rows.length();
            if (output.length() > 0) {
                output.append("\n\n");
            }
            output.append("=== ").append(table).append(" (").append(rowCount).append(" rows) ===\n");
            if (rowCount == 0) {
                output.append("No rows");
                continue;
            }
            for (int i = 0; i < rowCount; i++) {
                output.append("\n#").append(i + 1).append('\n');
                String row = formatDebugRow(rows.getJSONObject(i));
                output.append("  ").append(row.replace("\n", "\n  "));
            }
        }
        return output.toString();
    }

    private String formatDebugRow(JSONObject row) {
        StringBuilder result = new StringBuilder();
        JSONArray names = row.names();
        if (names == null) {
            return "{}";
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            if (i > 0) {
                result.append('\n');
            }
            result.append(name).append(": ").append(String.valueOf(row.opt(name)));
        }
        return result.toString();
    }

    private void showGuide() {
        content.removeAllViews();
        content.setBackgroundColor(BG);
        content.addView(sectionTitle("Guide"));
        String guide = ""
                + "To-do\n"
                + "Select any date, then claim a completion for that date. Completion records can be edited, moved, or deleted. A schedule can optionally allow one image on each daily completion; attached images can be added, replaced, or removed.\n\n"
                + "Diary\n"
                + "Previous, Select date, Today, and Next immediately load that diary date. Save updates the loaded entry. Existing diary entries can be deleted for the selected date.\n\n"
                + "Sleep\n"
                + "Date selection immediately loads the sleep record. Save updates it, and Delete removes only the selected date. Analysis calculates duration and seven-day averages.\n\n"
                + "Water\n"
                + "Select any date and add a timestamped water entry. Every past entry can be edited for amount, date, and time, or deleted individually.\n\n"
                + "Events\n"
                + "Use History, Log event, and Event types as separate views. Archiving a type removes it from future logging but keeps every historical occurrence. Deleting one occurrence never deletes other occurrences. Bulk event-history deletion shows its scope and requires two confirmations.\n\n"
                + "Analysis\n"
                + "Use Analysis to check water totals, successful to-dos, sleep duration, workload blocks, and special-event frequency for the selected day and last 7 days.\n\n"
                + "Backup\n"
                + "Export writes a complete local JSON backup, including Base64 copies of completion images. Import validates a selected backup and requires two confirmations before replacing local data.\n\n"
                + "Debug\n"
                + "Debug renders the full database in one selectable plain-text view or one formatted JSON view. Refresh reloads the snapshot.";
        content.addView(panelText(guide), fullWidth());
    }

    private List<EventType> loadEventTypes(boolean includeArchived) {
        List<EventType> eventTypes = new ArrayList<>();
        Cursor cursor = includeArchived ? database.getAllEventTypes() : database.getEventTypes();
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
                boolean active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1;
                eventTypes.add(new EventType(id, name, note == null ? "" : note, active));
            }
        } finally {
            cursor.close();
        }
        return eventTypes;
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

    private boolean loadDiaryInto(String date, EditText wake, EditText sleep, EditText nap, EditText lunch, EditText notes, EditText[] blocks) {
        Cursor cursor = database.getDiary(date);
        try {
            if (!cursor.moveToFirst()) {
                return false;
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
            return true;
        } catch (JSONException ignored) {
            return true;
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
        toast("Building backup...");
        new Thread(() -> {
            try {
                JSONObject json = database.exportJson();
                String fileName = "tododiary-backup-" + exportFormat.format(new Date()) + ".json";
                Uri uri = writeExport(fileName, json.toString(2));
                runOnUiThread(() -> toast("Backup exported: " + uri));
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> toast("Export failed: " + e.getMessage()));
            }
        }, "TodoDiary-Export").start();
    }

    private void readAndConfirmImport(Uri uri) {
        toast("Reading backup...");
        new Thread(() -> {
            try {
                JSONObject backup = readBackupJson(uri);
                runOnUiThread(() -> confirmImportBackup(backup));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Import file rejected: " + e.getMessage()));
            }
        }, "TodoDiary-ImportReader").start();
    }

    private JSONObject readBackupJson(Uri uri) throws IOException, JSONException {
        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Cannot open selected file");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BACKUP_BYTES) {
                    throw new IOException("Backup exceeds 150 MB");
                }
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private void confirmImportBackup(JSONObject backup) {
        String[] tables = {
                "todos", "todo_completions", "diary", "water",
                "event_types", "special_events", "sleep_records"
        };
        StringBuilder summary = new StringBuilder("IMPORT MODE: REPLACE ALL LOCAL DATA\n\n");
        int imageCount = 0;
        for (String table : tables) {
            JSONArray rows = backup.optJSONArray(table);
            if (rows == null) {
                toast("Import file is missing table: " + table);
                return;
            }
            summary.append(table).append(": ").append(rows.length()).append(" rows\n");
            if ("todo_completions".equals(table)) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) {
                        toast("Import file has an invalid completion row");
                        return;
                    }
                    if (!row.optString("image_base64", "").isEmpty()) {
                        imageCount++;
                    }
                }
            }
        }
        summary.append("attached images: ").append(imageCount)
                .append("\n\nCurrent local data will be replaced only after final confirmation.");
        String details = summary.toString();
        new AlertDialog.Builder(this)
                .setTitle("Import backup: confirmation 1 of 2")
                .setMessage(details)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (firstDialog, firstWhich) ->
                        new AlertDialog.Builder(this)
                                .setTitle("Final import confirmation 2 of 2")
                                .setMessage(details + "\n\nThis replacement cannot be undone unless you exported the current data first.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Replace all local data", (secondDialog, secondWhich) -> performImport(backup))
                                .show()
                )
                .show();
    }

    private void performImport(JSONObject backup) {
        toast("Importing backup...");
        new Thread(() -> {
            try {
                database.importJson(backup);
                runOnUiThread(() -> {
                    TodoScheduler.scheduleAll(this);
                    pendingImageCompletion = null;
                    pendingImageReturnDate = null;
                    toast("Backup import complete");
                    showExport();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Import failed; existing data kept: " + e.getMessage()));
            }
        }, "TodoDiary-Import").start();
    }

    private void saveSelectedCompletionImage(Uri uri) {
        TodoCompletion completion = pendingImageCompletion;
        String returnDate = pendingImageReturnDate;
        pendingImageCompletion = null;
        pendingImageReturnDate = null;
        if (completion == null) {
            return;
        }
        toast("Saving completion image...");
        new Thread(() -> {
            File file = null;
            try {
                String mime = getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) {
                    throw new IOException("Selected file is not an image");
                }
                File directory = new File(getFilesDir(), "todo-images");
                if (!directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("Cannot create image storage");
                }
                file = new File(directory, UUID.randomUUID() + extensionForMime(mime));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(file)) {
                    if (input == null) {
                        throw new IOException("Cannot open selected image");
                    }
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) {
                            throw new IOException("Image exceeds 20 MB");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (!database.setTodoCompletionImage(completion.id, file.getAbsolutePath(), mime)) {
                    throw new IOException("This completion does not allow an image");
                }
                runOnUiThread(() -> {
                    toast("Completion image saved");
                    showTodos(returnDate == null ? completion.date : returnDate);
                });
            } catch (Exception e) {
                if (file != null) {
                    file.delete();
                }
                runOnUiThread(() -> toast("Image save failed: " + e.getMessage()));
            }
        }, "TodoDiary-ImageSave").start();
    }

    private String extensionForMime(String mime) {
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("image/gif".equalsIgnoreCase(mime)) return ".gif";
        if ("image/heic".equalsIgnoreCase(mime) || "image/heif".equalsIgnoreCase(mime)) return ".heic";
        return ".jpg";
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_COMPLETION_IMAGE) {
                pendingImageCompletion = null;
                pendingImageReturnDate = null;
            }
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_JSON) {
            readAndConfirmImport(uri);
        } else if (requestCode == REQUEST_COMPLETION_IMAGE) {
            saveSelectedCompletionImage(uri);
        }
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

    private void addImmediateDateNavigation(String date, DateSelectionListener listener) {
        applyDateBackground(date);
        String today = date.equals(currentDate()) ? "Today\n" : "";
        content.addView(panelText(today + humanDate(date) + "\n" + date), fullWidth());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("Previous");
        previous.setOnClickListener(view -> listener.onDateSelected(dateOffset(date, -1)));
        Button select = button("Select date");
        select.setOnClickListener(view -> showDatePicker(date, listener));
        Button todayButton = button("Today");
        todayButton.setOnClickListener(view -> listener.onDateSelected(currentDate()));
        Button next = button("Next");
        next.setOnClickListener(view -> listener.onDateSelected(dateOffset(date, 1)));
        navigation.addView(previous, tabParams());
        navigation.addView(select, tabParams());
        navigation.addView(todayButton, tabParams());
        navigation.addView(next, tabParams());
        content.addView(navigation, fullWidth());
    }

    private void applyDateBackground(String date) {
        content.setBackgroundColor(date.equals(currentDate()) ? TODAY_BG : BG);
    }

    private LinearLayout dateControls(EditText dateInput) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = button("Select date");
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

    private void showDatePicker(String date, DateSelectionListener listener) {
        Calendar calendar = calendarFromDate(date);
        new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    listener.onDateSelected(dateFormat.format(selected.getTime()));
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

    private String humanDate(String date) {
        try {
            Date parsed = dateFormat.parse(date);
            return parsed == null ? date : displayDateFormat.format(parsed);
        } catch (ParseException ignored) {
            return date;
        }
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
        private final boolean active;

        private EventType(long id, String name, String note, boolean active) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.active = active;
        }
    }

    private static final class TodoDefinition {
        private final long id;
        private final String title;
        private final String notes;
        private final String time;
        private final String weekdays;
        private final boolean imageEnabled;

        private TodoDefinition(long id, String title, String notes, String time, String weekdays, boolean imageEnabled) {
            this.id = id;
            this.title = title;
            this.notes = notes;
            this.time = time;
            this.weekdays = weekdays;
            this.imageEnabled = imageEnabled;
        }
    }

    private static final class TodoCompletion {
        private final long id;
        private final long todoId;
        private final String title;
        private final long completedMs;
        private final String date;
        private final boolean imageAllowed;
        private final String imagePath;
        private final String imageMime;

        private TodoCompletion(
                long id,
                long todoId,
                String title,
                long completedMs,
                String date,
                boolean imageAllowed,
                String imagePath,
                String imageMime
        ) {
            this.id = id;
            this.todoId = todoId;
            this.title = title;
            this.completedMs = completedMs;
            this.date = date;
            this.imageAllowed = imageAllowed;
            this.imagePath = imagePath;
            this.imageMime = imageMime;
        }
    }

    private static final class WaterEntry {
        private final long id;
        private final int amountMl;
        private final long timeMs;
        private final String date;

        private WaterEntry(long id, int amountMl, long timeMs, String date) {
            this.id = id;
            this.amountMl = amountMl;
            this.timeMs = timeMs;
            this.date = date;
        }
    }

    private static final class SpecialEvent {
        private final long id;
        private final long eventTypeId;
        private final String name;
        private final String note;
        private final long timeMs;
        private final String date;

        private SpecialEvent(long id, long eventTypeId, String name, String note, long timeMs, String date) {
            this.id = id;
            this.eventTypeId = eventTypeId;
            this.name = name;
            this.note = note;
            this.timeMs = timeMs;
            this.date = date;
        }
    }

    private interface DateSelectionListener {
        void onDateSelected(String date);
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
