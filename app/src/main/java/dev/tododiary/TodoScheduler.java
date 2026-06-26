package dev.tododiary;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class TodoScheduler {
    public static final String CHANNEL_ID = "todo_reminders";
    public static final String EXTRA_TODO_ID = "todo_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_NOTES = "notes";
    public static final String EXTRA_TIME = "time";

    private TodoScheduler() {
    }

    public static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "To-do reminders",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        manager.createNotificationChannel(channel);
    }

    public static void scheduleAll(Context context) {
        createChannel(context);
        AppDatabase database = new AppDatabase(context);
        Cursor cursor = database.getActiveTodosForScheduler();
        try {
            while (cursor.moveToNext()) {
                scheduleTodo(
                        context,
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        cursor.getString(cursor.getColumnIndexOrThrow("notes")),
                        cursor.getString(cursor.getColumnIndexOrThrow("time")),
                        cursor.getString(cursor.getColumnIndexOrThrow("weekdays"))
                );
            }
        } finally {
            cursor.close();
            database.close();
        }
    }

    public static void scheduleTodo(Context context, long id, String title, String notes, String time, String weekdays) {
        long triggerAt = nextTriggerMillis(time, weekdays);
        if (triggerAt <= 0L) {
            cancelTodo(context, id);
            return;
        }

        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }

        Intent intent = new Intent(context, ReminderReceiver.class)
                .putExtra(EXTRA_TODO_ID, id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NOTES, notes == null ? "" : notes)
                .putExtra(EXTRA_TIME, time);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                (int) id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
    }

    public static void cancelTodo(Context context, long id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                (int) id,
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pendingIntent != null) {
            manager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    private static long nextTriggerMillis(String time, String weekdays) {
        TimeValue timeValue = parseFlexibleTime(time);
        if (timeValue == null) {
            return 0L;
        }

        Set<Integer> allowedDays = parseWeekdays(weekdays);
        if (allowedDays.isEmpty()) {
            return 0L;
        }

        Calendar now = Calendar.getInstance();
        long nowMs = now.getTimeInMillis();
        long best = 0L;
        for (int addDays = 0; addDays <= 14; addDays++) {
            Calendar broadcastDay = (Calendar) now.clone();
            broadcastDay.add(Calendar.DAY_OF_YEAR, addDays);
            broadcastDay.set(Calendar.HOUR_OF_DAY, 0);
            broadcastDay.set(Calendar.MINUTE, 0);
            broadcastDay.set(Calendar.SECOND, 0);
            broadcastDay.set(Calendar.MILLISECOND, 0);
            if (!allowedDays.contains(broadcastDay.get(Calendar.DAY_OF_WEEK))) {
                continue;
            }
            Calendar candidate = (Calendar) broadcastDay.clone();
            candidate.add(Calendar.DAY_OF_YEAR, timeValue.hour / 24);
            candidate.set(Calendar.HOUR_OF_DAY, timeValue.hour % 24);
            candidate.set(Calendar.MINUTE, timeValue.minute);
            long candidateMs = candidate.getTimeInMillis();
            if (candidateMs > nowMs && (best == 0L || candidateMs < best)) {
                best = candidateMs;
            }
        }
        return best;
    }

    private static TimeValue parseFlexibleTime(String value) {
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

    private static Set<Integer> parseWeekdays(String weekdays) {
        Set<Integer> days = new HashSet<>();
        if (weekdays == null || weekdays.trim().isEmpty()) {
            return days;
        }
        String[] parts = weekdays.split(",");
        for (String part : parts) {
            try {
                days.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return days;
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
