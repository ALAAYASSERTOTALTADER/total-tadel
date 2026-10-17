package com.example.yacinestylebackup;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Parses the bundled playlist off the UI thread and caches parsed entries privately on-device. */
final class ChannelRepository {
    static final class Channel {
        final String name;
        final String url;
        final String category;
        Channel(String name, String url, String category) {
            this.name = name; this.url = url; this.category = category;
        }
    }

    interface Callback { void onLoaded(List<Channel> channels, List<String> categories); }
    private static final int CACHE_MAGIC = 0x54495033;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<Channel> cachedChannels;
    private static volatile List<String> cachedCategories;
    private static boolean loading;
    private static final List<Callback> WAITING = new ArrayList<>();

    private ChannelRepository() { }

    static void load(Context context, Callback callback) {
        synchronized (ChannelRepository.class) {
            if (cachedChannels != null) {
                List<Channel> channels = cachedChannels;
                List<String> categories = cachedCategories;
                MAIN.post(() -> callback.onLoaded(channels, categories));
                return;
            }
            WAITING.add(callback);
            if (loading) return;
            loading = true;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<Channel> channels;
            List<String> categories;
            try {
                File cache = new File(app.getFilesDir(), "playlist-cache-v3-" + 1 + ".bin");
                Parsed parsed = readCache(cache);
                if (parsed == null) {
                    parsed = parsePlaylist(app);
                    writeCache(cache, parsed);
                }
                channels = Collections.unmodifiableList(parsed.channels);
                categories = Collections.unmodifiableList(new ArrayList<>(parsed.categories));
            } catch (Exception e) {
                channels = Collections.emptyList();
                categories = Collections.emptyList();
            }
            List<Callback> callbacks;
            synchronized (ChannelRepository.class) {
                cachedChannels = channels;
                cachedCategories = categories;
                loading = false;
                callbacks = new ArrayList<>(WAITING);
                WAITING.clear();
            }
            List<Channel> finalChannels = channels;
            List<String> finalCategories = categories;
            MAIN.post(() -> { for (Callback cb : callbacks) cb.onLoaded(finalChannels, finalCategories); });
        });
    }

    private static final class Parsed {
        final ArrayList<Channel> channels = new ArrayList<>();
        final LinkedHashSet<String> categories = new LinkedHashSet<>();
    }

    private static String cleanCategory(String name) {
        return name.replace("•", "").replace("●", "").replace("★", "")
                .replace("---", "").replace("--", "").trim().replaceAll("\\s+", " ");
    }
    private static boolean isHeader(String name) {
        return name != null && (name.contains("---") || name.contains("★★") || name.contains("●"));
    }

    private static Parsed parsePlaylist(Context context) throws Exception {
        Parsed result = new Parsed();
        String category = "عام";
        boolean skipNextUrl = false;
        String pendingName = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.channels), "UTF-8"), 64 * 1024)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "قناة";
                    if (isHeader(pendingName)) {
                        category = cleanCategory(pendingName);
                        if (!category.isEmpty()) result.categories.add(category);
                        skipNextUrl = true;
                        pendingName = null;
                    } else skipNextUrl = false;
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (skipNextUrl) { skipNextUrl = false; continue; }
                    if (pendingName != null) {
                        result.channels.add(new Channel(pendingName, line, category));
                        pendingName = null;
                    }
                }
            }
        }
        return result;
    }

    private static Parsed readCache(File file) {
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != CACHE_MAGIC) return null;
            int categoryCount = in.readInt();
            int channelCount = in.readInt();
            if (categoryCount < 0 || categoryCount > 10000 || channelCount < 0 || channelCount > 2000000) return null;
            Parsed p = new Parsed();
            for (int i = 0; i < categoryCount; i++) p.categories.add(in.readUTF());
            for (int i = 0; i < channelCount; i++) p.channels.add(new Channel(in.readUTF(), in.readUTF(), in.readUTF()));
            return p;
        } catch (Exception e) { return null; }
    }

    private static void writeCache(File file, Parsed p) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
            out.writeInt(CACHE_MAGIC);
            out.writeInt(p.categories.size());
            out.writeInt(p.channels.size());
            for (String c : p.categories) out.writeUTF(c);
            for (Channel c : p.channels) { out.writeUTF(c.name); out.writeUTF(c.url); out.writeUTF(c.category); }
        } catch (Exception e) { temp.delete(); return; }
        if (file.exists()) file.delete();
        if (!temp.renameTo(file)) temp.delete();
    }
}
