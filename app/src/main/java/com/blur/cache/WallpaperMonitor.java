package com.blur.cache;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
public class WallpaperMonitor {
    private static final String TAG = "BlurCache";
    private static final String WALLPAPER_DIR = "/data/system/users/0";
    private static final long DEBOUNCE_MS = 500;
    private FileObserver fileObserver;
    private Handler handler;
    private Runnable pendingRunnable;
    private Context context;
    private BlurCacheGenerator.Callback callback;
    public WallpaperMonitor(Context context, BlurCacheGenerator.Callback callback) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.callback = callback;
    }
    public void start() { Log.i(TAG, "启动壁纸监听"); startFileObserver(); }
    public void stop() {
        Log.i(TAG, "停止壁纸监听");
        if (fileObserver != null) { fileObserver.stopWatching(); fileObserver = null; }
        if (pendingRunnable != null) handler.removeCallbacks(pendingRunnable);
    }
    public void onWallpaperChanged() { scheduleGenerate("广播触发"); }
    private void startFileObserver() {
        File dir = new File(WALLPAPER_DIR);
        if (!dir.exists()) { Log.w(TAG, "壁纸目录不存在"); return; }
        int mask = FileObserver.CREATE | FileObserver.MODIFY | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE;
        fileObserver = new FileObserver(WALLPAPER_DIR, mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.startsWith("wallpaper")) { Log.i(TAG, "壁纸变更: " + path); scheduleGenerate("FileObserver: " + path); }
            }
        };
        fileObserver.startWatching();
        Log.i(TAG, "FileObserver 已启动");
    }
    private void scheduleGenerate(String reason) {
        if (pendingRunnable != null) handler.removeCallbacks(pendingRunnable);
        pendingRunnable = () -> { Log.i(TAG, "触发生成: " + reason); generateFromCurrentWallpaper(); };
        handler.postDelayed(pendingRunnable, DEBOUNCE_MS);
    }
    private void generateFromCurrentWallpaper() {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
                InputStream is = p.getInputStream();
                bitmap = BitmapFactory.decodeStream(is);
                is.close();
                p.waitFor();
            } catch (Exception e) { Log.e(TAG, "root 读取壁纸失败", e); }
            if (bitmap != null) {
                BlurCacheGenerator.generate(context, bitmap, callback);
            } else {
                Log.e(TAG, "无法获取壁纸");
                if (callback != null) callback.onError("无法获取壁纸");
            }
        }).start();
    }
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) { Log.i(TAG, "开机启动服务"); context.startForegroundService(new Intent(context, BlurCacheService.class)); }
        }
    }
    public static class WallpaperReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.WALLPAPER_CHANGED".equals(intent.getAction())) {
                Log.i(TAG, "收到壁纸变更广播");
                Intent svc = new Intent(context, BlurCacheService.class);
                svc.setAction("WALLPAPER_CHANGED");
                context.startForegroundService(svc);
            }
        }
    }
}
