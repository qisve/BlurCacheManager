package com.blur.cache;
import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.ArrayList;
public class SuShell {
    private static final String TAG = "BlurCache";
    private static Process suProcess;
    private static DataOutputStream suOs;
    private static final Object lock = new Object();
    public static synchronized void open() {
        synchronized (lock) {
            if (suProcess != null) return;
            try {
                suProcess = Runtime.getRuntime().exec("su");
                suOs = new DataOutputStream(suProcess.getOutputStream());
                Log.i(TAG, "SuShell 已打开");
            } catch (Exception e) {
                Log.e(TAG, "SuShell 打开失败", e);
                suProcess = null;
                suOs = null;
            }
        }
    }
    public static synchronized void close() {
        synchronized (lock) {
            try {
                if (suOs != null) { suOs.writeBytes("exit\n"); suOs.flush(); suOs.close(); suOs = null; }
                if (suProcess != null) { suProcess.waitFor(); suProcess = null; }
                Log.i(TAG, "SuShell 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "SuShell 关闭失败", e);
                suProcess = null;
                suOs = null;
            }
        }
    }
    public static synchronized boolean isAlive() {
        synchronized (lock) {
            if (suProcess == null) return false;
            try { suProcess.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
        }
    }
    public static synchronized List<String> execWithOutput(String command) {
        List<String> output = new ArrayList<>();
        synchronized (lock) {
            if (suProcess == null || suOs == null) return output;
            try {
                String marker = "CMD_DONE_" + System.nanoTime();
                suOs.writeBytes(command + "\n");
                suOs.writeBytes("echo " + marker + "\n");
                suOs.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(marker)) break;
                    output.add(line);
                }
            } catch (Exception e) {
                Log.e(TAG, "execWithOutput 失败: " + command, e);
            }
        }
        return output;
    }
    public static synchronized void exec(String command) {
        synchronized (lock) {
            if (suProcess == null || suOs == null) return;
            try {
                suOs.writeBytes(command + "\n");
                suOs.flush();
            } catch (Exception e) {
                Log.e(TAG, "exec 失败: " + command, e);
            }
        }
    }
    public static String execSingleWithOutput(String command) {
        List<String> lines = execWithOutput(command);
        return lines.isEmpty() ? "" : lines.get(0);
    }
}
