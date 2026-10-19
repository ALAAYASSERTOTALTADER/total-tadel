package com.example.yacinestylebackup;

import android.content.res.Configuration;

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
        SearchView search = findViewById(R.id.searchChannels);

        sectionTitle.setText(favoritesOnly ? "⭐ المفضلة" : (selectedCategory == null ? "📺 كل القنوات" : "📁 " + selectedCategory));

        channelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);
        channelList.setAdapter(channelAdapter);

        channelList.setOnItemClickListener((a, v, pos, id) -> {
            if (pos < urls.size()) {
                currentIndex = pos;
                retryCount = 0;
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

        String startupName = getIntent().getStringExtra("autoplayName");
        String startupUrl = getIntent().getStringExtra("autoplayUrl");
        if (startupUrl == null || startupUrl.trim().isEmpty()) {
            startupName = prefs.getString("last_channel_name", null);
            startupUrl = prefs.getString("last_channel_url", null);
        }

        // Start saved playback before parsing the full playlist.
        if (startupUrl != null && !startupUrl.trim().isEmpty()) {
            playChannel(startupName == null ? "آخر قناة" : startupName, startupUrl);
        } else {
            status.setText("جاري تجهيز القنوات...");
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
        if (activeChannelUrl == null) status.setText("جاري تجهيز القنوات...");

        new Thread(() -> {
            ArrayList<String> parsedNames = new ArrayList<>();
            ArrayList<String> parsedUrls = new ArrayList<>();
            ArrayList<String> parsedCategories = new ArrayList<>();
            String category = "عام";
            boolean skipNextUrl = false;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            getResources().openRawResource(R.raw.channels),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                String pendingName = null;

                while ((line = br.readLine()) != null) {
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
                            if (!isValidDisplayName(pendingName)) pendingName = null;
                        }
                    } else if (!line.startsWith("#") && !line.isEmpty()) {
                        if (skipNextUrl) {
                            skipNextUrl = false;
                            continue;
                        }

                        if (pendingName != null) {
                            parsedNames.add(pendingName);
                            parsedUrls.add(line);
                            parsedCategories.add(category);
                            pendingName = null;
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("LiveActivity", "Playlist loading failed", e);
            }

            runOnUiThread(() -> {
                channelsLoading = false;
                if (isFinishing() || isDestroyed()) return;

                allNames.clear();
                allUrls.clear();
                allCategories.clear();
                allNames.addAll(parsedNames);
                allUrls.addAll(parsedUrls);
                allCategories.addAll(parsedCategories);
                channelsLoaded = true;

                applyChannelFilter(wanted);
                if (activeChannelUrl == null) {
                    status.setText(names.isEmpty()
                            ? "لا توجد قنوات في هذا القسم"
                            : "اختر قناة لبدء البث");
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

        status.setText("جاري الاتصال: " + name);
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
                        status.setText("جاري تحميل البث...");
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
