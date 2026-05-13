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
    private static final String TMP_DIR = "/data/adb/blur_cache_tmp";
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
        new BlurCacheGenerator.BlurConfig("statusbar", 1.0f / 8, 15),
    };
    // 每次生成前确保 SuShell 打开
    private static void ensureSuShell() {
        if (!SuShell.isAlive()) {
            Log.i(TAG, "SuShell 未打开，正在打开...");
            SuShell.open();
        }
    }
    public static void generate(Context context, Bitmap wallpaper, Callback callback) {
        new Thread(() -> {
            try {
                long start = SystemClock.elapsedRealtime();
                ensureSuShell();
                if (!SuShell.isAlive()) {
                    callback.onError("无法获取 root 权限");
                    return;
                }
                callback.onProgress("创建临时目录...");
                SuShell.exec("mkdir -p " + TMP_DIR);
                // 验证目录创建成功
                List<String> check = SuShell.execWithOutput("ls -d " + TMP_DIR + " && echo OK");
                if (check.isEmpty() || !check.get(check.size()-1).trim().equals("OK")) {
                    callback.onError("无法创建临时目录: " + TMP_DIR);
                    return;
                }
                callback.onProgress("生成亮色缓存...");
                generateSet(wallpaper, "light", callback);
                callback.onProgress("生成深色缓存...");
                Bitmap darkCopy = wallpaper.copy(wallpaper.getConfig(), true);
                BrightnessAdjuster.adjust(darkCopy, 0.4f);
                generateSet(darkCopy, "dark", callback);
                darkCopy.recycle();
                callback.onProgress("计算壁纸 Hash...");
                String hash = computeHash(wallpaper);
                writeTmpFile(TMP_DIR + "/wallpaper_hash", hash);
                // 验证文件生成
                List<String> files = SuShell.execWithOutput("ls " + TMP_DIR + "/ | wc -l");
                int fileCount = Integer.parseInt(files.get(0).trim());
                callback.onProgress("已生成 " + fileCount + " 个文件，开始原子替换...");
                atomicReplace();
                // 验证替换成功
                List<String> verify = SuShell.execWithOutput("ls " + CACHE_DIR + "/ | wc -l");
                int finalCount = Integer.parseInt(verify.get(0).trim());
                long elapsed = SystemClock.elapsedRealtime() - start;
                callback.onSuccess(elapsed, finalCount);
                Log.i(TAG, "缓存生成完成: " + elapsed + "ms, " + finalCount + " 文件");
            } catch (Exception e) {
                Log.e(TAG, "生成失败", e);
                try { SuShell.exec("rm -rf " + TMP_DIR); } catch (Exception ignored) {}
                callback.onError(e.getMessage());
            }
        }).start();
    }
    private static void generateSet(Bitmap source, String prefix, Callback callback) {
        int screenW = source.getWidth();
        int screenH = source.getHeight();
        for (BlurConfig config : CONFIGS) {
            callback.onProgress("  " + prefix + "_" + config.component + "...");
            int targetW = Math.max(1, (int)(screenW * config.scaleFactor));
            int targetH = Math.max(1, (int)(screenH * config.scaleFactor));
            Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);
            Bitmap blurred = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != blurred) scaled.recycle();
            StackBlur.blur(blurred, config.blurRadius);
            String filename = prefix + "_" + config.component + "_" + config.blurRadius + ".png";
            savePng(blurred, TMP_DIR + "/" + filename);
            blurred.recycle();
        }
    }
    private static void savePng(Bitmap bitmap, String path) {
        try {
            File tempFile = File.createTempFile("blur_", ".png");
            FileOutputStream fos = new FileOutputStream(tempFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush(); fos.close();
            String tempPath = tempFile.getAbsolutePath();
            SuShell.exec("cp " + tempPath + " " + path);
            SuShell.exec("chmod 600 " + path);
            tempFile.delete();
            // 验证文件存在
            List<String> check = SuShell.execWithOutput("test -f " + path + " && echo OK");
            if (check.isEmpty() || !check.get(0).trim().equals("OK")) {
                Log.e(TAG, "文件保存失败: " + path);
            }
        } catch (Exception e) { Log.e(TAG, "保存失败: " + path, e); }
    }
    private static void writeTmpFile(String path, String content) {
        try {
            File tempFile = File.createTempFile("hash_", ".txt");
            java.io.FileWriter fw = new java.io.FileWriter(tempFile);
            fw.write(content); fw.close();
            String tempPath = tempFile.getAbsolutePath();
            SuShell.exec("cp " + tempPath + " " + path);
            SuShell.exec("chmod 600 " + path);
            tempFile.delete();
        } catch (Exception e) { Log.e(TAG, "写入失败: " + path, e); }
    }
    private static void atomicReplace() {
        SuShell.exec("mkdir -p " + CACHE_DIR);
        SuShell.exec("rm -f " + CACHE_DIR + "/*.png");
        SuShell.exec("rm -f " + CACHE_DIR + "/wallpaper_hash");
        SuShell.exec("cp " + TMP_DIR + "/* " + CACHE_DIR + "/");
        SuShell.exec("rm -rf " + TMP_DIR);
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
            ensureSuShell();
            List<String> lines = SuShell.execWithOutput("du -sb " + CACHE_DIR + " 2>/dev/null | cut -f1");
            if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) size = Long.parseLong(lines.get(0).trim());
        } catch (Exception e) { Log.e(TAG, "getCacheSize 失败", e); }
        return size;
    }
    public static String getCachedHash() {
        try {
            ensureSuShell();
            List<String> lines = SuShell.execWithOutput("cat " + CACHE_DIR + "/wallpaper_hash 2>/dev/null");
            return lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (Exception e) { return ""; }
    }
    public static boolean isReady() {
        try {
            ensureSuShell();
            List<String> lines = SuShell.execWithOutput("test -f " + CACHE_DIR + "/wallpaper_hash && echo ready");
            return !lines.isEmpty() && lines.get(0).trim().equals("ready");
        } catch (Exception e) { return false; }
    }
}
