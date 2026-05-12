package com.blur.cache;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.File;
public class BlurCacheReader {
    private static final String TAG = "BlurCache";
    private static final String CACHE_DIR = "/data/adb/blur_cache";
    public static Bitmap get(Context context, String component) {
        String prefix = isDarkMode(context) ? "dark" : "light";
        int radius = getRadius(component);
        String path = CACHE_DIR + "/" + prefix + "_" + component + "_" + radius + ".png";
        Bitmap result = loadBitmap(path);
        if (result == null) { Log.w(TAG, "缓存未命中: " + component); return generateFallback(context); }
        return result;
    }
    public static boolean isReady() {
        File hashFile = new File(CACHE_DIR + "/wallpaper_hash");
        return hashFile.exists() && hashFile.length() > 0;
    }
    private static int getRadius(String component) {
        switch (component) {
            case "capsule": return 25; case "notify": return 15; case "volume": return 20;
            case "recent": return 10; case "statusbar": return 15; default: return 20;
        }
    }
    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    private static Bitmap loadBitmap(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            Bitmap bitmap = BitmapFactory.decodeStream(p.getInputStream());
            p.waitFor();
            return bitmap;
        } catch (Exception e) { return null; }
    }
    private static Bitmap generateFallback(Context context) {
        int color = isDarkMode(context) ? 0x99000000 : 0x99FFFFFF;
        Bitmap fallback = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        fallback.eraseColor(color);
        return fallback;
    }
}
