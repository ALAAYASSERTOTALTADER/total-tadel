package com.example.yacinestylebackup;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.URLUtil;

public final class MovieDownloader {

    private MovieDownloader() {}

    public static long download(
            Context context,
            String url,
            String fileName
    ) {
        if (context == null || url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("رابط الفيلم غير صالح");
        }

        Uri uri = Uri.parse(url.trim());
        String scheme = uri.getScheme();

        if (!("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "رابط التنزيل يجب أن يبدأ بـ http أو https"
            );
        }

        String safeName = URLUtil.guessFileName(
                url, null, null
        );

        if (fileName != null && !fileName.trim().isEmpty()) {
            safeName = fileName.trim()
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
        }

        if (safeName == null || safeName.trim().isEmpty()) {
            safeName = "movie_" + System.currentTimeMillis();
        }

        DownloadManager manager = (DownloadManager)
                context.getSystemService(Context.DOWNLOAD_SERVICE);

        if (manager == null) {
            throw new IllegalStateException(
                    "خدمة التنزيل غير متاحة على الجهاز"
            );
        }

        DownloadManager.Request request =
                new DownloadManager.Request(uri);

        request.setTitle(safeName);
        request.setDescription("تنزيل الفيلم");
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        );
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_MOVIES,
                safeName
        );

        return manager.enqueue(request);
    }
}
