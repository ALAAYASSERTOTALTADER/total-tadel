package com.example.yacinestylebackup;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class ChannelCacheDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "channel_cache.db";
    private static final int DB_VERSION = 1;

    static final class Channel {
        final String name;
        final String url;
        final String category;

        Channel(String name, String url, String category) {
            this.name = name;
            this.url = url;
            this.category = category;
        }
    }

    ChannelCacheDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channels (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "url TEXT NOT NULL," +
                "category TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_channels_category ON channels(category)");
        db.execSQL("CREATE INDEX idx_channels_name ON channels(name)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // لا نحذف بيانات القنوات عند ترقية التطبيق.
    }

    synchronized boolean isEmpty() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM channels", null)) {
            return !c.moveToFirst() || c.getLong(0) == 0;
        }
    }

    synchronized void loadFromM3u(Resources resources, int rawResource,
                                  boolean forceRefresh) throws Exception {
        if (!forceRefresh && !isEmpty()) return;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        resources.openRawResource(rawResource),
                        StandardCharsets.UTF_8), 64 * 1024)) {

            db.delete("channels", null, null);

            String category = "عام";
            String pendingName = null;
            boolean skipNextUrl = false;
            String line;
            int batchCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#EXTINF")) {
                    int comma = findDisplayNameComma(line);
                    pendingName = comma >= 0
                            ? line.substring(comma + 1).trim() : null;

                    if (isCategoryHeader(pendingName)) {
                        category = cleanCategory(pendingName);
                        skipNextUrl = true;
                        pendingName = null;
                    } else {
                        skipNextUrl = false;
                        if (!isValidDisplayName(pendingName)) {
                            pendingName = null;
                        }
                    }
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    if (skipNextUrl) {
                        skipNextUrl = false;
                        continue;
                    }

                    if (pendingName != null) {
                        ContentValues values = new ContentValues();
                        values.put("name", pendingName);
                        values.put("url", line);
                        values.put("category", category);
                        db.insertOrThrow("channels", null, values);
                        batchCount++;

                        pendingName = null;
                    }
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    interface SearchProgressListener {
        void onProgress(int percent, int scanned, int total);
    }

    synchronized ArrayList<Channel> getChannels(
            String wantedCategory, String query,
            boolean favoritesOnly, Set<String> favoriteNames) {
        return getChannels(wantedCategory, query, favoritesOnly, favoriteNames, null);
    }

    synchronized ArrayList<Channel> getChannels(
            String wantedCategory, String query,
            boolean favoritesOnly, Set<String> favoriteNames,
            SearchProgressListener progressListener) {

        ArrayList<Channel> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        String selection = null;
        String[] args = null;

        if (wantedCategory != null) {
            selection = "category = ?";
            args = new String[]{wantedCategory};
        }

        String q = normalizeForSearch(query);
        final String[] FAST_SEARCH_WORDS =
                q.isEmpty() ? new String[0] : q.split(" ");

        Set<String> favorites = favoriteNames == null
                ? new HashSet<>() : favoriteNames;

        try (Cursor c = db.query(
                "channels",
                new String[]{"name", "url", "category"},
                selection, args, null, null, "id ASC")) {

            int total = c.getCount();
            int scanned = 0;
            int lastReported = -1;

            if (progressListener != null) {
                progressListener.onProgress(total == 0 ? 100 : 0, 0, total);
            }

            while (c.moveToNext()) {
                String name = c.getString(0);
                String url = c.getString(1);
                String category = c.getString(2);

                scanned++;

                if (favoritesOnly && !favorites.contains(name)) {
                    // Still report progress for skipped rows.
                } else if (matchesSearch(name, category, q, FAST_SEARCH_WORDS)) {
                    result.add(new Channel(name, url, category));
                }

                if (progressListener != null && total > 0) {
                    int percent = Math.min(100, (int) ((scanned * 100L) / total));
                    // حدّث الواجهة كل 5% بدل تحديثها مع كل نسبة.
                    if (percent == 100 || percent >= lastReported + 5) {
                        progressListener.onProgress(percent, scanned, total);
                        lastReported = percent;
                    }
                }
            }

            if (progressListener != null) {
                progressListener.onProgress(100, total, total);
            }
        }

        return result;
    }

    private static String normalizeForSearch(String value) {
        if (value == null || value.isEmpty()) return "";

        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingSpace = false;

        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);

            // تجاهل التشكيل العربي.
            if ((ch >= '\u064B' && ch <= '\u065F') || ch == '\u0670') {
                continue;
            }

            switch (ch) {
                case 'أ': case 'إ': case 'آ': ch = 'ا'; break;
                case 'ى': ch = 'ي'; break;
                case 'ؤ': ch = 'و'; break;
                case 'ئ': ch = 'ي'; break;
                case 'ة': ch = 'ه'; break;
                default: break;
            }

            if (Character.isLetterOrDigit(ch)) {
                if (pendingSpace && out.length() > 0) out.append(' ');
                out.append(ch);
                pendingSpace = false;
            } else if (out.length() > 0) {
                pendingSpace = true;
            }
        }

        return out.toString();
    }

    private static boolean matchesSearch(
            String name, String category, String query, String[] words) {
        if (query == null || query.isEmpty()) return true;

        String normalizedName = normalizeForSearch(name);
        String normalizedCategory = normalizeForSearch(category);

        if (normalizedName.contains(query) ||
                normalizedCategory.contains(query)) {
            return true;
        }

        // كلمات البحث مجهزة مرة واحدة فقط لكل عملية بحث.
        for (String word : words) {
            if (word.isEmpty()) continue;

            if (!normalizedName.contains(word) &&
                    !normalizedCategory.contains(word)) {
                return false;
            }
        }
        return words.length > 0;
    }

    private static int findDisplayNameComma(String line) {
        boolean quoted = false;
        int found = -1;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) found = i;
        }
        return found;
    }

    private static boolean isValidDisplayName(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.isEmpty() || value.startsWith("/9j/") ||
                value.startsWith("data:image/")) return false;
        return !value.matches("(?i)^[a-z0-9+/]{40,}={0,2}$");
    }

    private static String cleanCategory(String name) {
        if (name == null) return "";
        return name.replace("•", "")
                .replace("●", "")
                .replace("★", "")
                .replace("---", "")
                .replace("--", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean isCategoryHeader(String name) {
        return name != null &&
                (name.contains("---") || name.contains("★★") ||
                 name.contains("●"));
    }
}
