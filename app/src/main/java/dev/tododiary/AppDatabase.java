package dev.tododiary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "tododiary.db";
    private static final int DB_VERSION = 5;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_BACKUP_IMAGE_BYTES = 100 * 1024 * 1024;
    private static final String[] BACKUP_TABLES = {
            "todos", "todo_completions", "diary", "water",
            "event_types", "special_events", "sleep_records"
    };

    private final Context context;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
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
                "image_enabled INTEGER NOT NULL DEFAULT 0," +
                "created_ms INTEGER NOT NULL," +
                "updated_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE todo_completions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "todo_id INTEGER," +
                "title TEXT NOT NULL," +
                "completed_ms INTEGER NOT NULL," +
                "date TEXT NOT NULL," +
                "image_allowed INTEGER NOT NULL DEFAULT 0," +
                "image_path TEXT," +
                "image_mime TEXT)");
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
        if (oldVersion >= 2 && oldVersion < 4) {
            db.execSQL("ALTER TABLE event_types ADD COLUMN active INTEGER NOT NULL DEFAULT 1");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE todos ADD COLUMN image_enabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE todo_completions ADD COLUMN image_allowed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE todo_completions ADD COLUMN image_path TEXT");
            db.execSQL("ALTER TABLE todo_completions ADD COLUMN image_mime TEXT");
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
                "created_ms INTEGER NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1)");
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

    public long addTodo(String title, String notes, String time, String weekdays, boolean imageEnabled) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("notes", notes);
        values.put("time", time);
        values.put("weekdays", weekdays);
        values.put("active", 1);
        values.put("image_enabled", imageEnabled ? 1 : 0);
        values.put("created_ms", now);
        values.put("updated_ms", now);
        return getWritableDatabase().insertOrThrow("todos", null, values);
    }

    public int updateTodo(long id, String title, String notes, String time, String weekdays, boolean imageEnabled) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("notes", notes);
        values.put("time", time);
        values.put("weekdays", weekdays);
        values.put("image_enabled", imageEnabled ? 1 : 0);
        values.put("updated_ms", System.currentTimeMillis());
        return getWritableDatabase().update("todos", values, "id=?", new String[]{String.valueOf(id)});
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
                new String[]{"title", "image_enabled"},
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
            values.put("image_allowed", cursor.getInt(1));
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

    public boolean updateTodoCompletion(long id, String title, long completedMs, String date) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("completed_ms", completedMs);
        values.put("date", date);
        return getWritableDatabase().updateWithOnConflict(
                "todo_completions",
                values,
                "id=?",
                new String[]{String.valueOf(id)},
                SQLiteDatabase.CONFLICT_IGNORE
        ) > 0;
    }

    public void deleteTodoCompletion(long id) {
        String imagePath = getTodoCompletionImagePath(id);
        getWritableDatabase().delete("todo_completions", "id=?", new String[]{String.valueOf(id)});
        deleteFile(imagePath);
    }

    public boolean setTodoCompletionImage(long id, String imagePath, String imageMime) {
        String oldImagePath = getTodoCompletionImagePath(id);
        ContentValues values = new ContentValues();
        values.put("image_path", imagePath);
        values.put("image_mime", imageMime);
        int updated = getWritableDatabase().update(
                "todo_completions",
                values,
                "id=? AND image_allowed=1",
                new String[]{String.valueOf(id)}
        );
        if (updated > 0) {
            if (oldImagePath != null && !oldImagePath.equals(imagePath)) {
                deleteFile(oldImagePath);
            }
            return true;
        }
        deleteFile(imagePath);
        return false;
    }

    public void removeTodoCompletionImage(long id) {
        String imagePath = getTodoCompletionImagePath(id);
        ContentValues values = new ContentValues();
        values.putNull("image_path");
        values.putNull("image_mime");
        getWritableDatabase().update("todo_completions", values, "id=?", new String[]{String.valueOf(id)});
        deleteFile(imagePath);
    }

    private String getTodoCompletionImagePath(long id) {
        Cursor cursor = getReadableDatabase().query(
                "todo_completions",
                new String[]{"image_path"},
                "id=?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null,
                "1"
        );
        try {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        } finally {
            cursor.close();
        }
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

    public void deleteDiary(String date) {
        getWritableDatabase().delete("diary", "date=?", new String[]{date});
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

    public void deleteSleepRecord(String date) {
        getWritableDatabase().delete("sleep_records", "date=?", new String[]{date});
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

    public int updateWater(long id, int amountMl, String date, long timeMs) {
        ContentValues values = new ContentValues();
        values.put("amount_ml", amountMl);
        values.put("time_ms", timeMs);
        values.put("date", date);
        return getWritableDatabase().update("water", values, "id=?", new String[]{String.valueOf(id)});
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
        SQLiteDatabase db = getWritableDatabase();
        Cursor existing = db.query(
                "event_types",
                new String[]{"id"},
                "name=? COLLATE NOCASE",
                new String[]{name},
                null,
                null,
                null,
                "1"
        );
        try {
            if (existing.moveToFirst()) {
                long id = existing.getLong(0);
                ContentValues restore = new ContentValues();
                restore.put("note", note);
                restore.put("active", 1);
                db.update("event_types", restore, "id=?", new String[]{String.valueOf(id)});
                return id;
            }
        } finally {
            existing.close();
        }

        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("note", note);
        values.put("created_ms", System.currentTimeMillis());
        values.put("active", 1);
        return db.insertWithOnConflict(
                "event_types",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    public void archiveEventType(long id) {
        setEventTypeActive(id, false);
    }

    public boolean updateEventType(long id, String name, String note) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("note", note);
        return getWritableDatabase().updateWithOnConflict(
                "event_types",
                values,
                "id=?",
                new String[]{String.valueOf(id)},
                SQLiteDatabase.CONFLICT_IGNORE
        ) > 0;
    }

    public void restoreEventType(long id) {
        setEventTypeActive(id, true);
    }

    private void setEventTypeActive(long id, boolean active) {
        ContentValues values = new ContentValues();
        values.put("active", active ? 1 : 0);
        getWritableDatabase().update("event_types", values, "id=?", new String[]{String.valueOf(id)});
    }

    public Cursor getEventTypes() {
        return getReadableDatabase().query(
                "event_types",
                null,
                "active=1",
                null,
                null,
                null,
                "name COLLATE NOCASE ASC"
        );
    }

    public Cursor getAllEventTypes() {
        return getReadableDatabase().query(
                "event_types",
                null,
                null,
                null,
                null,
                null,
                "active DESC, name COLLATE NOCASE ASC"
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

    public int updateSpecialEvent(long id, long eventTypeId, String eventName, String note, long timeMs, String date) {
        ContentValues values = new ContentValues();
        values.put("event_type_id", eventTypeId);
        values.put("event_name", eventName);
        values.put("note", note);
        values.put("time_ms", timeMs);
        values.put("date", date);
        return getWritableDatabase().update(
                "special_events",
                values,
                "id=?",
                new String[]{String.valueOf(id)}
        );
    }

    public void deleteSpecialEvent(long id) {
        getWritableDatabase().delete("special_events", "id=?", new String[]{String.valueOf(id)});
    }

    public int deleteSpecialEventsBetween(String startDate, String endDate) {
        return getWritableDatabase().delete(
                "special_events",
                "date>=? AND date<=?",
                new String[]{startDate, endDate}
        );
    }

    public int deleteSpecialEventsByName(String eventName) {
        return getWritableDatabase().delete(
                "special_events",
                "event_name=? COLLATE NOCASE",
                new String[]{eventName}
        );
    }

    public int deleteAllSpecialEvents() {
        return getWritableDatabase().delete("special_events", null, null);
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

    public int countSpecialEventsByName(String eventName) {
        return intQuery(
                "SELECT COUNT(*) FROM special_events WHERE event_name=? COLLATE NOCASE",
                new String[]{eventName}
        );
    }

    public int countSpecialEventsByTypeId(long eventTypeId) {
        return intQuery(
                "SELECT COUNT(*) FROM special_events WHERE event_type_id=?",
                new String[]{String.valueOf(eventTypeId)}
        );
    }

    public int countAllSpecialEvents() {
        return intQuery("SELECT COUNT(*) FROM special_events", new String[]{});
    }

    public Cursor getDistinctSpecialEventNames() {
        return getReadableDatabase().rawQuery(
                "SELECT event_name, COUNT(*) AS occurrence_count " +
                        "FROM special_events GROUP BY event_name COLLATE NOCASE " +
                        "ORDER BY event_name COLLATE NOCASE ASC",
                null
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
        return buildJson(true);
    }

    public JSONObject debugJson() throws JSONException {
        return buildJson(false);
    }

    private JSONObject buildJson(boolean includeImages) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "TodoDiary");
        root.put("backup_version", 2);
        root.put("schema_version", DB_VERSION);
        root.put("exported_ms", System.currentTimeMillis());
        root.put("todos", tableToJson("todos", "id ASC"));
        JSONArray completions = tableToJson("todo_completions", "completed_ms ASC");
        if (includeImages) {
            embedCompletionImages(completions);
        }
        root.put("todo_completions", completions);
        root.put("diary", tableToJson("diary", "date ASC"));
        root.put("water", tableToJson("water", "time_ms ASC"));
        root.put("event_types", tableToJson("event_types", "name COLLATE NOCASE ASC"));
        root.put("special_events", tableToJson("special_events", "time_ms ASC"));
        root.put("sleep_records", tableToJson("sleep_records", "date ASC"));
        return root;
    }

    public void importJson(JSONObject root) throws JSONException, IOException {
        String app = root.optString("app", "TodoDiary");
        if (!"TodoDiary".equals(app)) {
            throw new JSONException("Backup belongs to a different app");
        }
        if (root.optInt("schema_version", 1) > DB_VERSION) {
            throw new JSONException("Backup uses a newer TodoDiary schema");
        }
        for (String table : BACKUP_TABLES) {
            if (root.optJSONArray(table) == null) {
                throw new JSONException("Backup is missing table: " + table);
            }
        }

        File imageDirectory = imageDirectory();
        if (!imageDirectory.mkdirs() && !imageDirectory.isDirectory()) {
            throw new IOException("Cannot create image storage");
        }
        List<File> newImageFiles = new ArrayList<>();
        Map<Integer, String> importedImagePaths;
        try {
            importedImagePaths = prepareImportedImages(
                    root.getJSONArray("todo_completions"),
                    imageDirectory,
                    newImageFiles
            );
        } catch (JSONException | IOException e) {
            for (File file : newImageFiles) {
                file.delete();
            }
            throw e;
        }
        List<String> oldImagePaths = currentCompletionImagePaths();
        SQLiteDatabase db = getWritableDatabase();
        boolean imported = false;
        db.beginTransaction();
        try {
            for (String table : BACKUP_TABLES) {
                db.delete(table, null, null);
            }
            for (String table : BACKUP_TABLES) {
                insertJsonRows(db, table, root.getJSONArray(table), importedImagePaths);
            }
            db.setTransactionSuccessful();
            imported = true;
        } finally {
            db.endTransaction();
            if (!imported) {
                for (File file : newImageFiles) {
                    file.delete();
                }
            }
        }
        for (String path : oldImagePaths) {
            deleteFile(path);
        }
    }

    private void embedCompletionImages(JSONArray completions) throws JSONException {
        long totalBytes = 0L;
        for (int i = 0; i < completions.length(); i++) {
            JSONObject row = completions.getJSONObject(i);
            String imagePath = row.optString("image_path", "");
            row.remove("image_path");
            if (imagePath.isEmpty()) {
                continue;
            }
            try {
                byte[] bytes = readManagedImage(imagePath);
                totalBytes += bytes.length;
                if (totalBytes > MAX_BACKUP_IMAGE_BYTES) {
                    throw new IOException("backup images exceed 100 MB total");
                }
                row.put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            } catch (IOException e) {
                throw new JSONException("Cannot export completion image for row " + row.optLong("id") + ": " + e.getMessage());
            }
        }
    }

    private Map<Integer, String> prepareImportedImages(
            JSONArray completions,
            File imageDirectory,
            List<File> newImageFiles
    ) throws JSONException, IOException {
        Map<Integer, String> paths = new HashMap<>();
        long totalBytes = 0L;
        for (int i = 0; i < completions.length(); i++) {
            JSONObject row = completions.getJSONObject(i);
            String encoded = row.optString("image_base64", "");
            if (encoded.isEmpty()) {
                continue;
            }
            if (encoded.length() > (MAX_IMAGE_BYTES * 4L / 3L) + 16L) {
                throw new IOException("Completion image exceeds 20 MB");
            }
            byte[] bytes;
            try {
                bytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                throw new JSONException("Invalid Base64 image for completion row " + row.optLong("id"));
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new IOException("Completion image exceeds 20 MB");
            }
            totalBytes += bytes.length;
            if (totalBytes > MAX_BACKUP_IMAGE_BYTES) {
                throw new IOException("Backup images exceed 100 MB total");
            }
            String mime = row.optString("image_mime", "image/jpeg");
            File file = new File(imageDirectory, UUID.randomUUID() + extensionForMime(mime));
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
            }
            newImageFiles.add(file);
            paths.put(i, file.getAbsolutePath());
        }
        return paths;
    }

    private void insertJsonRows(
            SQLiteDatabase db,
            String table,
            JSONArray rows,
            Map<Integer, String> importedImagePaths
    ) throws JSONException {
        Set<String> allowed = new HashSet<>();
        for (String column : columnsForTable(table)) {
            allowed.add(column);
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ContentValues values = new ContentValues();
            for (String column : allowed) {
                if ("todo_completions".equals(table) && "image_path".equals(column)) {
                    String importedPath = importedImagePaths.get(i);
                    if (importedPath != null) {
                        values.put(column, importedPath);
                    }
                    continue;
                }
                if (!row.has(column) || row.isNull(column)) {
                    continue;
                }
                putJsonValue(values, column, row.get(column));
            }
            db.insertOrThrow(table, null, values);
        }
    }

    private String[] columnsForTable(String table) {
        switch (table) {
            case "todos":
                return new String[]{"id", "title", "notes", "time", "weekdays", "active", "image_enabled", "created_ms", "updated_ms"};
            case "todo_completions":
                return new String[]{"id", "todo_id", "title", "completed_ms", "date", "image_allowed", "image_path", "image_mime"};
            case "diary":
                return new String[]{"date", "wake", "sleep", "nap", "lunch", "workload", "notes", "updated_ms"};
            case "water":
                return new String[]{"id", "amount_ml", "time_ms", "date"};
            case "event_types":
                return new String[]{"id", "name", "note", "created_ms", "active"};
            case "special_events":
                return new String[]{"id", "event_type_id", "event_name", "note", "time_ms", "date"};
            case "sleep_records":
                return new String[]{"date", "wake_time", "last_sleep_time", "sleep_minutes", "note", "updated_ms"};
            default:
                throw new IllegalArgumentException("Unsupported backup table: " + table);
        }
    }

    private void putJsonValue(ContentValues values, String column, Object value) {
        if (value instanceof Boolean) {
            values.put(column, (Boolean) value ? 1 : 0);
        } else if (value instanceof Integer) {
            values.put(column, (Integer) value);
        } else if (value instanceof Long) {
            values.put(column, (Long) value);
        } else if (value instanceof Number) {
            values.put(column, ((Number) value).doubleValue());
        } else {
            values.put(column, String.valueOf(value));
        }
    }

    private List<String> currentCompletionImagePaths() {
        List<String> paths = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "todo_completions",
                new String[]{"image_path"},
                "image_path IS NOT NULL AND image_path<>''",
                null,
                null,
                null,
                null
        );
        try {
            while (cursor.moveToNext()) {
                paths.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return paths;
    }

    private byte[] readManagedImage(String path) throws IOException {
        File file = new File(path);
        if (!isManagedImage(file) || !file.isFile()) {
            throw new IOException("managed image file is missing");
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            throw new IOException("image exceeds 20 MB");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private File imageDirectory() {
        return new File(context.getFilesDir(), "todo-images");
    }

    private boolean isManagedImage(File file) throws IOException {
        String root = imageDirectory().getCanonicalPath() + File.separator;
        return file.getCanonicalPath().startsWith(root);
    }

    private void deleteFile(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File file = new File(path);
            if (isManagedImage(file)) {
                file.delete();
            }
        } catch (IOException ignored) {
        }
    }

    private String extensionForMime(String mime) {
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("image/gif".equalsIgnoreCase(mime)) return ".gif";
        if ("image/heic".equalsIgnoreCase(mime) || "image/heif".equalsIgnoreCase(mime)) return ".heic";
        return ".jpg";
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
