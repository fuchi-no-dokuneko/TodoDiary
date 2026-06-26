package dev.tododiary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "tododiary.db";
    private static final int DB_VERSION = 3;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE todos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "notes TEXT," +
                "time TEXT NOT NULL," +
                "weekdays TEXT NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1," +
                "created_ms INTEGER NOT NULL," +
                "updated_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE todo_completions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "todo_id INTEGER," +
                "title TEXT NOT NULL," +
                "completed_ms INTEGER NOT NULL," +
                "date TEXT NOT NULL)");
        createTodoCompletionIndex(db);
        db.execSQL("CREATE TABLE diary (" +
                "date TEXT PRIMARY KEY," +
                "wake TEXT NOT NULL," +
                "sleep TEXT NOT NULL," +
                "nap TEXT," +
                "lunch TEXT NOT NULL," +
                "workload TEXT NOT NULL," +
                "notes TEXT," +
                "updated_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE water (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount_ml INTEGER NOT NULL," +
                "time_ms INTEGER NOT NULL," +
                "date TEXT NOT NULL)");
        createSpecialEventTables(db);
        createSleepTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createSpecialEventTables(db);
        }
        if (oldVersion < 3) {
            db.execSQL("DELETE FROM todo_completions WHERE id NOT IN (" +
                    "SELECT MIN(id) FROM todo_completions GROUP BY todo_id, date)");
            createTodoCompletionIndex(db);
            createSleepTable(db);
        }
    }

    private void createTodoCompletionIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_todo_completion_once_per_day " +
                "ON todo_completions(todo_id, date)");
    }

    private void createSpecialEventTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS event_types (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL COLLATE NOCASE UNIQUE," +
                "note TEXT," +
                "created_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS special_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "event_type_id INTEGER," +
                "event_name TEXT NOT NULL," +
                "note TEXT," +
                "time_ms INTEGER NOT NULL," +
                "date TEXT NOT NULL)");
    }

    private void createSleepTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_records (" +
                "date TEXT PRIMARY KEY," +
                "wake_time TEXT NOT NULL," +
                "last_sleep_time TEXT NOT NULL," +
                "sleep_minutes INTEGER NOT NULL," +
                "note TEXT," +
                "updated_ms INTEGER NOT NULL)");
    }

    public long addTodo(String title, String notes, String time, String weekdays) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("notes", notes);
        values.put("time", time);
        values.put("weekdays", weekdays);
        values.put("active", 1);
        values.put("created_ms", now);
        values.put("updated_ms", now);
        return getWritableDatabase().insertOrThrow("todos", null, values);
    }

    public void deleteTodo(long id) {
        getWritableDatabase().delete("todos", "id=?", new String[]{String.valueOf(id)});
    }

    public boolean markTodoDone(long id, long completedMs, String date) {
        if (hasTodoCompletion(id, date)) {
            return false;
        }
        Cursor cursor = getReadableDatabase().query(
                "todos",
                new String[]{"title"},
                "id=?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null
        );
        try {
            if (!cursor.moveToFirst()) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("todo_id", id);
            values.put("title", cursor.getString(0));
            values.put("completed_ms", completedMs);
            values.put("date", date);
            return getWritableDatabase().insertWithOnConflict(
                    "todo_completions",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            ) >= 0L;
        } finally {
            cursor.close();
        }
    }

    public boolean hasTodoCompletion(long id, String date) {
        Cursor cursor = getReadableDatabase().query(
                "todo_completions",
                new String[]{"id"},
                "todo_id=? AND date=?",
                new String[]{String.valueOf(id), date},
                null,
                null,
                null,
                "1"
        );
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    public Cursor getTodos() {
        return getReadableDatabase().query(
                "todos",
                null,
                null,
                null,
                null,
                null,
                "time ASC, id DESC"
        );
    }

    public Cursor getActiveTodosForScheduler() {
        return getReadableDatabase().query(
                "todos",
                new String[]{"id", "title", "notes", "time", "weekdays"},
                "active=1",
                null,
                null,
                null,
                "id ASC"
        );
    }

    public Cursor getCompletionsForDate(String date) {
        return getReadableDatabase().query(
                "todo_completions",
                null,
                "date=?",
                new String[]{date},
                null,
                null,
                "completed_ms ASC"
        );
    }

    public int countCompletionsBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COUNT(*) FROM todo_completions WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public void saveDiary(String date, String wake, String sleep, String nap, String lunch, String workload, String notes) {
        ContentValues values = new ContentValues();
        values.put("date", date);
        values.put("wake", wake);
        values.put("sleep", sleep);
        values.put("nap", nap);
        values.put("lunch", lunch);
        values.put("workload", workload);
        values.put("notes", notes);
        values.put("updated_ms", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("diary", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Cursor getDiary(String date) {
        return getReadableDatabase().query(
                "diary",
                null,
                "date=?",
                new String[]{date},
                null,
                null,
                null
        );
    }

    public int countDiaryWorkloadBlocks(String date) {
        Cursor cursor = getDiary(date);
        try {
            if (!cursor.moveToFirst()) {
                return 0;
            }
            String workload = cursor.getString(cursor.getColumnIndexOrThrow("workload"));
            JSONArray array = new JSONArray(workload);
            int count = 0;
            for (int i = 0; i < array.length(); i++) {
                if (!array.optString(i, "").trim().isEmpty()) {
                    count++;
                }
            }
            return count;
        } catch (JSONException ignored) {
            return 0;
        } finally {
            cursor.close();
        }
    }

    public void saveSleepRecord(String date, String wakeTime, String lastSleepTime, int sleepMinutes, String note) {
        ContentValues values = new ContentValues();
        values.put("date", date);
        values.put("wake_time", wakeTime);
        values.put("last_sleep_time", lastSleepTime);
        values.put("sleep_minutes", sleepMinutes);
        values.put("note", note);
        values.put("updated_ms", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("sleep_records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Cursor getSleepRecord(String date) {
        return getReadableDatabase().query(
                "sleep_records",
                null,
                "date=?",
                new String[]{date},
                null,
                null,
                null
        );
    }

    public int getSleepMinutes(String date) {
        return intQuery(
                "SELECT COALESCE(MAX(sleep_minutes),0) FROM sleep_records WHERE date=?",
                new String[]{date}
        );
    }

    public int sumSleepMinutesBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COALESCE(SUM(sleep_minutes),0) FROM sleep_records WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public int countSleepRecordsBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COUNT(*) FROM sleep_records WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public long addWater(String date, long timeMs) {
        ContentValues values = new ContentValues();
        values.put("amount_ml", 250);
        values.put("time_ms", timeMs);
        values.put("date", date);
        return getWritableDatabase().insertOrThrow("water", null, values);
    }

    public void deleteWater(long id) {
        getWritableDatabase().delete("water", "id=?", new String[]{String.valueOf(id)});
    }

    public Cursor getWaterForDate(String date) {
        return getReadableDatabase().query(
                "water",
                null,
                "date=?",
                new String[]{date},
                null,
                null,
                "time_ms ASC"
        );
    }

    public int sumWaterBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COALESCE(SUM(amount_ml),0) FROM water WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public int countWaterBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COUNT(*) FROM water WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public long addEventType(String name, String note) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("note", note);
        values.put("created_ms", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "event_types",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    public void deleteEventType(long id) {
        getWritableDatabase().delete("event_types", "id=?", new String[]{String.valueOf(id)});
    }

    public Cursor getEventTypes() {
        return getReadableDatabase().query(
                "event_types",
                null,
                null,
                null,
                null,
                null,
                "name COLLATE NOCASE ASC"
        );
    }

    public long addSpecialEvent(long eventTypeId, String eventName, String note, long timeMs, String date) {
        ContentValues values = new ContentValues();
        values.put("event_type_id", eventTypeId);
        values.put("event_name", eventName);
        values.put("note", note);
        values.put("time_ms", timeMs);
        values.put("date", date);
        return getWritableDatabase().insertOrThrow("special_events", null, values);
    }

    public void deleteSpecialEvent(long id) {
        getWritableDatabase().delete("special_events", "id=?", new String[]{String.valueOf(id)});
    }

    public Cursor getSpecialEventsForDate(String date) {
        return getReadableDatabase().query(
                "special_events",
                null,
                "date=?",
                new String[]{date},
                null,
                null,
                "time_ms ASC"
        );
    }

    public int countSpecialEventsBetween(String startDate, String endDate) {
        return intQuery(
                "SELECT COUNT(*) FROM special_events WHERE date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public Cursor getSpecialEventFrequency(String startDate, String endDate) {
        return getReadableDatabase().rawQuery(
                "SELECT event_name, COUNT(*) AS frequency " +
                        "FROM special_events WHERE date>=? AND date<=? " +
                        "GROUP BY event_name ORDER BY frequency DESC, event_name COLLATE NOCASE ASC",
                new String[]{startDate, endDate}
        );
    }

    public JSONObject exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("exported_ms", System.currentTimeMillis());
        root.put("todos", tableToJson("todos", "id ASC"));
        root.put("todo_completions", tableToJson("todo_completions", "completed_ms ASC"));
        root.put("diary", tableToJson("diary", "date ASC"));
        root.put("water", tableToJson("water", "time_ms ASC"));
        root.put("event_types", tableToJson("event_types", "name COLLATE NOCASE ASC"));
        root.put("special_events", tableToJson("special_events", "time_ms ASC"));
        root.put("sleep_records", tableToJson("sleep_records", "date ASC"));
        return root;
    }

    private int intQuery(String sql, String[] args) {
        Cursor cursor = getReadableDatabase().rawQuery(sql, args);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    private JSONArray tableToJson(String table, String orderBy) throws JSONException {
        JSONArray array = new JSONArray();
        Cursor cursor = getReadableDatabase().query(table, null, null, null, null, null, orderBy);
        try {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String name = cursor.getColumnName(i);
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.put(name, cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row.put(name, cursor.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_NULL:
                            row.put(name, JSONObject.NULL);
                            break;
                        default:
                            row.put(name, cursor.getString(i));
                            break;
                    }
                }
                array.put(row);
            }
        } finally {
            cursor.close();
        }
        return array;
    }
}
