package com.blur.cache;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.WallpaperManager;
import java.io.File;
import java.io.InputStream;
public class WallpaperMonitor {
    private static final String TAG = "BlurCache";
    private static final long DEBOUNCE_MS = 3000;
    private static FileObserver triggerObserver;
    private static Thread rootWatcherThread;
    private static Handler handler;
    private static Runnable pendingRunnable;
    private static volatile boolean running = false;
    private static Process suProcess;

    public static void start(Context context) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (running) return;
        running = true;
        CacheConfig.ensureDir();
        startTriggerObserver(context);
        startRootInotify(context);
        Log.i(TAG, "壁纸监听已启动");
    }

    public static void stop() {
        running = false;
        if (triggerObserver != null) { triggerObserver.stopWatching(); triggerObserver = null; }
        if (suProcess != null) { suProcess.destroy(); suProcess = null; }
        if (pendingRunnable != null && handler != null) handler.removeCallbacks(pendingRunnable);
        Log.i(TAG, "壁纸监听已停止");
    }

    private static void startTriggerObserver(Context context) {
        File watchDir = CacheConfig.CACHE_DIR;
        if (!watchDir.exists()) watchDir.mkdirs();
        triggerObserver = new FileObserver(watchDir.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.MODIFY | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (".wp_trigger".equals(path)) {
                    Log.i(TAG, "检测到壁纸变更标记");
                    handleWallpaperChanged(context.getApplicationContext());
                }
            }
        };
        triggerObserver.startWatching();
        Log.i(TAG, "触发文件监听已启动");
    }

    private static void startRootInotify(Context context) {
        rootWatcherThread = new Thread(() -> {
            try {
                String triggerPath = CacheConfig.CACHE_DIR.getAbsolutePath() + "/.wp_trigger";
                // 创建触发脚本到 /data/adb/（root 可写可执行）
                suProcess = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(suProcess.getOutputStream());

                // 写触发脚本
                os.writeBytes("cat > /data/adb/blur_trigger.sh << 'SCRIPTEOF'\n");
                os.writeBytes("#!/system/bin/sh\n");
                os.writeBytes("echo changed > '" + triggerPath + "'\n");
                os.writeBytes("SCRIPTEOF\n");
                os.writeBytes("chmod 755 /data/adb/blur_trigger.sh\n");
                os.flush();

                // 等脚本创建完成
                Thread.sleep(500);

                // 用 inotifyd 监听壁纸文件（内核事件驱动，零轮询）
                // 语法: inotifyd 脚本路径 文件路径:事件
                // w = IN_CLOSE_WRITE（文件写入完成）
                os.writeBytes("inotifyd /data/adb/blur_trigger.sh /data/system/users/0/wallpaper_orig:w\n");
                os.flush();

                Log.i(TAG, "inotifyd 已启动，监听壁纸文件写入事件");

                // 保持 su 进程存活
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(suProcess.getInputStream()));
                String line;
                while (running && (line = reader.readLine()) != null) {
                    Log.d(TAG, "inotifyd: " + line);
                }
            } catch (Exception e) {
                Log.e(TAG, "inotifyd 启动失败，降级为 stat 轮询", e);
                fallbackPolling(context, CacheConfig.CACHE_DIR.getAbsolutePath() + "/.wp_trigger");
            }
        });
        rootWatcherThread.setDaemon(true);
        rootWatcherThread.start();
    }

    // 降级方案：stat 轮询
    private static void fallbackPolling(Context context, String triggerPath) {
        try {
            suProcess = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(suProcess.getOutputStream());
            os.writeBytes("TRIGGER='" + triggerPath + "'\n");
            os.writeBytes("LAST_MTIME=''\n");
            os.writeBytes("while true; do\n");
            os.writeBytes("  sleep 5\n");
            os.writeBytes("  MTIME=$(stat -c %Y /data/system/users/0/wallpaper_orig 2>/dev/null)\n");
            os.writeBytes("  if [ -n \"$MTIME\" ] && [ \"$MTIME\" != \"$LAST_MTIME\" ]; then\n");
            os.writeBytes("    if [ -n \"$LAST_MTIME\" ]; then echo changed > \"$TRIGGER\"; fi\n");
            os.writeBytes("    LAST_MTIME=$MTIME\n");
            os.writeBytes("  fi\n");
            os.writeBytes("done\n");
            os.flush();
            Log.i(TAG, "降级轮询已启动（每 5 秒）");
        } catch (Exception e) {
            Log.e(TAG, "降级轮询也失败", e);
        }
    }

    private static void handleWallpaperChanged(Context context) {
        if (pendingRunnable != null && handler != null) handler.removeCallbacks(pendingRunnable);
        pendingRunnable = () -> {
            Log.i(TAG, "防抖结束，开始生成缓存");
            new Thread(() -> {
                Bitmap bitmap = readWallpaper(context);
                if (bitmap != null) {
                    CacheConfig.ensureDir();
                    BlurCacheGenerator.generateSync(context, bitmap);
                    File trigger = new File(CacheConfig.CACHE_DIR, ".wp_trigger");
                    if (trigger.exists()) trigger.delete();
                    Log.i(TAG, "壁纸变更缓存生成完成");
                } else {
                    Log.e(TAG, "无法获取壁纸");
                }
            }).start();
        };
        handler.postDelayed(pendingRunnable, DEBOUNCE_MS);
    }

    private static Bitmap readWallpaper(Context context) {
        try {
            WallpaperManager wpm = WallpaperManager.getInstance(context);
            BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
            if (drawable != null && drawable.getBitmap() != null) return drawable.getBitmap();
        } catch (Exception e) { Log.w(TAG, "API 获取失败", e); }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
            InputStream is = p.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            p.waitFor();
            if (bitmap != null) return bitmap;
        } catch (Exception e) { Log.w(TAG, "root 获取失败", e); }
        return null;
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            Log.i(TAG, "开机完成");
            CacheConfig.ensureDir();
            new Thread(() -> {
                try {
                    Thread.sleep(15000);
                    if (!BlurCacheGenerator.isReady()) {
                        Bitmap bitmap = readWallpaper(context);
                        if (bitmap != null) BlurCacheGenerator.generateSync(context, bitmap);
                    }
                    start(context);
                } catch (Exception e) { Log.e(TAG, "开机处理失败", e); }
            }).start();
        }
    }
}
