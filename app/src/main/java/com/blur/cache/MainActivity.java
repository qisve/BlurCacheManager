package com.blur.cache;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import android.app.WallpaperManager;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvCacheSize, tvWallpaperHash, tvLog;
    private MaterialButton btnGenerate, btnStartService, btnStopService;
    private Handler handler;
    private StringBuilder logBuilder = new StringBuilder();
    private static final int REQ_PERMISSION = 100;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler(Looper.getMainLooper());
        tvStatus = findViewById(R.id.tv_status);
        tvCacheSize = findViewById(R.id.tv_cache_size);
        tvWallpaperHash = findViewById(R.id.tv_wallpaper_hash);
        tvLog = findViewById(R.id.tv_log);
        btnGenerate = findViewById(R.id.btn_generate);
        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        btnGenerate.setOnClickListener(v -> generateCache());
        btnStartService.setOnClickListener(v -> { startForegroundService(new Intent(this, BlurCacheService.class)); appendLog("监听服务已启动"); });
        btnStopService.setOnClickListener(v -> { stopService(new Intent(this, BlurCacheService.class)); appendLog("监听服务已停止"); });
        requestPermissions();
        // 打开 SuShell，整个生命周期保持
        new Thread(() -> {
            SuShell.open();
            handler.post(() -> {
                appendLog("SuShell 已初始化");
                refreshStatus();
            });
        }).start();
    }
    @Override protected void onResume() { super.onResume(); refreshStatus(); }
    @Override protected void onDestroy() {
        SuShell.close();
        super.onDestroy();
    }
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT < 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            appendLog(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED ? "权限已授予" : "权限被拒绝，使用 root 方式");
        }
    }
    private Bitmap getWallpaperBitmap() {
        try {
            WallpaperManager wpm = WallpaperManager.getInstance(this);
            BitmapDrawable drawable = (BitmapDrawable) wpm.getDrawable();
            if (drawable != null && drawable.getBitmap() != null) {
                handler.post(() -> appendLog("通过 API 获取壁纸成功"));
                return drawable.getBitmap();
            }
        } catch (Exception e) {
            handler.post(() -> appendLog("API 方式失败: " + e.getMessage()));
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/system/users/0/wallpaper_orig"});
            InputStream is = p.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            p.waitFor();
            if (bitmap != null) { handler.post(() -> appendLog("通过 root 读取壁纸成功")); return bitmap; }
        } catch (Exception e) {
            handler.post(() -> appendLog("root 读取失败: " + e.getMessage()));
        }
        return null;
    }
    private void generateCache() {
        appendLog("开始生成缓存...");
        tvStatus.setText("正在生成...");
        tvStatus.setTextColor(0xFFFFC107);
        btnGenerate.setEnabled(false);
        new Thread(() -> {
            Bitmap bitmap = getWallpaperBitmap();
            if (bitmap == null) {
                handler.post(() -> {
                    appendLog("无法获取壁纸，请确认已 root");
                    tvStatus.setText("获取壁纸失败");
                    tvStatus.setTextColor(0xFFFF5252);
                    btnGenerate.setEnabled(true);
                });
                return;
            }
            BlurCacheGenerator.generate(this, bitmap, new BlurCacheGenerator.Callback() {
                @Override public void onProgress(String message) { handler.post(() -> appendLog(message)); }
                @Override public void onSuccess(long elapsedMs, int fileCount) { handler.post(() -> {
                    appendLog("完成! " + elapsedMs + "ms, " + fileCount + " 文件");
                    tvStatus.setText("缓存就绪");
                    tvStatus.setTextColor(0xFF4CAF50);
                    btnGenerate.setEnabled(true);
                    refreshStatus();
                }); }
                @Override public void onError(String error) { handler.post(() -> {
                    appendLog("失败: " + error);
                    tvStatus.setText("生成失败");
                    tvStatus.setTextColor(0xFFFF5252);
                    btnGenerate.setEnabled(true);
                }); }
            });
        }).start();
    }
    private void refreshStatus() {
        new Thread(() -> {
            long size = BlurCacheGenerator.getCacheSize();
            String hash = BlurCacheGenerator.getCachedHash();
            boolean ready = BlurCacheGenerator.isReady();
            String sizeStr;
            if (size > 1024 * 1024) sizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));
            else if (size > 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
            else sizeStr = size + " B";
            String hashStr = hash.isEmpty() ? "无" : hash.substring(0, Math.min(16, hash.length())) + "...";
            handler.post(() -> {
                tvCacheSize.setText("缓存大小: " + sizeStr);
                tvWallpaperHash.setText("壁纸 Hash: " + hashStr);
                tvStatus.setText(ready ? "缓存就绪" : "缓存未就绪");
                tvStatus.setTextColor(ready ? 0xFF4CAF50 : 0xFFFFC107);
            });
        }).start();
    }
    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuilder.insert(0, "[" + ts + "] " + msg + "\n");
        String[] lines = logBuilder.toString().split("\n");
        if (lines.length > 50) { logBuilder = new StringBuilder(); for (int i = 0; i < 50; i++) logBuilder.append(lines[i]).append("\n"); }
        tvLog.setText(logBuilder.toString());
    }
}
