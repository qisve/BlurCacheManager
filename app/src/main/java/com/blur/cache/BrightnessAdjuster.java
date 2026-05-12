package com.blur.cache;
import android.graphics.Bitmap;
public class BrightnessAdjuster {
    public static Bitmap adjust(Bitmap bitmap, float factor) {
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
        return bitmap;
    }
}
