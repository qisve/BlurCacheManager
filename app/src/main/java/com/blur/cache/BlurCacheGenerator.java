package com.blur.cache;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.security.MessageDigest;
public class BlurCacheGenerator {
    private static final String TAG = "BlurCache";
    public interface Callback {
        void onProgress(String message);
        void onSuccess(long elapsedMs, int fileCount);
        void onError(String error);
    }
    static class BlurConfig {
        String component; float scaleFactor; int blurRadius;
        BlurConfig(String c, float s, int b) { component = c; scaleFactor = s; blurRadius = b; }
    }
    private static final BlurConfig[] CONFIGS = {
        new BlurConfig("capsule",   1.0f / 4, 30),
        new BlurConfig("notify",    1.0f / 3, 20),
        new BlurConfig("volume",    1.0f / 3, 25),
        new BlurConfig("recent",    1.0f / 3, 15),
        new BlurConfig("statusbar", 1.0f / 4, 20),
    };
    // 异步生成（App 界面调用）
    public static void generate(Context context, Bitmap wallpaper, Callback callback) {
        new Thread(() -> {
            try {
                CacheConfig.ensureDir();
                long start = SystemClock.elapsedRealtime();
                callback.onProgress("生成亮色缓存...");
                int count = 0;
                count += generateSet(wallpaper, "light", CacheConfig.CACHE_DIR, callback);
                callback.onProgress("生成深色缓存...");
                Bitmap darkCopy = wallpaper.copy(wallpaper.getConfig(), true);
                BrightnessAdjuster.adjust(darkCopy, 0.55f);
                count += generateSet(darkCopy, "dark", CacheConfig.CACHE_DIR, callback);
                darkCopy.recycle();
                callback.onProgress("计算壁纸 Hash...");
                writeHash(wallpaper);
                count++;
                long elapsed = SystemClock.elapsedRealtime() - start;
                callback.onSuccess(elapsed, count);
            } catch (Exception e) {
                Log.e(TAG, "生成失败", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
    // 同步生成（广播接收器调用）
    public static void generateSync(Context context, Bitmap wallpaper) {
        try {
            CacheConfig.ensureDir();
            long start = SystemClock.elapsedRealtime();
            int count = 0;
            count += generateSet(wallpaper, "light", CacheConfig.CACHE_DIR, null);
            Bitmap darkCopy = wallpaper.copy(wallpaper.getConfig(), true);
            BrightnessAdjuster.adjust(darkCopy, 0.55f);
            count += generateSet(darkCopy, "dark", CacheConfig.CACHE_DIR, null);
            darkCopy.recycle();
            writeHash(wallpaper);
            count++;
            long elapsed = SystemClock.elapsedRealtime() - start;
            Log.i(TAG, "缓存生成完成: " + elapsed + "ms, " + count + " 文件");
        } catch (Exception e) {
            Log.e(TAG, "生成失败", e);
        }
    }
    private static int generateSet(Bitmap source, String prefix, File cacheDir, Callback callback) {
        int screenW = source.getWidth();
        int screenH = source.getHeight();
        int count = 0;
        for (BlurConfig config : CONFIGS) {
            if (callback != null) callback.onProgress("  " + prefix + "_" + config.component + "...");
            int targetW = Math.max(1, (int)(screenW * config.scaleFactor));
            int targetH = Math.max(1, (int)(screenH * config.scaleFactor));
            Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);
            Bitmap blurred = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != blurred) scaled.recycle();
            StackBlur.blur(blurred, config.blurRadius);
            StackBlur.blur(blurred, Math.max(1, config.blurRadius / 2));
            String filename = prefix + "_" + config.component + "_" + config.blurRadius + ".png";
            File outFile = new File(cacheDir, filename);
            try {
                FileOutputStream fos = new FileOutputStream(outFile);
                blurred.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                count++;
            } catch (Exception e) {
                Log.e(TAG, "保存失败: " + filename, e);
            }
            blurred.recycle();
        }
        return count;
    }
    private static void writeHash(Bitmap bitmap) {
        try {
            String hash = computeHash(bitmap);
            FileWriter fw = new FileWriter(new File(CacheConfig.CACHE_DIR, "wallpaper_hash"));
            fw.write(hash);
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "hash 写入失败", e);
        }
    }
    public static String computeHash(Bitmap bitmap) {
        try {
            int w = Math.min(bitmap.getWidth(), 64);
            int h = Math.min(bitmap.getHeight(), 64);
            Bitmap thumb = Bitmap.createScaledBitmap(bitmap, w, h, true);
            int[] pixels = new int[w * h];
            thumb.getPixels(pixels, 0, w, 0, 0, w, h);
            thumb.recycle();
            StringBuilder sb = new StringBuilder();
            for (int p : pixels) sb.append(p);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) { return String.valueOf(System.currentTimeMillis()); }
    }
    public static long getCacheSize() {
        long size = 0;
        File dir = CacheConfig.CACHE_DIR;
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) size += f.length();
        }
        return size;
    }
    public static String getCachedHash() {
        try {
            File hashFile = new File(CacheConfig.CACHE_DIR, "wallpaper_hash");
            if (!hashFile.exists()) return "";
            java.util.Scanner s = new java.util.Scanner(hashFile).useDelimiter("\\A");
            return s.hasNext() ? s.next().trim() : "";
        } catch (Exception e) { return ""; }
    }
    public static boolean isReady() {
        File hashFile = new File(CacheConfig.CACHE_DIR, "wallpaper_hash");
        return hashFile.exists() && hashFile.length() > 0;
    }
}
