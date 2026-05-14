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
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
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
        startTriggerObserver(context);
        startRootInotify(context);
        Log.i(TAG, "壁纸监听已启动");
    }

    public static void stop() {
        running = false;
        if (triggerObserver != null) {
            triggerObserver.stopWatching();
            triggerObserver = null;
        }
        if (suProcess != null) {
            suProcess.destroy();
            suProcess = null;
        }
        if (pendingRunnable != null && handler != null) {
            handler.removeCallbacks(pendingRunnable);
        }
        Log.i(TAG, "壁纸监听已停止");
    }

    // 监听 /sdcard/BlurCache/.wp_trigger 标记文件
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
        Log.i(TAG, "触发监听已启动");
    }

    // 用 root 的 inotifywait 监听壁纸目录（内核事件驱动，零轮询）
    private static void startRootInotify(Context context) {
        rootWatcherThread = new Thread(() -> {
            try {
                // 检查 inotifywait 是否可用
                Process check = Runtime.getRuntime().exec(new String[]{"su", "-c", "which inotifywait"});
                BufferedReader checkReader = new BufferedReader(new InputStreamReader(check.getInputStream()));
                String inotifyPath = checkReader.readLine();
                check.waitFor();

                if (inotifyPath != null && !inotifyPath.isEmpty()) {
                    // 方式1: inotifywait（零轮询，内核事件驱动）
                    Log.i(TAG, "使用 inotifywait 监听");
                    suProcess = Runtime.getRuntime().exec("su");
                    DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));

                    String triggerPath = CacheConfig.CACHE_DIR.getAbsolutePath() + "/.wp_trigger";
                    // -m 持续监听，-e 仅监听写入完成和移动事件
                    os.writeBytes("inotifywait -m -e close_write,moved_to /data/system/users/0/\n");
                    os.flush();

                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        Log.d(TAG, "inotify: " + line);
                        if (line.contains("wallpaper")) {
                            // 写标记文件触发 app 侧 FileObserver
                            os.writeBytes("echo changed > '" + triggerPath + "'\n");
                            os.flush();
                        }
                    }
                } else {
                    // 方式2: 降级为 stat 轮询（inotifywait 不可用时）
                    Log.i(TAG, "inotifywait 不可用，降级为 stat 轮询（每 5 秒）");
                    suProcess = Runtime.getRuntime().exec("su");
                    DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());

                    String triggerPath = CacheConfig.CACHE_DIR.getAbsolutePath() + "/.wp_trigger";
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
                }
            } catch (Exception e) {
                Log.e(TAG, "root 监听失败", e);
            }
        });
        rootWatcherThread.setDaemon(true);
        rootWatcherThread.start();
    }

    private static void handleWallpaperChanged(Context context) {
        if (pendingRunnable != null && handler != null) {
            handler.removeCallbacks(pendingRunnable);
        }
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
            if (drawable != null && drawable.getBitmap() != null) {
                return drawable.getBitmap();
            }
        } catch (Exception e) {
            Log.w(TAG, "API 获取失败", e);
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
            InputStream is = p.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            p.waitFor();
            if (bitmap != null) return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "root 获取失败", e);
        }
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
                        Log.i(TAG, "缓存未就绪，开始生成");
                        Bitmap bitmap = readWallpaper(context);
                        if (bitmap != null) BlurCacheGenerator.generateSync(context, bitmap);
                    }
                    start(context);
                } catch (Exception e) {
                    Log.e(TAG, "开机处理失败", e);
                }
            }).start();
        }
    }
}
