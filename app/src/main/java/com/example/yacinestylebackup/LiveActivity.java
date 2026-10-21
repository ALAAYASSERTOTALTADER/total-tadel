package com.example.yacinestylebackup;

import android.content.res.Configuration;
import android.webkit.URLUtil;
import android.os.Environment;
import android.content.Context;
import android.app.DownloadManager;

import android.content.SharedPreferences;
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
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private Button downloadMovieButton;

    private ChannelCacheDb channelDb;
    private final ExecutorService channelExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler searchHandler =
            new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private volatile int loadToken = 0;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_live);

        prefs = getSharedPreferences("legend", MODE_PRIVATE);
        channelDb = new ChannelCacheDb(this);
        selectedCategory = getIntent().getStringExtra("category");
        favoritesOnly = getIntent().getBooleanExtra("favoritesOnly", false);
        String incomingSearchQuery = getIntent().getStringExtra("searchQuery");
        if (incomingSearchQuery != null && !incomingSearchQuery.trim().isEmpty()) {
            currentQuery = incomingSearchQuery.trim();
            selectedCategory = null;
        }

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
        SearchView search = findViewById(R.id.searchChannels);
        if (!currentQuery.isEmpty()) search.setQuery(currentQuery, false);
        downloadMovieButton = findViewById(R.id.downloadMovieButton);
        downloadMovieButton.setOnClickListener(v -> downloadCurrentMovie());

        sectionTitle.setText(favoritesOnly ? "⭐ المفضلة" : (selectedCategory == null ? "📺 كل القنوات" : ("__EGYPTIAN_CHANNELS__".equals(selectedCategory) ? "🇪🇬 القنوات المصرية" : "📁 " + selectedCategory)));

        channelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);
        channelList.setAdapter(channelAdapter);
        updateMovieDownloadVisibility();

        String autoplayName = getIntent().getStringExtra("autoplayName");
        String autoplayUrl = getIntent().getStringExtra("autoplayUrl");
        if (autoplayName != null && autoplayUrl != null) {
            names.clear();
            urls.clear();
            names.add(autoplayName);
            urls.add(autoplayUrl);
            channelAdapter.notifyDataSetChanged();
            currentIndex = 0;
            updateMovieDownloadVisibility();
            playCurrent();
        } else {
            loadChannels(selectedCategory, false);
        }

        channelList.setOnItemClickListener((a, v, pos, id) -> {
            if (pos < urls.size()) {
                currentIndex = pos;
                retryCount = 0;
                updateMovieDownloadVisibility();
                playCurrent();
            }
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
            currentQuery = "";
            search.setQuery("", false);
            loadChannels(selectedCategory, true);
        });

        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return true; }
            @Override public boolean onQueryTextChange(String text) {
                filterChannels(text);
                return true;
            }
        });

        if (autoplayName == null || autoplayUrl == null) {
            loading.setVisibility(View.GONE);
            status.setText(names.isEmpty() ? "" : "اختر قناة لبدء البث");
        }
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

    private void loadChannels(String wanted, boolean forceRefresh) {
        final int token = ++loadToken;
        final String query = currentQuery;
        final Set<String> favoriteNames = new HashSet<>(
                prefs.getStringSet("favorite_channels", new HashSet<>()));

        boolean searching = query != null && !query.trim().isEmpty();
        if (forceRefresh || !searching) {
            loading.setVisibility(View.VISIBLE);
            status.setText(forceRefresh
                    ? "برجاء الانتظار، جاري تحديث قاعدة بيانات القنوات..."
                    : "برجاء الانتظار، جاري تحميل القنوات...");
        } else {
            loading.setVisibility(View.GONE);
            status.setText("جاري البحث: 0%");
        }

        channelExecutor.execute(() -> {
            try {
                channelDb.loadFromM3u(
                        getResources(), R.raw.channels, forceRefresh);

                ChannelCacheDb.SearchProgressListener progressListener = searching
                        ? (percent, scanned, total) -> runOnUiThread(() -> {
                            if (token != loadToken || isFinishing() || isDestroyed()) return;
                            status.setText("جاري البحث: " + percent + "%");
                        })
                        : null;

                ArrayList<ChannelCacheDb.Channel> result =
                        channelDb.getChannels(
                                wanted, query, favoritesOnly, favoriteNames,
                                progressListener);

                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing() || isDestroyed()) return;

                    names.clear();
                    urls.clear();

                    for (ChannelCacheDb.Channel channel : result) {
                        names.add(channel.name);
                        urls.add(channel.url);
                    }

                    currentIndex = -1;
                    updateMovieDownloadVisibility();
                    if (channelAdapter != null) {
                        channelAdapter.notifyDataSetChanged();
                    }

                    loading.setVisibility(View.GONE);
                    if (names.isEmpty()) {
                        status.setText(currentQuery.trim().isEmpty()
                                ? ""
                                : "لا توجد نتائج مطابقة");
                    } else {
                        status.setText(currentQuery.trim().isEmpty()
                                ? "تم تحميل " + names.size() + " قناة • اختر قناة"
                                : "نتائج البحث: " + names.size());
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing() || isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                status.setText("تعذر تحميل القنوات");
                    Toast.makeText(this, "فشل تحميل ملف القنوات", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isMovieCategory(String category) {
        if (category == null) return false;
        String value = category.toLowerCase(java.util.Locale.ROOT);
        // Do not expose the file downloader in live TV groups such as "beIN Movies".
        if (value.contains("قنوات مباشرة") || value.contains("live tv")) return false;
        return value.contains("movie") || value.contains("film")
                || value.contains("cinema") || value.contains("أفلام")
                || value.contains("افلام") || value.contains("فيلم")
                || value.contains("سينما") || value.contains("مسلسلات")
                || value.contains("مسلسل") || value.contains("series")
                || value.contains("vod") || value.contains("مسرحيات");
    }

    private boolean isMovieName(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.contains("movie") || value.contains("film")
                || value.contains("cinema") || value.contains("أفلام")
                || value.contains("افلام") || value.contains("فيلم")
                || value.contains("سينما") || value.contains("مسلسل")
                || value.contains("series") || value.contains("vod");
    }

    private boolean isMovieUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        Uri uri = Uri.parse(url.trim());
        String path = uri.getPath();
        if (path == null) path = "";
        String value = path.toLowerCase(java.util.Locale.ROOT);
        return value.contains("/movie/") || value.contains("/movies/")
                || value.contains("/series/") || value.matches(".*\\.(mkv|mp4|avi|mov|webm|mpeg|mpg|m4v|wmv|flv)$");
    }

    private boolean isLiveStreamUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        Uri uri = Uri.parse(url.trim());
        String path = uri.getPath();
        if (path == null) path = "";
        String value = path.toLowerCase(java.util.Locale.ROOT);
        return value.contains("/live/") || value.endsWith(".ts")
                || value.endsWith(".m3u8") || value.endsWith(".mpd");
    }

    private void updateMovieDownloadVisibility() {
        if (downloadMovieButton == null) return;
        downloadMovieButton.setVisibility(View.VISIBLE);
    }

    private void downloadCurrentMovie() {
        if (currentIndex < 0 || currentIndex >= urls.size() || currentIndex >= names.size()) {
            Toast.makeText(this, "اختَر الفيلم أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        String movieName = names.get(currentIndex);
        String movieUrl = urls.get(currentIndex).trim();
        if (isLiveStreamUrl(movieUrl)) {
            Toast.makeText(this, "ده رابط بث مباشر، مش ملف فيلم للتنزيل", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isMovieCategory(selectedCategory) && !isMovieName(movieName) && !isMovieUrl(movieUrl)) {
            Toast.makeText(this, "التنزيل متاح للأفلام والمسلسلات فقط", Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri = Uri.parse(movieUrl);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            Toast.makeText(this, "رابط الفيلم غير صالح للتنزيل", Toast.LENGTH_LONG).show();
            return;
        }

        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m3u") || path.endsWith(".ts")) {
            Toast.makeText(this, "هذا الرابط بث متجزئ وليس ملف فيديو مباشر؛ لا يمكن حفظه كملف فيلم بهذه الطريقة", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "خدمة التنزيل غير متاحة على الجهاز", Toast.LENGTH_LONG).show();
            return;
        }

        String extension = "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\.(mkv|mp4|avi|mov|webm|mpeg|mpg|m4v|wmv|flv)$")
                .matcher(path);
        if (matcher.find()) {
            extension = "." + matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        } else {
            String guessed = URLUtil.guessFileName(movieUrl, null, null);
            java.util.regex.Matcher guessedMatcher = java.util.regex.Pattern
                    .compile("(?i)\\.(mkv|mp4|avi|mov|webm|mpeg|mpg|m4v|wmv|flv)$")
                    .matcher(guessed == null ? "" : guessed);
            if (guessedMatcher.find()) {
                extension = "." + guessedMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (extension.isEmpty()) extension = ".mp4";

        String baseName = movieName == null ? "" : movieName.trim();
        baseName = baseName.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)\\.(mkv|mp4|avi|mov|webm|mpeg|mpg|m4v|wmv|flv)$", "")
                .trim();
        if (baseName.isEmpty()) baseName = "movie";
        // A timestamp prevents a second download of the same title from colliding with an existing file.
        String fileName = baseName + "_" + System.currentTimeMillis() + extension;

        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(movieName == null || movieName.trim().isEmpty() ? "تنزيل الفيلم" : movieName);
            request.setDescription("جاري حفظ الفيلم في مجلد Downloads");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            long downloadId = manager.enqueue(request);
            if (downloadId > 0) {
                Toast.makeText(this, "بدأ تنزيل الفيلم؛ ستجد الملف في مجلد Downloads", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "لم يبدأ التنزيل؛ جرّب الرابط مرة أخرى", Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException ex) {
            Toast.makeText(this, "تعذّر بدء التنزيل. تأكد أن الرابط مباشر ويشير إلى ملف فيديو", Toast.LENGTH_LONG).show();
        }
    }

    private void playCurrent() {
        if (currentIndex < 0 || currentIndex >= urls.size()) {
            Toast.makeText(this, "اختر قناة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        status.setText("جاري الاتصال: " + names.get(currentIndex));
        loading.setVisibility(View.VISIBLE);

        if (player != null) player.release();

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    loading.setVisibility(View.VISIBLE);
                    status.setText("جاري تحميل البث...");
                } else if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    status.setText("مباشر الآن • " + names.get(currentIndex));
                    retryCount = 0;
                    prefs.edit()
                            .putString("last_channel_name", names.get(currentIndex))
                            .putString("last_channel_url", urls.get(currentIndex))
                            .apply();
                } else if (state == Player.STATE_ENDED) {
                    loading.setVisibility(View.VISIBLE);
                    status.setText("انقطع البث • إعادة الاتصال تلقائيًا...");
                    handler.postDelayed(() -> { if (!isFinishing()) playCurrent(); }, 2000);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                loading.setVisibility(View.GONE);

                retryCount++;
                status.setText("انقطع البث • إعادة اتصال تلقائي " + retryCount);
                handler.postDelayed(() -> {
                    if (!isFinishing()) playCurrent();
                }, Math.min(5000, 1500 + (retryCount * 500L)));
            }
        });

        player.setMediaItem(MediaItem.fromUri(Uri.parse(urls.get(currentIndex))));
        player.prepare();
        player.play();
    }

    private void filterChannels(String query) {
        currentQuery = query == null ? "" : query;

        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
        }

        pendingSearch = () -> loadChannels(selectedCategory, false);
        searchHandler.postDelayed(pendingSearch, 120);
    }

    private void toggleFavorite() {
        if (currentIndex < 0 || currentIndex >= names.size()) {
            Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> saved = new HashSet<>(
                prefs.getStringSet("favorite_channels", new HashSet<>()));

        String name = names.get(currentIndex);

        if (saved.contains(name)) {
            saved.remove(name);
            Toast.makeText(this, "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show();
        } else {
            saved.add(name);
            Toast.makeText(this, "تمت الإضافة للمفضلة ⭐", Toast.LENGTH_SHORT).show();
        }

        prefs.edit().putStringSet("favorite_channels", saved).apply();
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
        handler.removeCallbacksAndMessages(null);
        searchHandler.removeCallbacksAndMessages(null);
        channelExecutor.shutdownNow();
        if (player != null) player.release();
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
