package com.example.yacinestylebackup;

import android.content.res.Configuration;
import android.app.AlertDialog;

import android.content.SharedPreferences;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Environment;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.app.ProgressDialog;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class LiveActivity extends ComponentActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout playerContainer;
    private ListView channelList;
    private View topBar;
    private ProgressBar loading;
    private TextView status, sectionTitle;

    private boolean fullscreen = false;
    private boolean controlsLocked = false;
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> urls = new ArrayList<>();
    private int currentIndex = -1;
    private int retryCount = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String selectedCategory;
    private boolean favoritesOnly = false;
    private ArrayAdapter<String> channelAdapter;

    // Parsed playlist cached in memory; never parse the large M3U on the UI thread.
    private final ArrayList<String> allNames = new ArrayList<>();
    private final ArrayList<String> allUrls = new ArrayList<>();
    private final ArrayList<String> allCategories = new ArrayList<>();
    private boolean channelsLoaded = false;
    private boolean channelsLoading = false;
    private String activeQuery = "";
    private String activeChannelName;
    private String activeChannelUrl;
    private int playbackGeneration = 0;
    private volatile boolean downloadCancelled = false;
    private Thread downloadThread;
    private ProgressDialog downloadDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_live);

        prefs = getSharedPreferences("legend", MODE_PRIVATE);
        selectedCategory = getIntent().getStringExtra("category");
        favoritesOnly = getIntent().getBooleanExtra("favoritesOnly", false);

        playerView = findViewById(R.id.player);
        playerContainer = findViewById(R.id.playerContainer);
        channelList = findViewById(R.id.channelList);
        topBar = findViewById(R.id.topBar);
        loading = findViewById(R.id.loading);
        status = findViewById(R.id.status);
        sectionTitle = findViewById(R.id.sectionTitle);

        Button quality = findViewById(R.id.qualityButton);
        Button full = findViewById(R.id.fullscreenButton);
        Button favorite = findViewById(R.id.favoriteButton);
        Button retry = findViewById(R.id.retryButton);
        Button lock = findViewById(R.id.lockButton);
        Button refresh = findViewById(R.id.refreshButton);
        Button downloads = findViewById(R.id.downloadsButton);
        SearchView search = findViewById(R.id.searchChannels);
        downloads.setOnClickListener(v -> showDownloads());

        sectionTitle.setText(favoritesOnly ? "⭐ المفضلة" : (selectedCategory == null ? "📺 كل القنوات" : "📁 " + selectedCategory));

        channelAdapter = new ArrayAdapter<String>(
                this, R.layout.item_channel, R.id.channelName, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = getLayoutInflater().inflate(R.layout.item_channel, parent, false);
                }
                TextView itemName = row.findViewById(R.id.channelName);
                Button downloadButton = row.findViewById(R.id.downloadItemButton);
                itemName.setText(getItem(position));
                final int itemPosition = position;
                itemName.setOnClickListener(itemView -> {
                    if (itemPosition >= 0 && itemPosition < urls.size()) {
                        currentIndex = itemPosition;
                        retryCount = 0;
                        playCurrent();
                    }
                });
                downloadButton.setOnClickListener(v -> {
                    if (position >= 0 && position < urls.size() && position < names.size()) {
                        startMovieDownload(names.get(position), urls.get(position));
                    }
                });
                return row;
            }
        };
        channelList.setAdapter(channelAdapter);

        channelList.setOnItemClickListener((a, v, pos, id) -> {
            if (pos < urls.size()) {
                currentIndex = pos;
                retryCount = 0;
                playCurrent();
            }
        });
        channelList.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= 0 && pos < urls.size()) {
                currentIndex = pos;
                new AlertDialog.Builder(this)
                        .setTitle(names.get(pos))
                        .setMessage("هل تريد تنزيل هذا الفيلم على الجهاز؟")
                        .setPositiveButton("تنزيل", (dialog, which) -> startMovieDownload(names.get(pos), urls.get(pos)))
                        .setNegativeButton("إلغاء", null)
                        .show();
                return true;
            }
            return false;
        });

        quality.setOnClickListener(v -> showQualitySelector());
        full.setOnClickListener(v -> toggleFullscreen());
        favorite.setOnClickListener(v -> toggleFavorite());
        lock.setOnClickListener(v -> {
            controlsLocked = !controlsLocked;
            playerView.setUseController(!controlsLocked);
            lock.setText(controlsLocked ? "🔒 مقفول" : "🔓 قفل");
            Toast.makeText(this, controlsLocked ? "تم قفل أدوات المشغل" : "تم فتح أدوات المشغل", Toast.LENGTH_SHORT).show();
        });

        retry.setOnClickListener(v -> {
            retryCount = 0;
            playCurrent();
        });

        refresh.setOnClickListener(v -> {
            activeQuery = "";
            currentIndex = -1;
            search.setQuery("", false);
            if (channelsLoaded) applyChannelFilter(selectedCategory);
            else loadChannels(selectedCategory);
        });

        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return true; }
            @Override public boolean onQueryTextChange(String text) {
                filterChannels(text);
                return true;
            }
        });

        // Only play an item when this screen was explicitly opened with an item.
        // Do not automatically resume the previously saved channel/file.
        String startupName = getIntent().getStringExtra("autoplayName");
        String startupUrl = getIntent().getStringExtra("autoplayUrl");

        // Start saved playback before parsing the full playlist.
        if (startupUrl != null && !startupUrl.trim().isEmpty()) {
            playChannel(startupName == null ? "آخر قناة" : startupName, startupUrl);
        } else {
            status.setText("Please wait...");
        }
        loadChannels(selectedCategory);

    }

    // Find the display-name separator comma, ignoring commas inside quoted attributes
    // (notably data:image/jpeg;base64,... logos).
    private int findDisplayNameComma(String line) {
        boolean quoted = false;
        int found = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') quoted = !quoted;
            else if (ch == ',' && !quoted) found = i;
        }
        return found;
    }

    private boolean isValidDisplayName(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.isEmpty() || value.startsWith("/9j/") || value.startsWith("data:image/")) return false;
        return !value.matches("(?i)^[a-z0-9+/]{40,}={0,2}$");
    }

    private String cleanCategory(String name) {
        if (name == null) return "";
        return name.replace("•", "")
                .replace("●", "")
                .replace("★", "")
                .replace("---", "")
                .replace("--", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean isCategoryHeader(String name) {
        return name != null && (name.contains("---") || name.contains("★★") || name.contains("●"));
    }

    private void loadChannels(String wanted) {
        if (channelsLoaded) {
            applyChannelFilter(wanted);
            return;
        }
        if (channelsLoading) return;

        channelsLoading = true;
        if (activeChannelUrl == null) status.setText("Please wait...");

        new Thread(() -> {
            PlaylistCache.Data cached = PlaylistCache.load(this);
            runOnUiThread(() -> {
                channelsLoading = false;
                if (isFinishing() || isDestroyed()) return;

                allNames.clear();
                allUrls.clear();
                allCategories.clear();
                allNames.addAll(cached.names);
                allUrls.addAll(cached.urls);
                allCategories.addAll(cached.categories);
                channelsLoaded = true;

                applyChannelFilter(wanted);
                if (activeChannelUrl == null) {
                    loading.setVisibility(View.GONE);
                    status.setText(names.isEmpty()
                            ? "No items in this section"
                            : "Choose an item to start playback");
                }
            });
        }, "m3u-loader").start();
    }

    private void applyChannelFilter(String wanted) {
        if (!channelsLoaded) return;

        Set<String> stored = prefs.getStringSet(
                "favorite_channels", new HashSet<>());
        Set<String> favorites = stored == null
                ? new HashSet<>() : new HashSet<>(stored);
        String query = activeQuery == null ? ""
                : activeQuery.trim().toLowerCase(java.util.Locale.ROOT);

        names.clear();
        urls.clear();

        for (int i = 0; i < allNames.size(); i++) {
            String name = allNames.get(i);
            boolean categoryOk = wanted == null
                    || wanted.equals(allCategories.get(i));
            boolean favoriteOk = !favoritesOnly || favorites.contains(name);
            boolean queryOk = query.isEmpty()
                    || name.toLowerCase(java.util.Locale.ROOT).contains(query);

            if (categoryOk && favoriteOk && queryOk) {
                names.add(name);
                urls.add(allUrls.get(i));
            }
        }

        currentIndex = -1;
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
    }
    private void playCurrent() {
        if (currentIndex >= 0 && currentIndex < urls.size()) {
            playChannel(names.get(currentIndex), urls.get(currentIndex));
        } else if (activeChannelUrl != null) {
            playChannel(activeChannelName, activeChannelUrl);
        } else {
            Toast.makeText(this, "اختر قناة أولاً", Toast.LENGTH_SHORT).show();
        }
    }

    private void playChannel(String name, String url) {
        if (url == null || url.trim().isEmpty()) return;

        activeChannelName = name;
        activeChannelUrl = url;
        final int generation = ++playbackGeneration;

        status.setText("Please wait...");
        loading.setVisibility(View.VISIBLE);

        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (player == null) return;

                    if (state == Player.STATE_BUFFERING) {
                        loading.setVisibility(View.VISIBLE);
                        status.setText("Please wait...");
                    } else if (state == Player.STATE_READY) {
                        loading.setVisibility(View.GONE);
                        status.setText("مباشر الآن • " + activeChannelName);
                        retryCount = 0;
                        prefs.edit()
                                .putString("last_channel_name", activeChannelName)
                                .putString("last_channel_url", activeChannelUrl)
                                .apply();
                    } else if (state == Player.STATE_ENDED) {
                        final int retryGeneration = playbackGeneration;
                        handler.postDelayed(() -> {
                            if (!isFinishing()
                                    && retryGeneration == playbackGeneration
                                    && activeChannelUrl != null) {
                                playChannel(activeChannelName, activeChannelUrl);
                            }
                        }, 2000);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    final int retryGeneration = playbackGeneration;
                    loading.setVisibility(View.GONE);
                    retryCount++;
                    status.setText("انقطع البث • إعادة اتصال تلقائي " + retryCount);

                    handler.postDelayed(() -> {
                        if (!isFinishing()
                                && retryGeneration == playbackGeneration
                                && activeChannelUrl != null) {
                            playChannel(activeChannelName, activeChannelUrl);
                        }
                    }, Math.min(5000, 1500 + retryCount * 500L));
                }
            });
        }

        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.prepare();
        player.play();
    }
    private void filterChannels(String query) {
        activeQuery = query == null ? "" : query;
        currentIndex = -1;
        if (channelsLoaded) applyChannelFilter(selectedCategory);
        else loadChannels(selectedCategory);
    }
    private void toggleFavorite() {
        String name = currentIndex >= 0 && currentIndex < names.size()
                ? names.get(currentIndex) : activeChannelName;
        if (name == null || name.trim().isEmpty()) {
            Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> stored = prefs.getStringSet(
                "favorite_channels", new HashSet<>());
        Set<String> saved = stored == null
                ? new HashSet<>() : new HashSet<>(stored);

        if (saved.contains(name)) {
            saved.remove(name);
            Toast.makeText(this, "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show();
        } else {
            saved.add(name);
            Toast.makeText(this, "تمت الإضافة للمفضلة ⭐", Toast.LENGTH_SHORT).show();
        }

        prefs.edit().putStringSet("favorite_channels", saved).apply();
        if (channelsLoaded) applyChannelFilter(selectedCategory);
    }
    private File downloadsDirectory() {
        File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(getFilesDir(), "movies");
        if (!base.exists()) base.mkdirs();
        return base;
    }

    private String safeFileName(String name) {
        String cleaned = name == null ? "movie" : name.replaceAll("[\\\\/:*?\"<>|\\\\r\\\\n]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "movie";
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100);
        return cleaned;
    }

    private void startMovieDownload(String name, String source) {
        if (downloadThread != null && downloadThread.isAlive()) {
            Toast.makeText(this, "يوجد تنزيل جارٍ بالفعل", Toast.LENGTH_SHORT).show();
            return;
        }
        if (source == null || !(source.startsWith("http://") || source.startsWith("https://"))) {
            Toast.makeText(this, "رابط التنزيل غير مدعوم", Toast.LENGTH_LONG).show();
            return;
        }
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("/live/") || lower.contains("/movie/") == false && (lower.contains("/live") || lower.contains("playlist"))) {
            Toast.makeText(this, "يبدو أن هذا رابط بث مباشر وليس ملف فيلم قابلًا للتنزيل", Toast.LENGTH_LONG).show();
            return;
        }

        downloadCancelled = false;
        downloadDialog = new ProgressDialog(this);
        downloadDialog.setTitle("تنزيل " + name);
        downloadDialog.setMessage("جاري التحقق من رابط الفيلم...");
        downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        downloadDialog.setIndeterminate(true);
        downloadDialog.setCancelable(false);
        downloadDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء", (dialog, which) -> {
            downloadCancelled = true;
            if (downloadThread != null) downloadThread.interrupt();
        });
        downloadDialog.show();

        downloadThread = new Thread(() -> {
            HttpURLConnection connection = null;
            File partial = null;
            try {
                URL url = new URL(source);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                connection = conn;
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "TotalIPTV/2.1");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                String type = conn.getContentType();
                String disposition = conn.getHeaderField("Content-Disposition");
                String path = url.getPath() == null ? "" : url.getPath().toLowerCase(java.util.Locale.ROOT);
                boolean hasFilename = disposition != null && disposition.toLowerCase(java.util.Locale.ROOT).contains("filename");
                boolean videoMime = type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("video/");
                boolean genericDownloadMime = type != null && type.toLowerCase(java.util.Locale.ROOT).contains("application/octet-stream");
                boolean knownVideo = path.matches(".*\\.(mp4|mkv|avi|mov|webm|m4v|wmv|flv)$")
                        || (hasFilename && (videoMime || genericDownloadMime));
                if (!knownVideo && (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("video/"))) {
                    throw new java.io.IOException("الرابط لا يبدو ملف فيديو مباشرًا؛ روابط البث M3U8 غير مدعومة للتنزيل");
                }
                long total = conn.getContentLengthLong();
                String ext = ".mp4";
                if (path.matches(".*\\.(mp4|mkv|avi|mov|webm|m4v|wmv|flv)$")) ext = path.substring(path.lastIndexOf('.'));
                File target = new File(downloadsDirectory(), safeFileName(name) + ext);
                partial = new File(target.getAbsolutePath() + ".part");
                final long contentLength = total;
                runOnUiThread(() -> {
                    if (downloadDialog != null) {
                        downloadDialog.setIndeterminate(contentLength <= 0);
                        downloadDialog.setMax(1000);
                        downloadDialog.setMessage("جاري تنزيل الفيلم...");
                    }
                });
                long copied = 0;
                byte[] buffer = new byte[64 * 1024];
                long lastUi = 0;
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (downloadCancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        out.write(buffer, 0, n);
                        copied += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (contentLength > 0 && now - lastUi > 250) {
                            lastUi = now;
                            int progress = (int) Math.min(1000, copied * 1000L / contentLength);
                            runOnUiThread(() -> { if (downloadDialog != null) downloadDialog.setProgress(progress); });
                        }
                    }
                }
                if (downloadCancelled) throw new InterruptedException();
                File finalTarget = target;
                if (target.exists() && !target.delete()) throw new java.io.IOException("تعذر استبدال الملف الموجود");
                if (!partial.renameTo(finalTarget)) throw new java.io.IOException("تعذر حفظ الفيلم");
                boolean visibleInDownloads = publishToPublicDownloads(finalTarget);
                runOnUiThread(() -> {
                    closeDownloadDialog();
                    String message = visibleInDownloads
                            ? "اكتمل تنزيل الفيلم في Download/TOTAL TADER: " + finalTarget.getName()
                            : "اكتمل التنزيل داخل التطبيق، لكن تعذر نسخه إلى مجلد Download: " + finalTarget.getName();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } catch (InterruptedException e) {
                if (partial != null && partial.exists()) partial.delete();
                runOnUiThread(() -> { closeDownloadDialog(); Toast.makeText(this, "تم إلغاء التنزيل", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                if (partial != null && partial.exists()) partial.delete();
                String message = e.getMessage() == null ? "فشل تنزيل الفيلم" : e.getMessage();
                runOnUiThread(() -> { closeDownloadDialog(); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "movie-download");
        downloadThread.start();
    }


    // Publish a completed video to the user's public Downloads folder on Android 10+.
    private boolean publishToPublicDownloads(File source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }

        String lowerName = source.getName().toLowerCase(java.util.Locale.ROOT);
        String mime = "video/*";
        if (lowerName.endsWith(".mkv")) mime = "video/x-matroska";
        else if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) mime = "video/mp4";
        else if (lowerName.endsWith(".avi")) mime = "video/x-msvideo";
        else if (lowerName.endsWith(".webm")) mime = "video/webm";
        else if (lowerName.endsWith(".mov")) mime = "video/quicktime";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TOTAL TADER");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri destination = null;
        try {
            destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) return false;

            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new java.io.IOException("تعذر فتح ملف التنزيلات");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(destination, ready, null, null);
            return true;
        } catch (Exception e) {
            if (destination != null) {
                try { getContentResolver().delete(destination, null, null); } catch (Exception ignored) { }
            }
            return false;
        }
    }

    private void closeDownloadDialog() {
        if (downloadDialog != null) {
            if (downloadDialog.isShowing()) downloadDialog.dismiss();
            downloadDialog = null;
        }
    }

    private void showDownloads() {
        File[] files = downloadsDirectory().listFiles((dir, name) -> !name.endsWith(".part") && !name.endsWith(".tmp"));
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this).setTitle("تحميلاتي").setMessage("لا توجد أفلام محمّلة حتى الآن. اضغط مطولًا على عنصر في القائمة واختر تنزيل.").setPositiveButton("حسنًا", null).show();
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[files.length];
        for (int i = 0; i < files.length; i++) labels[i] = files[i].getName() + "  •  " + android.text.format.Formatter.formatShortFileSize(this, files[i].length());
        new AlertDialog.Builder(this).setTitle("تحميلاتي").setItems(labels, (dialog, which) -> {
            File chosen = files[which];
            new AlertDialog.Builder(this).setTitle(chosen.getName()).setItems(new String[]{"تشغيل دون إنترنت", "حذف"}, (d, action) -> {
                if (action == 0) {
                    activeChannelName = chosen.getName();
                    activeChannelUrl = Uri.fromFile(chosen).toString();
                    final int generation = ++playbackGeneration;
                    status.setText("تشغيل محلي: " + activeChannelName);
                    if (player == null) {
                        player = new ExoPlayer.Builder(this).build();
                        playerView.setPlayer(player);
                    }
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(chosen)));
                    player.prepare();
                    player.play();
                } else if (chosen.delete()) {
                    Toast.makeText(this, "تم حذف الملف", Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this, "تعذر حذف الملف", Toast.LENGTH_SHORT).show();
            }).show();
        }).show();
    }

    private void showQualitySelector() {
        if (player == null) {
            Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        new TrackSelectionDialogBuilder(
                this, "اختيار الجودة", player, C.TRACK_TYPE_VIDEO)
                .setAllowAdaptiveSelections(true)
                .setShowDisableOption(false)
                .build()
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;

        if (fullscreen) {
            topBar.setVisibility(View.GONE);
            channelList.setVisibility(View.GONE);
            findViewById(R.id.controlBar).setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            sectionTitle.setVisibility(View.GONE);

            ViewGroup.LayoutParams c = playerContainer.getLayoutParams();
            c.height = ViewGroup.LayoutParams.MATCH_PARENT;
            playerContainer.setLayoutParams(c);

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

        } else {
            topBar.setVisibility(View.VISIBLE);
            channelList.setVisibility(View.VISIBLE);
            findViewById(R.id.controlBar).setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);
            sectionTitle.setVisibility(View.VISIBLE);

            ViewGroup.LayoutParams c = playerContainer.getLayoutParams();
            c.height = dp(230);
            playerContainer.setLayoutParams(c);

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) toggleFullscreen();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        downloadCancelled = true;
        if (downloadThread != null) downloadThread.interrupt();
        closeDownloadDialog();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }


    private void showAudioTrackSelector() {
        if (player == null) return;
        new TrackSelectionDialogBuilder(
                this,
                "اختيار الصوت",
                player,
                C.TRACK_TYPE_AUDIO
        ).setAllowAdaptiveSelections(false)
         .setShowDisableOption(false)
         .build()
         .show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }
}
