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

        loadChannels(selectedCategory);

        channelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);

        channelList.setAdapter(channelAdapter);

        String autoplayName = getIntent().getStringExtra("autoplayName");
        String autoplayUrl = getIntent().getStringExtra("autoplayUrl");
        if (autoplayName != null && autoplayUrl != null) {
            names.clear();
            urls.clear();
            names.add(autoplayName);
            urls.add(autoplayUrl);
            channelAdapter.notifyDataSetChanged();
            currentIndex = 0;
            playCurrent();
        }

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
            names.clear();
            urls.clear();
            currentIndex = -1;
            loadChannels(selectedCategory);
            channelAdapter.notifyDataSetChanged();
            search.setQuery("", false);
            status.setText(names.isEmpty() ? "لا توجد قنوات" : "تم تحديث القنوات • اختر قناة");
        });

        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return true; }
            @Override public boolean onQueryTextChange(String text) {
                filterChannels(text);
                return true;
            }
        });

        if (autoplayName == null || autoplayUrl == null) {
            status.setText(names.isEmpty() ? "لا توجد قنوات في هذا القسم" : "اختر قناة لبدء البث");
        }
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
        String currentCategory = "عام";
        boolean skipNextUrl = false;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getResources().openRawResource(R.raw.channels)))) {

            String line;
            String pendingName = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#EXTINF")) {
                    int comma = line.indexOf(',');
                    pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "قناة";

                    if (isCategoryHeader(pendingName)) {
                        currentCategory = cleanCategory(pendingName);
                        skipNextUrl = true;
                        pendingName = null;
                    } else {
                        skipNextUrl = false;
                    }

                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (skipNextUrl) {
                        skipNextUrl = false;
                        continue;
                    }

                    if (pendingName != null) {
                        boolean categoryOk = wanted == null || wanted.equals(currentCategory);
                        Set<String> favs = prefs.getStringSet("favorite_channels", new HashSet<>());
                        boolean favoriteOk = !favoritesOnly || (favs != null && favs.contains(pendingName));
                        if (categoryOk && favoriteOk) {
                            names.add(pendingName);
                            urls.add(line);
                        }
                        pendingName = null;
                    }
                }
            }

        } catch (Exception ignored) { }
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
        String q = query == null ? "" : query.trim().toLowerCase();
        names.clear();
        urls.clear();
        loadChannels(selectedCategory);
        if (!q.isEmpty()) {
            for (int i = names.size() - 1; i >= 0; i--) {
                if (!names.get(i).toLowerCase().contains(q)) {
                    names.remove(i);
                    urls.remove(i);
                }
            }
        }
        currentIndex = -1;
        channelAdapter.notifyDataSetChanged();
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
