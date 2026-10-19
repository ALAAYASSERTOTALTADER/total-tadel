package com.example.yacinestylebackup;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Persistent on-device cache for the bundled M3U playlist. Call load() from a background thread. */
public final class PlaylistCache {
    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE = "playlist_cache_v1.bin";
    private static Data memoryCache;

    private PlaylistCache() { }

    public static final class Data {
        public final ArrayList<String> names = new ArrayList<>();
        public final ArrayList<String> urls = new ArrayList<>();
        public final ArrayList<String> categories = new ArrayList<>();
        public final ArrayList<String> categoryNames = new ArrayList<>();
    }

    private static final class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        int version;
        long sourceSize;
        ArrayList<String> names;
        ArrayList<String> urls;
        ArrayList<String> categories;
        ArrayList<String> categoryNames;
    }

    public static synchronized Data load(Context context) {
        long sourceSize = getSourceSize(context);
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE);

        if (memoryCache != null && cacheFile.isFile()) return copy(memoryCache);

        Payload payload = readCache(cacheFile, sourceSize);
        if (payload != null) {
            memoryCache = fromPayload(payload);
            return copy(memoryCache);
        }

        Data parsed = parsePlaylist(context);
        memoryCache = parsed;
        writeCache(cacheFile, sourceSize, parsed);
        return copy(parsed);
    }

    private static long getSourceSize(Context context) {
        try (InputStream in = context.getResources().openRawResource(R.raw.channels)) {
            return in.available();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static Payload readCache(File file, long sourceSize) {
        if (!file.isFile()) return null;
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            Object object = in.readObject();
            if (!(object instanceof Payload)) return null;
            Payload p = (Payload) object;
            if (p.version != CACHE_VERSION || p.sourceSize != sourceSize
                    || p.names == null || p.urls == null || p.categories == null || p.categoryNames == null
                    || p.names.size() != p.urls.size() || p.names.size() != p.categories.size()) return null;
            return p;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCache(File file, long sourceSize, Data data) {
        File temp = new File(file.getParentFile(), CACHE_FILE + ".tmp");
        Payload p = new Payload();
        p.version = CACHE_VERSION;
        p.sourceSize = sourceSize;
        p.names = data.names;
        p.urls = data.urls;
        p.categories = data.categories;
        p.categoryNames = data.categoryNames;
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            out.writeObject(p);
            out.flush();
        } catch (Exception ignored) {
            temp.delete();
            return;
        }
        if (file.exists() && !file.delete()) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) temp.delete();
    }

    private static Data parsePlaylist(Context context) {
        Data result = new Data();
        LinkedHashSet<String> categorySet = new LinkedHashSet<>();
        String category = "عام";
        boolean skipNextUrl = false;
        String pendingName = null;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.channels), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = findDisplayNameComma(line);
                    pendingName = comma >= 0 ? line.substring(comma + 1).trim() : null;
                    if (isCategoryHeader(pendingName)) {
                        category = cleanCategory(pendingName);
                        if (!category.isEmpty()) categorySet.add(category);
                        skipNextUrl = true;
                        pendingName = null;
                    } else {
                        skipNextUrl = false;
                        if (!isValidDisplayName(pendingName)) pendingName = null;
                    }
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (skipNextUrl) {
                        skipNextUrl = false;
                        continue;
                    }
                    if (pendingName != null) {
                        result.names.add(pendingName);
                        result.urls.add(line);
                        result.categories.add(category);
                        pendingName = null;
                    }
                }
            }
        } catch (Exception ignored) { }

        result.categoryNames.addAll(categorySet);
        return result;
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
        if (value.isEmpty() || value.startsWith("/9j/") || value.startsWith("data:image/")) return false;
        return !value.matches("(?i)^[a-z0-9+/]{40,}={0,2}$");
    }

    private static boolean isCategoryHeader(String name) {
        return name != null && (name.contains("---") || name.contains("★★") || name.contains("●"));
    }

    private static String cleanCategory(String name) {
        if (name == null) return "";
        return name.replace("•", "").replace("●", "").replace("★", "")
                .replace("---", "").replace("--", "").trim().replaceAll("\\s+", " ");
    }

    private static Data fromPayload(Payload p) {
        Data d = new Data();
        d.names.addAll(p.names);
        d.urls.addAll(p.urls);
        d.categories.addAll(p.categories);
        d.categoryNames.addAll(p.categoryNames);
        return d;
    }

    private static Data copy(Data source) {
        Data d = new Data();
        d.names.addAll(source.names);
        d.urls.addAll(source.urls);
        d.categories.addAll(source.categories);
        d.categoryNames.addAll(source.categoryNames);
        return d;
    }
}
