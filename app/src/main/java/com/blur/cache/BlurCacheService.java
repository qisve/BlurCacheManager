package com.blur.cache;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.IBinder;
import android.util.Log;
import android.app.WallpaperManager;
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
        startForeground(NOTIFICATION_ID, buildNotification("初始化中..."));
        CacheConfig.ensureDir();
        CacheConfig.migrateIfNeeded();
        monitor = new WallpaperMonitor(this, new BlurCacheGenerator.Callback() {
            @Override public void onProgress(String message) { Log.d(TAG, message); }
            @Override public void onSuccess(long elapsedMs, int fileCount) {
                updateNotification("就绪 (" + fileCount + " 文件, " + elapsedMs + "ms)");
            }
            @Override public void onError(String error) {
                Log.e(TAG, "失败: " + error);
                updateNotification("失败: " + error);
            }
        });
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "WALLPAPER_CHANGED".equals(intent.getAction())) {
            if (monitor != null) monitor.onWallpaperChanged();
        } else {
            if (monitor != null) monitor.start();
            if (isFirstRun) { isFirstRun = false; checkAndGenerateIfNeeded(); }
        }
        return START_STICKY;
    }
    @Override public void onDestroy() { if (monitor != null) monitor.stop(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    private void checkAndGenerateIfNeeded() {
        new Thread(() -> {
            try {
                if (!CacheConfig.hasStoragePermission()) {
                    updateNotification("需要存储权限");
                    return;
                }
                String cachedHash = BlurCacheGenerator.getCachedHash();
                boolean ready = BlurCacheGenerator.isReady();
                if (ready && !cachedHash.isEmpty()) {
                    Bitmap current = null;
                    try {
                        WallpaperManager wpm = WallpaperManager.getInstance(this);
                        BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
                        if (drawable != null) current = drawable.getBitmap();
                    } catch (Exception e) { Log.e(TAG, "获取壁纸失败", e); }
                    if (current != null) {
                        String currentHash = BlurCacheGenerator.computeHash(current);
                        if (cachedHash.equals(currentHash)) {
                            updateNotification("就绪 (" + getFileCount() + " 文件)");
                            return;
                        }
                    }
                }
                // 需要生成
                Bitmap current = null;
                try {
                    WallpaperManager wpm = WallpaperManager.getInstance(this);
                    BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
                    if (drawable != null) current = drawable.getBitmap();
                } catch (Exception e) { Log.e(TAG, "API 获取失败", e); }
                if (current == null) {
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
                        InputStream is = p.getInputStream();
                        current = BitmapFactory.decodeStream(is);
                        is.close();
                        p.waitFor();
                    } catch (Exception e) { Log.e(TAG, "root 读取失败", e); }
                }
                if (current != null) {
                    BlurCacheGenerator.generate(this, current, new BlurCacheGenerator.Callback() {
                        @Override public void onProgress(String msg) { Log.d(TAG, msg); }
                        @Override public void onSuccess(long ms, int count) { updateNotification("就绪 (" + count + " 文件)"); }
                        @Override public void onError(String err) { updateNotification("失败: " + err); }
                    });
                } else {
                    updateNotification("无法获取壁纸");
                }
            } catch (Exception e) { Log.e(TAG, "检查失败", e); }
        }).start();
    }
    private int getFileCount() {
        java.io.File dir = CacheConfig.CACHE_DIR;
        if (dir.exists()) {
            java.io.File[] files = dir.listFiles();
            return files != null ? files.length : 0;
        }
        return 0;
    }
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "BlurCache", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID).setContentTitle("BlurCache").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true).build();
    }
    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }
}
