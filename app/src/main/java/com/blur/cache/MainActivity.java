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
    private static final String TAG = "BlurCache";
    private TextView tvStatus, tvCacheSize, tvWallpaperHash, tvFileCount, tvLog;
    private TextView btnGenerate, btnXposedStatus;
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
        btnXposedStatus = findViewById(R.id.btn_xposed_status);
        btnGenerate.setOnClickListener(v -> generateCache());
        checkAndRequestPermissions();
        checkXposedStatus();
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
    private void checkXposedStatus() {
        boolean hooked = false;
        // 方法1: 检查 LSPosed 模块列表文件
        File modulesList = new File("/data/adb/lspd/modules.list");
        if (modulesList.exists()) {
            try {
                java.util.Scanner s = new java.util.Scanner(modulesList).useDelimiter("\\A");
                String content = s.hasNext() ? s.next() : "";
                hooked = content.contains("com.blur.cache");
                s.close();
            } catch (Exception ignored) {}
        }
        // 方法2: 检查 Xposed 框架标志
        if (!hooked) {
            File xposedBridge = new File("/data/misc/lspd/0/conf");
            if (xposedBridge.exists()) hooked = true;
        }
        // 方法3: 检查系统属性
        if (!hooked) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", "xp.hooked"});
                InputStream is = p.getInputStream();
                byte[] buf = new byte[64];
                int len = is.read(buf);
                is.close();
                if (len > 0) hooked = new String(buf, 0, len).trim().equals("1");
            } catch (Exception ignored) {}
        }
        final boolean isHooked = hooked;
        handler.post(() -> {
            btnXposedStatus.setText(isHooked ? "Xposed: 已激活" : "Xposed: 未激活");
            btnXposedStatus.setTextColor(isHooked ? 0xFF4ADE80 : 0xFFF43F5E);
        });
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("存储权限已授予");
                initCache();
            } else {
                appendLog("存储权限被拒绝");
            }
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
            if (bitmap != null) {
                handler.post(() -> appendLog("root 读取壁纸成功"));
                return bitmap;
            }
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
