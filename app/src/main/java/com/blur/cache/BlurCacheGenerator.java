package com.blur.cache;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
public class BlurCacheGenerator {
    private static final String TAG = "BlurCache";
    private static final String CACHE_DIR = "/data/adb/blur_cache";
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
        new BlurConfig("capsule", 1.0f / 8, 25),
        new BlurConfig("notify", 1.0f / 4, 15),
        new BlurConfig("volume", 1.0f / 4, 20),
        new BlurConfig("recent", 1.0f / 4, 10),
        new BlurConfig("statusbar", 1.0f / 8, 15),
    };
    public static void generate(Context context, Bitmap wallpaper, Callback callback) {
        new Thread(() -> {
            try {
                long start = SystemClock.elapsedRealtime();
                SuShell.exec("mkdir -p " + CACHE_DIR);
                // 用 app 内部存储做临时目录（不需要 root）
                File tmpDir = new File(context.getCacheDir(), "blur_tmp");
                if (tmpDir.exists()) deleteDir(tmpDir);
                tmpDir.mkdirs();
                callback.onProgress("创建临时目录...");
                callback.onProgress("生成亮色缓存...");
                int count = 0;
                count += generateSet(wallpaper, "light", tmpDir, callback);
                callback.onProgress("生成深色缓存...");
                Bitmap darkCopy = wallpaper.copy(wallpaper.getConfig(), true);
                BrightnessAdjuster.adjust(darkCopy, 0.55f);
                count += generateSet(darkCopy, "dark", tmpDir, callback);
                darkCopy.recycle();
                // 写 hash 文件到临时目录
                callback.onProgress("计算壁纸 Hash...");
                String hash = computeHash(wallpaper);
                File hashFile = new File(tmpDir, "wallpaper_hash");
                java.io.FileWriter fw = new java.io.FileWriter(hashFile);
                fw.write(hash);
                fw.close();
                count++;
                callback.onProgress("共生成 " + count + " 个文件，写入缓存...");
                // 一次性 root 复制到目标目录
                String tmpPath = tmpDir.getAbsolutePath();
                SuShell.exec("rm -f " + CACHE_DIR + "/*.png");
                SuShell.exec("rm -f " + CACHE_DIR + "/wallpaper_hash");
                SuShell.exec("cp " + tmpPath + "/* " + CACHE_DIR + "/");
                SuShell.exec("chmod 644 " + CACHE_DIR + "/*");
                // 验证
                List<String> verify = SuShell.execWithOutput("ls " + CACHE_DIR + "/ 2>/dev/null");
                int finalCount = verify.size();
                // 清理临时目录
                deleteDir(tmpDir);
                long elapsed = SystemClock.elapsedRealtime() - start;
                callback.onSuccess(elapsed, finalCount);
                Log.i(TAG, "缓存生成完成: " + elapsed + "ms, " + finalCount + " 文件");
            } catch (Exception e) {
                Log.e(TAG, "生成失败", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
    private static int generateSet(Bitmap source, String prefix, File tmpDir, Callback callback) {
        int screenW = source.getWidth();
        int screenH = source.getHeight();
        int count = 0;
        for (BlurConfig config : CONFIGS) {
            callback.onProgress("  " + prefix + "_" + config.component + "...");
            int targetW = Math.max(1, (int)(screenW * config.scaleFactor));
            int targetH = Math.max(1, (int)(screenH * config.scaleFactor));
            Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);
            Bitmap blurred = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != blurred) scaled.recycle();
            StackBlur.blur(blurred, config.blurRadius);
            String filename = prefix + "_" + config.component + "_" + config.blurRadius + ".png";
            File outFile = new File(tmpDir, filename);
            try {
                FileOutputStream fos = new FileOutputStream(outFile);
                blurred.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                Log.i(TAG, "已保存: " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
                count++;
            } catch (Exception e) {
                Log.e(TAG, "保存失败: " + filename, e);
            }
            blurred.recycle();
        }
        return count;
    }
    private static void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) for (File child : children) child.delete();
        }
        dir.delete();
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
        try {
            List<String> lines = SuShell.execWithOutput("du -sb " + CACHE_DIR + " 2>/dev/null | cut -f1");
            if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) size = Long.parseLong(lines.get(0).trim());
        } catch (Exception e) { Log.e(TAG, "getCacheSize 失败", e); }
        return size;
    }
    public static String getCachedHash() {
        try {
            List<String> lines = SuShell.execWithOutput("cat " + CACHE_DIR + "/wallpaper_hash 2>/dev/null");
            return lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (Exception e) { return ""; }
    }
    public static boolean isReady() {
        try {
            List<String> lines = SuShell.execWithOutput("test -f " + CACHE_DIR + "/wallpaper_hash && echo ready");
            return !lines.isEmpty() && lines.get(0).trim().equals("ready");
        } catch (Exception e) { return false; }
    }
}
