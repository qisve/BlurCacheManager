package com.blur.cache;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.WallpaperManager;
import java.io.InputStream;
public class WallpaperMonitor {
    private static final String TAG = "BlurCache";

    // 壁纸变更广播接收器（系统触发，不需要后台服务）
    public static class WallpaperReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "收到广播: " + intent.getAction());
            PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // 等壁纸写入完成
                    generateFromWallpaper(context);
                } catch (Exception e) {
                    Log.e(TAG, "广播处理失败", e);
                } finally {
                    result.finish();
                }
            }).start();
        }
    }

    // 开机广播接收器
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            Log.i(TAG, "开机完成，检查缓存");
            PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    Thread.sleep(30000); // 等系统完全启动
                    if (!BlurCacheGenerator.isReady()) {
                        Log.i(TAG, "缓存未就绪，开始生成");
                        generateFromWallpaper(context);
                    } else {
                        // 检查壁纸是否变更
                        Bitmap current = readWallpaper(context);
                        if (current != null) {
                            String currentHash = BlurCacheGenerator.computeHash(current);
                            String cachedHash = BlurCacheGenerator.getCachedHash();
                            if (!currentHash.equals(cachedHash)) {
                                Log.i(TAG, "壁纸已变更，重新生成");
                                generateFromWallpaper(context, current);
                            } else {
                                Log.i(TAG, "缓存就绪，无需更新");
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "开机检查失败", e);
                } finally {
                    result.finish();
                }
            }).start();
        }
    }

    private static void generateFromWallpaper(Context context) {
        generateFromWallpaper(context, null);
    }

    private static void generateFromWallpaper(Context context, Bitmap existing) {
        Bitmap bitmap = existing != null ? existing : readWallpaper(context);
        if (bitmap == null) {
            Log.e(TAG, "无法获取壁纸");
            return;
        }
        CacheConfig.ensureDir();
        BlurCacheGenerator.generateSync(context, bitmap);
    }

    private static Bitmap readWallpaper(Context context) {
        // 方式1: API
        try {
            WallpaperManager wpm = WallpaperManager.getInstance(context);
            BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
            if (drawable != null && drawable.getBitmap() != null) {
                Log.i(TAG, "API 获取壁纸成功");
                return drawable.getBitmap();
            }
        } catch (Exception e) {
            Log.w(TAG, "API 失败: " + e.getMessage());
        }
        // 方式2: root
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
            InputStream is = p.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            p.waitFor();
            if (bitmap != null) {
                Log.i(TAG, "root 获取壁纸成功");
                return bitmap;
            }
        } catch (Exception e) {
            Log.w(TAG, "root 失败: " + e.getMessage());
        }
        return null;
    }
}
