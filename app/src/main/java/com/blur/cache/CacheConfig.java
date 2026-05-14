package com.blur.cache;
import android.os.Environment;
import java.io.File;
public class CacheConfig {
    public static final File CACHE_DIR = new File(Environment.getExternalStorageDirectory(), "BlurCache");
    public static final File CACHE_DIR_LEGACY = new File("/data/adb/blur_cache");
    public static void ensureDir() {
        if (!CACHE_DIR.exists()) CACHE_DIR.mkdirs();
    }
    // 迁移旧缓存
    public static void migrateIfNeeded() {
        if (CACHE_DIR_LEGACY.exists() && CACHE_DIR_LEGACY.listFiles() != null && CACHE_DIR_LEGACY.listFiles().length > 0) {
            if (!CACHE_DIR.exists()) CACHE_DIR.mkdirs();
            for (File f : CACHE_DIR_LEGACY.listFiles()) {
                f.renameTo(new File(CACHE_DIR, f.getName()));
            }
            CACHE_DIR_LEGACY.delete();
        }
    }
}
