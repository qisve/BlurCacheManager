package com.blur.cache;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.app.WallpaperManager;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvCacheSize, tvWallpaperHash, tvFileCount, tvLog;
    private TextView btnGenerate;
    private View statusDot;
    private Handler handler;
    private StringBuilder logBuilder = new StringBuilder();
    private static final int REQ_PERMISSION = 100;
    private static final int REQ_MANAGE_STORAGE = 101;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler(Looper.getMainLooper());
        tvStatus = findViewById(R.id.tv_status);
        tvCacheSize = findViewById(R.id.tv_cache_size);
        tvWallpaperHash = findViewById(R.id.tv_wallpaper_hash);
        tvFileCount = findViewById(R.id.tv_file_count);
        tvLog = findViewById(R.id.tv_log);
        statusDot = findViewById(R.id.status_dot);
        btnGenerate = findViewById(R.id.btn_generate);
        btnGenerate.setOnClickListener(v -> generateCache());
        checkAndRequestPermissions();
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (CacheConfig.hasStoragePermission()) {
            CacheConfig.ensureDir();
            CacheConfig.migrateIfNeeded();
            refreshStatus();
        }
    }
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("需要授予「管理所有文件」权限");
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_MANAGE_STORAGE);
            } else {
                appendLog("存储权限已授予");
                initCache();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
            } else {
                appendLog("存储权限已授予");
                initCache();
            }
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                appendLog("存储权限已授予");
                initCache();
            } else {
                appendLog("存储权限被拒绝");
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            appendLog(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED ? "存储权限已授予" : "存储权限被拒绝");
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) initCache();
        }
    }
    private void initCache() {
        CacheConfig.ensureDir();
        new Thread(() -> {
            CacheConfig.migrateIfNeeded();
            handler.post(() -> refreshStatus());
        }).start();
    }
    private Bitmap getWallpaperBitmap() {
        try {
            WallpaperManager wpm = WallpaperManager.getInstance(this);
            BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
            if (drawable != null && drawable.getBitmap() != null) {
                handler.post(() -> appendLog("API 获取壁纸成功"));
                return drawable.getBitmap();
            }
        } catch (Exception e) {
            handler.post(() -> appendLog("API 失败: " + e.getMessage()));
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
            InputStream is = p.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            p.waitFor();
            if (bitmap != null) { handler.post(() -> appendLog("root 获取壁纸成功")); return bitmap; }
        } catch (Exception e) {
            handler.post(() -> appendLog("root 失败: " + e.getMessage()));
        }
        return null;
    }
    private void generateCache() {
        if (!CacheConfig.hasStoragePermission()) {
            appendLog("未授权");
            checkAndRequestPermissions();
            return;
        }
        appendLog("开始生成缓存...");
        setStatus("正在生成...", 0xFFFBBF24);
        btnGenerate.setEnabled(false);
        btnGenerate.setAlpha(0.5f);
        new Thread(() -> {
            Bitmap bitmap = getWallpaperBitmap();
            if (bitmap == null) {
                handler.post(() -> {
                    appendLog("无法获取壁纸");
                    setStatus("获取壁纸失败", 0xFFF43F5E);
                    btnGenerate.setEnabled(true);
                    btnGenerate.setAlpha(1f);
                });
                return;
            }
            BlurCacheGenerator.generate(this, bitmap, new BlurCacheGenerator.Callback() {
                @Override public void onProgress(String message) { handler.post(() -> appendLog(message)); }
                @Override public void onSuccess(long elapsedMs, int fileCount) {
                    handler.post(() -> {
                        appendLog("完成! " + elapsedMs + "ms, " + fileCount + " 文件");
                        setStatus("缓存就绪", 0xFF4ADE80);
                        btnGenerate.setEnabled(true);
                        btnGenerate.setAlpha(1f);
                        refreshStatus();
                    });
                }
                @Override public void onError(String error) {
                    handler.post(() -> {
                        appendLog("失败: " + error);
                        setStatus("生成失败", 0xFFF43F5E);
                        btnGenerate.setEnabled(true);
                        btnGenerate.setAlpha(1f);
                    });
                }
            });
        }).start();
    }
    private void setStatus(String text, int color) {
        tvStatus.setText(text);
        tvStatus.setTextColor(color);
        statusDot.setBackgroundColor(color);
    }
    private void refreshStatus() {
        new Thread(() -> {
            long size = BlurCacheGenerator.getCacheSize();
            String hash = BlurCacheGenerator.getCachedHash();
            boolean ready = BlurCacheGenerator.isReady();
            int fileCount = getFileCount();
            String sizeStr;
            if (size > 1024 * 1024) sizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));
            else if (size > 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
            else sizeStr = size + " B";
            String hashStr = hash.isEmpty() ? "无" : hash.substring(0, Math.min(16, hash.length())) + "...";
            handler.post(() -> {
                tvCacheSize.setText(sizeStr);
                tvWallpaperHash.setText(hashStr);
                tvFileCount.setText(fileCount + " 个文件");
                setStatus(ready ? "缓存就绪" : "缓存未就绪", ready ? 0xFF4ADE80 : 0xFFFBBF24);
            });
        }).start();
    }
    private int getFileCount() {
        File dir = CacheConfig.CACHE_DIR;
        if (dir.exists()) { File[] files = dir.listFiles(); return files != null ? files.length : 0; }
        return 0;
    }
    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuilder.insert(0, "[" + ts + "] " + msg + "\n");
        String[] lines = logBuilder.toString().split("\n");
        if (lines.length > 50) { logBuilder = new StringBuilder(); for (int i = 0; i < 50; i++) logBuilder.append(lines[i]).append("\n"); }
        tvLog.setText(logBuilder.toString());
    }
}
