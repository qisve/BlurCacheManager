package com.blur.cache;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public class XposedHook implements IXposedHookLoadPackage {
    private static final String TAG = "BlurCacheXposed";
    private static final String CACHE_DIR_PATH = "/sdcard/BlurCache";
    private static boolean isGenerating = false;
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": 模块已加载，hook 壁纸变更");
        // Hook WallpaperManager.setBitmap
        XposedHelpers.findAndHookMethod(
            "android.app.WallpaperManager",
            lpparam.classLoader,
            "setBitmap",
            Bitmap.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bitmap bitmap = (Bitmap) param.args[0];
                    if (bitmap != null) {
                        XposedBridge.log(TAG + ": 检测到壁纸变更 (setBitmap)");
                        triggerGenerate(bitmap);
                    }
                }
            }
        );
        // Hook WallpaperManager.setStream
        XposedHelpers.findAndHookMethod(
            "android.app.WallpaperManager",
            lpparam.classLoader,
            "setStream",
            InputStream.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + ": 检测到壁纸变更 (setStream)");
                    triggerGenerateFromFile();
                }
            }
        );
        // Hook WallpaperManager.setResource
        XposedHelpers.findAndHookMethod(
            "android.app.WallpaperManager",
            lpparam.classLoader,
            "setResource",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + ": 检测到壁纸变更 (setResource)");
                    triggerGenerateFromFile();
                }
            }
        );
        // 开机时检查缓存是否就绪
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    String processName = Application.getProcessName(context);
                    if (processName == null || !processName.equals("android")) return;
                    // 延迟检查，等系统完全启动
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        checkAndGenerateOnBoot(context);
                    }, 30000);
                }
            }
        );
    }
    private void triggerGenerate(Bitmap bitmap) {
        if (isGenerating) {
            XposedBridge.log(TAG + ": 正在生成中，跳过");
            return;
        }
        isGenerating = true;
        new Thread(() -> {
            try {
                Context context = getSystemContext();
                if (context == null) {
                    XposedBridge.log(TAG + ": 无法获取 Context");
                    return;
                }
                File cacheDir = new File(CACHE_DIR_PATH);
                if (!cacheDir.exists()) cacheDir.mkdirs();
                generateCache(context, bitmap, cacheDir);
                XposedBridge.log(TAG + ": 缓存生成完成");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 生成失败: " + e.getMessage());
            } finally {
                isGenerating = false;
            }
        }).start();
    }
    private void triggerGenerateFromFile() {
        if (isGenerating) return;
        isGenerating = true;
        new Thread(() -> {
            try {
                Thread.sleep(500);
                Context context = getSystemContext();
                if (context == null) return;
                Bitmap bitmap = readWallpaperFile();
                if (bitmap != null) {
                    File cacheDir = new File(CACHE_DIR_PATH);
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    generateCache(context, bitmap, cacheDir);
                    XposedBridge.log(TAG + ": 缓存生成完成 (from file)");
                }
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 生成失败: " + e.getMessage());
            } finally {
                isGenerating = false;
            }
        }).start();
    }
    private void checkAndGenerateOnBoot(Context context) {
        if (isGenerating) return;
        File hashFile = new File(CACHE_DIR_PATH, "/wallpaper_hash");
        if (hashFile.exists() && hashFile.length() > 0) {
            XposedBridge.log(TAG + ": 开机缓存已就绪");
            return;
        }
        XposedBridge.log(TAG + ": 开机缓存未就绪，开始生成");
        Bitmap bitmap = readWallpaperFile();
        if (bitmap != null) {
            File cacheDir = new File(CACHE_DIR_PATH);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            isGenerating = true;
            new Thread(() -> {
                try {
                    generateCache(context, bitmap, cacheDir);
                    XposedBridge.log(TAG + ": 开机缓存生成完成");
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": 开机生成失败: " + e.getMessage());
                } finally {
                    isGenerating = false;
                }
            }).start();
        }
    }
    private void generateCache(Context context, Bitmap wallpaper, File cacheDir) {
        long start = System.currentTimeMillis();
        generateSet(wallpaper, "light", cacheDir);
        Bitmap darkCopy = wallpaper.copy(wallpaper.getConfig(), true);
        adjustBrightness(darkCopy, 0.55f);
        generateSet(darkCopy, "dark", cacheDir);
        darkCopy.recycle();
        String hash = computeHash(wallpaper);
        try {
            java.io.FileWriter fw = new java.io.FileWriter(new File(cacheDir, "wallpaper_hash"));
            fw.write(hash);
            fw.close();
        } catch (Exception e) {
            XposedBridge.log(TAG + ": hash 写入失败: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        XposedBridge.log(TAG + ": 生成完成 " + elapsed + "ms");
    }
    private void generateSet(Bitmap source, String prefix, File cacheDir) {
        int[][] config = {
            {4, 30},   // capsule: 1/4 缩放, radius 30
            {3, 20},   // notify:  1/3 缩放, radius 20
            {3, 25},   // volume:  1/3 缩放, radius 25
            {3, 15},   // recent:  1/3 缩放, radius 15
            {4, 20},   // statusbar: 1/4 缩放, radius 20
        };
        String[] names = {"capsule", "notify", "volume", "recent", "statusbar"};
        int w = source.getWidth();
        int h = source.getHeight();
        for (int i = 0; i < config.length; i++) {
            int scale = config[i][0];
            int radius = config[i][1];
            int tw = Math.max(1, w / scale);
            int th = Math.max(1, h / scale);
            Bitmap scaled = Bitmap.createScaledBitmap(source, tw, th, true);
            Bitmap blurred = scaled.copy(Bitmap.Config.ARGB_8888, true);
            if (scaled != blurred) scaled.recycle();
            stackBlur(blurred, radius);
            stackBlur(blurred, radius / 2);
            String filename = prefix + "_" + names[i] + "_" + radius + ".png";
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(cacheDir, filename));
                blurred.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 保存失败: " + filename);
            }
            blurred.recycle();
        }
    }
    private void adjustBrightness(Bitmap bitmap, float factor) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int a = (pixel >> 24) & 0xff;
            int r = Math.min(255, Math.max(0, (int)(((pixel >> 16) & 0xff) * factor)));
            int g = Math.min(255, Math.max(0, (int)(((pixel >> 8) & 0xff) * factor)));
            int b = Math.min(255, Math.max(0, (int)((pixel & 0xff) * factor)));
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    private String computeHash(Bitmap bitmap) {
        try {
            int w = Math.min(bitmap.getWidth(), 64);
            int h = Math.min(bitmap.getHeight(), 64);
            Bitmap thumb = Bitmap.createScaledBitmap(bitmap, w, h, true);
            int[] pixels = new int[w * h];
            thumb.getPixels(pixels, 0, w, 0, 0, w, h);
            thumb.recycle();
            StringBuilder sb = new StringBuilder();
            for (int p : pixels) sb.append(p);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) { return String.valueOf(System.currentTimeMillis()); }
    }
    private void stackBlur(Bitmap bitmap, int radius) {
        if (radius < 1) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        int div = radius + radius + 1;
        int[] dv = new int[256 * div];
        for (int i = 0; i < dv.length; i++) dv[i] = i / div;
        int[] vMin = new int[Math.max(w, h)];
        int rSum, gSum, bSum, x, y, i, p, yp, yi, yw;
        yw = yi = 0;
        for (y = 0; y < h; y++) {
            rSum = gSum = bSum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pixels[yi + Math.min(w - 1, Math.max(i, 0))];
                rSum += (p & 0xff0000) >> 16;
                gSum += (p & 0x00ff00) >> 8;
                bSum += p & 0x0000ff;
            }
            for (x = 0; x < w; x++) {
                pixels[yi] = 0xff000000 | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];
                if (y == 0) vMin[x] = Math.min(x + radius + 1, w - 1);
                p = pixels[yw + vMin[x]];
                rSum += ((p & 0xff0000) >> 16) - ((pixels[yi] & 0xff0000) >> 16);
                gSum += ((p & 0x00ff00) >> 8) - ((pixels[yi] & 0x00ff00) >> 8);
                bSum += (p & 0x0000ff) - (pixels[yi] & 0x0000ff);
                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rSum = gSum = bSum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                rSum += (pixels[yi] & 0xff0000) >> 16;
                gSum += (pixels[yi] & 0x00ff00) >> 8;
                bSum += pixels[yi] & 0x0000ff;
                yp += w;
            }
            yi = x;
            for (y = 0; y < h; y++) {
                pixels[yi] = 0xff000000 | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];
                if (x == 0) vMin[y] = Math.min(y + radius + 1, h - 1) * w;
                p = x + vMin[y];
                rSum += ((pixels[p] & 0xff0000) >> 16) - ((pixels[yi] & 0xff0000) >> 16);
                gSum += ((pixels[p] & 0x00ff00) >> 8) - ((pixels[yi] & 0x00ff00) >> 8);
                bSum += (pixels[p] & 0x0000ff) - (pixels[yi] & 0x0000ff);
                yi += w;
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    private Bitmap readWallpaperFile() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"cat", "/data/system/users/0/wallpaper_orig"});
            InputStream is = proc.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            proc.waitFor();
            return bitmap;
        } catch (Exception e) { return null; }
    }
    private Context getSystemContext() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Exception e) { return null; }
    }
    // 内部使用 Application 获取进程名
    private static class Application {
        static String getProcessName(Context context) {
            try {
                return (String) Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName")
                    .invoke(null);
            } catch (Exception e) { return null; }
        }
    }
}
