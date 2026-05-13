package com.blur.cache;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.util.Log;
import java.io.InputStream;
public class BlurCacheService extends Service {
    private static final String TAG = "BlurCache";
    private static final String CHANNEL_ID = "blur_cache_channel";
    private static final int NOTIFICATION_ID = 1001;
    private WallpaperMonitor monitor;
    private boolean isFirstRun = true;
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "服务创建");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("监听壁纸变更中..."));
        monitor = new WallpaperMonitor(this, new BlurCacheGenerator.Callback() {
            @Override public void onProgress(String message) { Log.d(TAG, message); updateNotification(message); }
            @Override public void onSuccess(long elapsedMs, int fileCount) { String msg = "就绪 (" + fileCount + " 文件, " + elapsedMs + "ms)"; Log.i(TAG, msg); updateNotification(msg); }
            @Override public void onError(String error) { Log.e(TAG, "失败: " + error); updateNotification("失败: " + error); }
        });
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "WALLPAPER_CHANGED".equals(intent.getAction())) { if (monitor != null) monitor.onWallpaperChanged(); }
        else { if (monitor != null) monitor.start(); if (isFirstRun) { isFirstRun = false; checkAndGenerateIfNeeded(); } }
        return START_STICKY;
    }
    @Override public void onDestroy() { Log.i(TAG, "服务销毁"); if (monitor != null) monitor.stop(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    private void checkAndGenerateIfNeeded() {
        new Thread(() -> {
            try {
                String cachedHash = BlurCacheGenerator.getCachedHash();
                Bitmap current = null;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
                    InputStream is = p.getInputStream();
                    current = BitmapFactory.decodeStream(is);
                    is.close();
                    p.waitFor();
                } catch (Exception e) { Log.e(TAG, "root读取失败", e); }
                if (current == null) return;
                String currentHash = BlurCacheGenerator.computeHash(current);
                if (cachedHash.equals(currentHash) && BlurCacheReader.isReady()) { Log.i(TAG, "缓存已就绪"); updateNotification("缓存就绪"); }
                else { Log.i(TAG, "缓存不匹配，开始生成"); BlurCacheGenerator.generate(this, current, new BlurCacheGenerator.Callback() {
                    @Override public void onProgress(String msg) { updateNotification(msg); }
                    @Override public void onSuccess(long ms, int count) { updateNotification("就绪 (" + count + " 文件)"); }
                    @Override public void onError(String err) { updateNotification("失败: " + err); }
                }); }
            } catch (Exception e) { Log.e(TAG, "检查失败", e); }
        }).start();
    }
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "BlurCache", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID).setContentTitle("BlurCache").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true).build();
    }
    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text)); }
}
