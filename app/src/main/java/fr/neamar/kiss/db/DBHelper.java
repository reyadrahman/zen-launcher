package fr.neamar.kiss.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

import fr.neamar.kiss.BuildConfig;
import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.pojo.ShortcutsPojo;

public class DBHelper {
    private static SQLiteDatabase database = null;
    private static final String TAG = DBHelper.class.getSimpleName();
    private DBHelper() {
    }
    public static Map<String, Integer> loadBadges(Context context) {
        Map<String, Integer> records = new HashMap<>();
        SQLiteDatabase db = getDatabase(context);

        Cursor cursor = db.query("badges", new String[]{"package", "badge_count"}, null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String package_name = cursor.getString(0);
            int badge_count = cursor.getInt(1);
            records.put(package_name, badge_count);
            cursor.moveToNext();
        }
        cursor.close();

        return records;

    }

    public static void setBadgeCount(Context context, String packageName, Integer badgeCount) {
        SQLiteDatabase db = getDatabase(context);

        db.delete("badges", "package = ?", new String[]{packageName});

        if (badgeCount > 0) {
            ContentValues values = new ContentValues();
            values.put("package", packageName);
            values.put("badge_count", badgeCount);

            db.insert("badges", null, values);
        }

    }
    private static SQLiteDatabase getDatabase(Context context) {
        if (database == null) {
            database = new DB(context).getReadableDatabase();
        }
        return database;
    }
    public static void writeDatabase(byte[] db, Context cnt) throws IOException {
        if(database == null) {
            database = new DB(cnt).getReadableDatabase();
        }
        InputStream is = new ByteArrayInputStream(db) {
        }; // data.db
        OutputStream os = new FileOutputStream(database.getPath());

        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
        }

        // Close the streams.
        os.flush();
        os.close();
        is.close();
    }
    private static ArrayList<ValuedHistoryRecord> readCursor(Cursor cursor) {
        ArrayList<ValuedHistoryRecord> records = new ArrayList<>();

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            ValuedHistoryRecord entry = new ValuedHistoryRecord();

            entry.record = cursor.getString(0);
            entry.value = cursor.getInt(1);

            records.add(entry);
            cursor.moveToNext();
        }
        cursor.close();

        return records;
    }

    /**
     * Insert new item into history
     *
     * @param context android context
     * @param query   query to insert
     * @param record  record to insert
     */
    public static void insertHistory(Context context, String query, String record) {
        SQLiteDatabase db = getDatabase(context);
        ContentValues values = new ContentValues();
        values.put("query", query);
        values.put("record", record);
        values.put("timeStamp",System.currentTimeMillis());
        db.insert("history", null, values);
    }

    public static void removeFromHistory(Context context, String record) {
        SQLiteDatabase db = getDatabase(context);
        db.delete("history", "record = ?", new String[]{record});
    }

    public static void clearHistory(Context context) {
        SQLiteDatabase db = getDatabase(context);
        db.delete("history", "", null);
    }

    private static Cursor getHistoryByFrecency(SQLiteDatabase db, int limit) {
        // Since smart history sql uses a group by we don't use the whole history but a limit of recent apps
        int historyWindowSize = limit * 30;

        // order history based on frequency * recency
        // frequency = #launches_for_app / #all_launches
        // recency = 1 / position_of_app_in_normal_history
        String sql = "SELECT record, count(*) FROM " +
                " (" +
                "   SELECT * FROM history ORDER BY _id DESC " +
                "   LIMIT " + historyWindowSize + "" +
                " ) small_history " +
                " GROUP BY record " +
                " ORDER BY " +
                "   count(*) * 1.0 / (select count(*) from history LIMIT " + historyWindowSize + ") / ((SELECT _id FROM history ORDER BY _id DESC LIMIT 1) - max(_id) + 0.001) " +
                " DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    private static Cursor getHistoryByFrequency(SQLiteDatabase db, int limit) {
        // order history based on frequency
        String sql = "SELECT record, count(*) FROM history" +
                " GROUP BY record " +
                " ORDER BY count(*) DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    private static Cursor getHistoryByRecency(SQLiteDatabase db, int limit) {
        return db.query(true, "history", new String[]{"record", "1"}, null, null,
                null, null, "_id DESC", Integer.toString(limit));
    }

    /**
     * Get the most used history items adaptively based on a set period of time
     *
     * @param db The SQL db
     * @param hours How many hours back we want to test frequency against
     * @param limit Maximum result size
     * @return Cursor
     */
    private static Cursor getHistoryByAdaptive(SQLiteDatabase db, int hours,int limit) {
        // order history based on frequency
        String sql = "SELECT record, count(*) FROM history " +
                "WHERE timeStamp >= 0 "+
                "AND timeStamp >" + (System.currentTimeMillis() - ( hours *3600000))+
                " GROUP BY record " +
                " ORDER BY count(*) DESC " +
                " LIMIT " + limit;
        return db.rawQuery(sql, null);
    }

    /**
     * Retrieve previous query history
     *
     * @param context     android context
     * @param limit       max number of items to retrieve
     * @param sortHistory sort history entries alphabetically
     * @return records with number of use
     */
    public static ArrayList<ValuedHistoryRecord> getHistory(Context context, int limit, String historyMode, boolean sortHistory) {
        ArrayList<ValuedHistoryRecord> records;

        SQLiteDatabase db = getDatabase(context);

        Cursor cursor;
        switch (historyMode) {
            case "frecency":
                cursor = getHistoryByFrecency(db, limit);
                break;
            case "frequency":
                cursor = getHistoryByFrequency(db, limit);
                break;
            case "adaptive":
                cursor = getHistoryByAdaptive(db,36, limit);
                break;
            default:
                cursor = getHistoryByRecency(db, limit);
                break;
        }

        records = readCursor(cursor);
        cursor.close();

        // sort history entries alphabetically
        if (sortHistory) {
            DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();

            for (ValuedHistoryRecord entry : records) {
                entry.name = dataHandler.getItemName(entry.record);
            }

            Collections.sort(records, new Comparator<ValuedHistoryRecord>() {
                @Override
                public int compare(ValuedHistoryRecord a, ValuedHistoryRecord b) {
                    return a.name.compareTo(b.name);
                }
            });
        }

        return records;
    }


    /**
     * Retrieve history size
     *
     * @param context android context
     * @return total number of use for the application
     */
    public static int getHistoryLength(Context context) {
        SQLiteDatabase db = getDatabase(context);

        // Cursor query (boolean distinct, String table, String[] columns,
        // String selection, String[] selectionArgs, String groupBy, String
        // having, String orderBy, String limit)
        Cursor cursor = db.query(false, "history", new String[]{"COUNT(*)"}, null, null,
                null, null, null, null);

        cursor.moveToFirst();
        int historyLength = cursor.getInt(0);
        cursor.close();
        return historyLength;
    }

    /**
     * Retrieve previously selected items for the query
     *
     * @param context android context
     * @param query   query to run
     * @return records with number of use
     */
    public static ArrayList<ValuedHistoryRecord> getPreviousResultsForQuery(Context context,
                                                                            String query) {
        ArrayList<ValuedHistoryRecord> records;
        SQLiteDatabase db = getDatabase(context);

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        Cursor cursor = db.query("history", new String[]{"record", "COUNT(*) AS count"},
                "query LIKE ?", new String[]{query + "%"}, "record", null, "COUNT(*) DESC", "10");
        records = readCursor(cursor);
        cursor.close();
        return records;
    }

    public static boolean insertShortcut(Context context, ShortcutRecord shortcut) {
        SQLiteDatabase db = getDatabase(context);
        if (BuildConfig.DEBUG) Log.d(TAG,"insertShortcut, packageName: "+shortcut.packageName+" intentUri: " + shortcut.intentUri);
        // Do not add duplicate shortcuts
        if (shortcut.packageName!=null&&shortcut.intentUri!=null) {
            Cursor cursor = db.query("shortcuts", new String[]{"package", "intent_uri"},
                    "package = ? AND intent_uri = ?", new String[]{shortcut.packageName, shortcut.intentUri}, null, null, null, null);
            if (cursor.moveToFirst()) {
                return false;
            }
            cursor.close();

            // packageName can be null on Android 7 chrome shortcut
        } else if (shortcut.packageName==null&&shortcut.intentUri!=null){
            Cursor cursor = db.query("shortcuts", new String[]{"intent_uri"},
                    "intent_uri = ?", new String[]{shortcut.intentUri}, null, null, null, null);
            if (cursor.moveToFirst()) {
                return false;
            }
            cursor.close();
        }

        ContentValues values = new ContentValues();
        values.put("name", shortcut.name);
        values.put("package", shortcut.packageName);
        values.put("icon", shortcut.iconResource);
        values.put("intent_uri", shortcut.intentUri);
        values.put("icon_blob", shortcut.icon_blob);

        db.insert("shortcuts", null, values);
        return true;
    }

    public static void removeShortcut(Context context, ShortcutsPojo shortcut) {
        SQLiteDatabase db = getDatabase(context);
        db.delete("shortcuts", "package = ? AND intent_uri = ?", new String[]{shortcut.packageName, shortcut.intentUri});
    }

    public static ArrayList<ShortcutRecord> getShortcuts(Context context, String packageName) {
        ArrayList<ShortcutRecord> records = new ArrayList<>();
        SQLiteDatabase db = getDatabase(context);

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        Cursor cursor = db.query("shortcuts", new String[]{"name", "package", "icon", "intent_uri", "icon_blob"},
                "package = ?", new String[]{packageName}, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            ShortcutRecord entry = new ShortcutRecord();

            entry.name = cursor.getString(0);
            entry.packageName = cursor.getString(1);
            entry.iconResource = cursor.getString(2);
            entry.intentUri = cursor.getString(3);
            entry.icon_blob = cursor.getBlob(4);

            records.add(entry);
            cursor.moveToNext();
        }
        cursor.close();

        return records;
    }

    public static ArrayList<ShortcutRecord> getShortcuts(Context context) {
        ArrayList<ShortcutRecord> records = new ArrayList<>();
        SQLiteDatabase db = getDatabase(context);

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        Cursor cursor = db.query("shortcuts", new String[]{"name", "package", "icon", "intent_uri", "icon_blob"},
                null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            ShortcutRecord entry = new ShortcutRecord();

            entry.name = cursor.getString(0);
            entry.packageName = cursor.getString(1);
            entry.iconResource = cursor.getString(2);
            entry.intentUri = cursor.getString(3);
            entry.icon_blob = cursor.getBlob(4);

            records.add(entry);
            cursor.moveToNext();
        }
        cursor.close();

        return records;
    }

    public static void removeShortcuts(Context context, String packageName) {
        SQLiteDatabase db = getDatabase(context);

        // Cursor query (String table, String[] columns, String selection,
        // String[] selectionArgs, String groupBy, String having, String
        // orderBy)
        Cursor cursor = db.query("shortcuts", new String[]{"name", "package", "icon", "intent_uri", "icon_blob"},
                null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) { // remove from history
            db.delete("history", "record = ?", new String[]{ShortcutsPojo.SCHEME + cursor.getString(0).toLowerCase(Locale.ROOT)});
            cursor.moveToNext();
        }
        cursor.close();

        //remove shortcuts
        db.delete("shortcuts", "package LIKE ?", new String[]{"%" + packageName + "%"});
    }

    public static void removeAllShortcuts(Context context) {
        SQLiteDatabase db = getDatabase(context);
        // delete whole table
        db.delete("shortcuts", null, null);
    }

    /**
     * Insert new tags for given id
     *
     * @param context android context
     * @param tag     tag to insert
     * @param record  record to insert
     */
    public static void insertTagsForId(Context context, String tag, String record) {
        SQLiteDatabase db = getDatabase(context);
        ContentValues values = new ContentValues();
        values.put("tag", tag);
        values.put("record", record);
        db.insert("tags", null, values);
    }


    /* Delete
     * Insert new item into history
     *
     * @param context android context
     * @param tag   query to insert
     * @param record  record to insert
     */
    public static void deleteTagsForId(Context context, String record) {
        SQLiteDatabase db = getDatabase(context);

        db.delete("tags", "record = ?", new String[]{record});
    }

    public static Map<String, String> loadTags(Context context) {
        Map<String, String> records = new HashMap<>();
        SQLiteDatabase db = getDatabase(context);

        Cursor cursor = db.query("tags", new String[]{"record", "tag"}, null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String id = cursor.getString(0);
            String tags = cursor.getString(1);
            records.put(id, tags);
            cursor.moveToNext();
        }
        cursor.close();
        return records;

    }

    public static void setDatabase(byte[] db) {
        //database = db;
    }

    public static ByteArrayOutputStream getDatabaseBytes() {

        ByteArrayOutputStream os=new ByteArrayOutputStream();
        File file = new File(database.getPath());

        byte[] b = new byte[(int) file.length()];
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            fileInputStream.read(b);
            for (int i = 0; i < b.length; i++) {
                os.write((char)b[i]);
            }
        } catch (FileNotFoundException e) {
            System.out.println("File Not Found.");
            e.printStackTrace();
        }
        catch (IOException e1) {
            System.out.println("Error Reading The File.");
            e1.printStackTrace();
        }
        return os;
    }


    public static void clearCachedDb() {
        database = null;
    }
}
