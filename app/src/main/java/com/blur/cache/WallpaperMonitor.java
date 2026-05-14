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
    private static final String WALLPAPER_DIR = "/data/system/users/0";
    private static final long DEBOUNCE_MS = 3000;
    private static FileObserver fileObserver;
    private static Handler handler;
    private static Runnable pendingRunnable;

    public static void start(Context context) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (fileObserver != null) return;
        File dir = new File(WALLPAPER_DIR);
        if (!dir.exists()) {
            Log.w(TAG, "壁纸目录不存在");
            return;
        }
        int mask = FileObserver.MODIFY | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE;
        fileObserver = new FileObserver(WALLPAPER_DIR, mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.startsWith("wallpaper")) {
                    Log.i(TAG, "检测到壁纸文件变更: " + path + " event=" + event);
                    scheduleGenerate(context.getApplicationContext());
                }
            }
        };
        fileObserver.startWatching();
        Log.i(TAG, "FileObserver 已启动，监听: " + WALLPAPER_DIR);
    }

    public static void stop() {
        if (fileObserver != null) {
            fileObserver.stopWatching();
            fileObserver = null;
        }
        if (pendingRunnable != null && handler != null) {
            handler.removeCallbacks(pendingRunnable);
        }
    }

    private static void scheduleGenerate(Context context) {
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

    // 广播接收器作为备用
    public static class WallpaperReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "收到壁纸变更广播");
            scheduleGenerate(context.getApplicationContext());
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            Log.i(TAG, "开机完成");
            start(context);
            new Thread(() -> {
                try {
                    Thread.sleep(15000);
                    if (!BlurCacheGenerator.isReady()) {
                        Log.i(TAG, "缓存未就绪，开始生成");
                        Bitmap bitmap = readWallpaper(context);
                        if (bitmap != null) {
                            CacheConfig.ensureDir();
                            BlurCacheGenerator.generateSync(context, bitmap);
                        }
                    } else {
                        Bitmap bitmap = readWallpaper(context);
                        if (bitmap != null) {
                            String currentHash = BlurCacheGenerator.computeHash(bitmap);
                            String cachedHash = BlurCacheGenerator.getCachedHash();
                            if (!currentHash.equals(cachedHash)) {
                                Log.i(TAG, "壁纸已变更，重新生成");
                                CacheConfig.ensureDir();
                                BlurCacheGenerator.generateSync(context, bitmap);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "开机检查失败", e);
                }
            }).start();
        }
    }
}
