package com.blur.cache;
import android.graphics.Bitmap;
public class StackBlur {
    public static Bitmap blur(Bitmap bitmap, int radius) {
        if (radius < 1) return bitmap;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        stackBlur(pixels, w, h, radius);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }
    private static void stackBlur(int[] pix, int w, int h, int radius) {
        int div = radius + radius + 1;
        int divSum = (div + 1) >> 1;
        divSum *= divSum;
        int[] dv = new int[256 * divSum];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divSum;
        int[] vMin = new int[Math.max(w, h)];
        int rSum, gSum, bSum, x, y, i, p, yp, yi, yw;
        yw = yi = 0;
        for (y = 0; y < h; y++) {
            rSum = gSum = bSum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(w - 1, Math.max(i, 0))];
                rSum += (p & 0xff0000) >> 16;
                gSum += (p & 0x00ff00) >> 8;
                bSum += p & 0x0000ff;
            }
            for (x = 0; x < w; x++) {
                pix[yi] = 0xff000000 | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];
                if (y == 0) vMin[x] = Math.min(x + radius + 1, w - 1);
                p = pix[yw + vMin[x]];
                rSum += ((p & 0xff0000) >> 16) - ((pix[yi] & 0xff0000) >> 16);
                gSum += ((p & 0x00ff00) >> 8) - ((pix[yi] & 0x00ff00) >> 8);
                bSum += (p & 0x0000ff) - (pix[yi] & 0x0000ff);
                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rSum = gSum = bSum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                rSum += (pix[yi] & 0xff0000) >> 16;
                gSum += (pix[yi] & 0x00ff00) >> 8;
                bSum += pix[yi] & 0x0000ff;
                yp += w;
            }
            yi = x;
            for (y = 0; y < h; y++) {
                pix[yi] = 0xff000000 | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];
                if (x == 0) vMin[y] = Math.min(y + radius + 1, h - 1) * w;
                p = x + vMin[y];
                rSum += ((pix[p] & 0xff0000) >> 16) - ((pix[yi] & 0xff0000) >> 16);
                gSum += ((pix[p] & 0x00ff00) >> 8) - ((pix[yi] & 0x00ff00) >> 8);
                bSum += (pix[p] & 0x0000ff) - (pix[yi] & 0x0000ff);
                yi += w;
            }
        }
    }
}
