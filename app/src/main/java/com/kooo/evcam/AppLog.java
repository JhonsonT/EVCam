package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AppLog {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_DEBUG_TO_INFO = "debug_to_info";
    private static final int MAX_BUFFER_LINES = 5000;
    private static final Object LOCK = new Object();
    private static final List<String> BUFFER = new ArrayList<>();
    private static volatile boolean debugToInfo = false;
    
    // Gotify 服务器配置
    private static final String GOTIFY_SERVER_URL = "http://suyunkai.top:40266";
    private static final String GOTIFY_APP_TOKEN = "ABH7ON6sdUfR4Sc";
    
    /**
     * 日志上传回调接口
     */
    public interface UploadCallback {
        void onSuccess();
        void onError(String error);
    }

    private AppLog() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        debugToInfo = prefs.getBoolean(KEY_DEBUG_TO_INFO, false);
    }

    public static boolean isDebugToInfoEnabled(Context context) {
        if (context != null) {
            init(context);
        }
        return debugToInfo;
    }

    public static void setDebugToInfoEnabled(Context context, boolean enabled) {
        debugToInfo = enabled;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_DEBUG_TO_INFO, enabled).apply();
        }
    }

    public static File saveLogsToFile(Context context) {
        if (context == null) {
            return null;
        }
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(BUFFER);
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "evcam_log_" + timestamp + ".txt";

        // 保存到 Download/EVCam_Log/ 目录
        File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EVCam_Log");
        File logFile = new File(logDir, fileName);
        return writeLogToFile(logFile, snapshot) ? logFile : null;
    }
    
    /**
     * 一键上传日志到 Gotify 服务器
     * @param context 上下文
     * @param deviceNickname 设备识别名称
     * @param problemDescription 问题描述
     * @param callback 上传结果回调
     */
    public static void uploadLogsToServer(Context context, String deviceNickname, String problemDescription, UploadCallback callback) {
        if (context == null || callback == null) {
            if (callback != null) {
                callback.onError("Context is null");
            }
            return;
        }
        
        // 在后台线程执行网络请求
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                // 获取日志内容
                List<String> snapshot;
                synchronized (LOCK) {
                    snapshot = new ArrayList<>(BUFFER);
                }
                
                if (snapshot.isEmpty()) {
                    callback.onError("日志为空");
                    return;
                }
                
                // 获取应用版本信息
                String versionName = "unknown";
                int versionCode = 0;
                try {
                    PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    versionName = packageInfo.versionName;
                    versionCode = (int) packageInfo.getLongVersionCode();
                } catch (PackageManager.NameNotFoundException e) {
                    // 忽略
                }
                
                // 构建日志内容
                StringBuilder logContent = new StringBuilder();
                logContent.append("=== EVCam 日志上传 ===\n");
                logContent.append("用户标识: ").append(deviceNickname != null ? deviceNickname : "未知").append("\n");
                logContent.append("设备型号: ").append(Build.MODEL).append("\n");
                logContent.append("系统版本: Android ").append(Build.VERSION.RELEASE)
                         .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                logContent.append("应用版本: ").append(versionName)
                         .append(" (").append(versionCode).append(")\n");
                logContent.append("上传时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
                logContent.append("日志条数: ").append(snapshot.size()).append("\n");
                logContent.append("========================\n\n");
                
                // 添加问题描述
                logContent.append("【问题描述】\n");
                logContent.append(problemDescription != null ? problemDescription : "（无）").append("\n\n");
                logContent.append("========================\n\n");
                
                for (String line : snapshot) {
                    logContent.append(line).append("\n");
                }
                
                // 构建请求 URL
                URL url = new URL(GOTIFY_SERVER_URL + "/message?token=" + GOTIFY_APP_TOKEN);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                
                // 构建 JSON 请求体 - 标题包含用户标识便于识别
                JSONObject jsonBody = new JSONObject();
                String title = deviceNickname != null ? 
                        "EVCam Log - " + deviceNickname : 
                        "EVCam Log - " + Build.MODEL;
                jsonBody.put("title", title);
                jsonBody.put("message", logContent.toString());
                jsonBody.put("priority", 5);
                
                // 发送请求
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                // 获取响应
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    callback.onSuccess();
                } else {
                    // 读取错误信息
                    StringBuilder errorResponse = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            errorResponse.append(line);
                        }
                    } catch (Exception e) {
                        // 忽略读取错误流的异常
                    }
                    callback.onError("HTTP " + responseCode + ": " + errorResponse);
                }
                
            } catch (Exception e) {
                Log.e("AppLog", "Upload failed", e);
                callback.onError(e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private static boolean writeLogToFile(File logFile, List<String> lines) {
        if (!logFile.getParentFile().exists() && !logFile.getParentFile().mkdirs()) {
            return false;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            return true;
        } catch (IOException e) {
            Log.w("AppLog", "Cannot write to " + logFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    public static void d(String tag, String message) {
        logInternal(Log.DEBUG, tag, message, null);
    }

    public static void d(String tag, String message, Throwable tr) {
        logInternal(Log.DEBUG, tag, message, tr);
    }

    public static void i(String tag, String message) {
        logInternal(Log.INFO, tag, message, null);
    }

    public static void i(String tag, String message, Throwable tr) {
        logInternal(Log.INFO, tag, message, tr);
    }

    public static void w(String tag, String message) {
        logInternal(Log.WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable tr) {
        logInternal(Log.WARN, tag, message, tr);
    }

    public static void e(String tag, String message) {
        logInternal(Log.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable tr) {
        logInternal(Log.ERROR, tag, message, tr);
    }

    private static void logInternal(int level, String tag, String message, Throwable tr) {
        String safeTag = tag == null ? "AppLog" : tag;
        String safeMessage = message == null ? "" : message;
        if (tr != null) {
            safeMessage = safeMessage + "\n" + Log.getStackTraceString(tr);
        }
        int outputLevel = (level == Log.DEBUG && debugToInfo) ? Log.INFO : level;
        Log.println(outputLevel, safeTag, safeMessage);
        addToBuffer(outputLevel, safeTag, safeMessage);
    }

    private static void addToBuffer(int level, String tag, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String levelLabel = levelToLabel(level);
        String line = timestamp + " " + levelLabel + "/" + tag + ": " + message;
        synchronized (LOCK) {
            BUFFER.add(line);
            if (BUFFER.size() > MAX_BUFFER_LINES) {
                int removeCount = BUFFER.size() - MAX_BUFFER_LINES;
                BUFFER.subList(0, removeCount).clear();
            }
        }
    }

    private static String levelToLabel(int level) {
        switch (level) {
            case Log.ERROR:
                return "E";
            case Log.WARN:
                return "W";
            case Log.INFO:
                return "I";
            case Log.DEBUG:
                return "D";
            default:
                return String.valueOf(level);
        }
    }
}
