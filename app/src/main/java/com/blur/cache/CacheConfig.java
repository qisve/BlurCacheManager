package com.blur.cache;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
public class CacheConfig {
    private static final String TAG = "BlurCache";
    public static final File CACHE_DIR = new File(Environment.getExternalStorageDirectory(), "BlurCache");
    public static final File CACHE_DIR_LEGACY = new File("/data/adb/blur_cache");
    public static void ensureDir() {
        if (!CACHE_DIR.exists()) {
            boolean created = CACHE_DIR.mkdirs();
            Log.i(TAG, "创建缓存目录: " + CACHE_DIR.getAbsolutePath() + " → " + created);
        }
        if (!CACHE_DIR.exists()) {
            // 备用方案：用 exec 创建
            try {
                Runtime.getRuntime().exec(new String[]{"mkdir", "-p", CACHE_DIR.getAbsolutePath()}).waitFor();
            } catch (Exception e) {
                Log.e(TAG, "备用创建目录失败", e);
            }
        }
    }
    public static boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return Environment.getExternalStorageDirectory().canWrite();
    }
    public static void migrateIfNeeded() {
        try {
            if (CACHE_DIR_LEGACY.exists() && CACHE_DIR_LEGACY.listFiles() != null && CACHE_DIR_LEGACY.listFiles().length > 0) {
                ensureDir();
                for (File f : CACHE_DIR_LEGACY.listFiles()) {
                    File dest = new File(CACHE_DIR, f.getName());
                    f.renameTo(dest);
                    Log.i(TAG, "迁移: " + f.getName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "迁移失败", e);
        }
    }
}
