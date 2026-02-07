package com.kooo.evcam;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 主屏悬浮窗视图
 * 支持平滑拖动和边缘缩放
 */
public class MainFloatingWindowView extends FrameLayout {
    private static final String TAG = "MainFloatingWindowView";
    private static final int RESIZE_THRESHOLD = 50; // 边缘触发缩放的阈值（像素）

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private AppConfig appConfig;
    private TextureView textureView;
    private Surface cachedSurface;
    private SingleCamera currentCamera;
    private String desiredCameraPos;
    private boolean urgentPending = false; // 下次 startCameraPreview 使用紧急模式

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable retryBindRunnable;
    private int retryBindCount = 0;

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0; // 0: 拖动, 1: 左, 2: 右, 4: 上, 8: 下 (位运算组合)
    private boolean isCurrentlySwapped = false;

    public MainFloatingWindowView(Context context) {
        super(context);
        appConfig = new AppConfig(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        desiredCameraPos = appConfig.getMainFloatingCamera();
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.presentation_secondary_display, this);
        textureView = findViewById(R.id.secondary_texture_view);

        params = new WindowManager.LayoutParams(
                appConfig.getMainFloatingWidth(),
                appConfig.getMainFloatingHeight(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = appConfig.getMainFloatingX();
        params.y = appConfig.getMainFloatingY();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                String cameraPos = desiredCameraPos != null ? desiredCameraPos : appConfig.getMainFloatingCamera();
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        }
                    }
                }
                if (cachedSurface != null) {
                    cachedSurface.release();
                }
                cachedSurface = new Surface(surface);
                boolean urgent = urgentPending;
                urgentPending = false;
                startCameraPreview(cachedSurface, urgent);
                applyTransformNow();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                applyTransformNow();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                cancelRetryBind();
                stopCameraPreview();
                if (cachedSurface != null) {
                    cachedSurface.release();
                    cachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;

                // 判断是否在边缘，进入缩放模式
                float localX = event.getX();
                float localY = event.getY();
                resizeMode = 0;
                if (localX < RESIZE_THRESHOLD) resizeMode |= 1; // 左
                if (localX > getWidth() - RESIZE_THRESHOLD) resizeMode |= 2; // 右
                if (localY < RESIZE_THRESHOLD) resizeMode |= 4; // 上
                if (localY > getHeight() - RESIZE_THRESHOLD) resizeMode |= 8; // 下
                
                isResizing = resizeMode != 0;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;

                if (isResizing) {
                    // 缩放逻辑
                    if ((resizeMode & 1) != 0) { // 左
                        int newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.width = newWidth;
                            params.x = (int) (initialX + dx);
                        }
                    }
                    if ((resizeMode & 2) != 0) { // 右
                        int newWidth = (int) (initialWidth + dx);
                        if (newWidth > 200) params.width = newWidth;
                    }
                    if ((resizeMode & 4) != 0) { // 上
                        int newHeight = (int) (initialHeight - dy);
                        if (newHeight > 200) {
                            params.height = newHeight;
                            params.y = (int) (initialY + dy);
                        }
                    }
                    if ((resizeMode & 8) != 0) { // 下
                        int newHeight = (int) (initialHeight + dy);
                        if (newHeight > 200) params.height = newHeight;
                    }
                } else {
                    // 拖动逻辑
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                }
                
                windowManager.updateViewLayout(this, params);
                return true;

            case MotionEvent.ACTION_UP:
                isResizing = false;
                // 保存配置：若宽高因矫正旋转而交换过，保存前还原为基础值
                int saveW = params.width;
                int saveH = params.height;
                if (isCurrentlySwapped) {
                    saveW = params.height;
                    saveH = params.width;
                }
                appConfig.setMainFloatingBounds(params.x, params.y, saveW, saveH);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void startCameraPreview(Surface surface) {
        startCameraPreview(surface, false);
    }

    private void startCameraPreview(Surface surface, boolean urgent) {
        if (surface == null || !surface.isValid()) {
            scheduleRetryBind();
            return;
        }
        String cameraPos = desiredCameraPos != null ? desiredCameraPos : appConfig.getMainFloatingCamera();
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            // Holder 只初始化了对象但可能还没被调用过 getOrInit
            cameraManager = CameraManagerHolder.getInstance().getOrInit(getContext());
            if (cameraManager == null) {
                scheduleRetryBind();
                return;
            }
        }

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera == null) {
            scheduleRetryBind();
            return;
        }

        cancelRetryBind();
        currentCamera.setMainFloatingSurface(surface);

        // 如果摄像头硬件还未打开（后台初始化时不打开），先打开
        if (!currentCamera.isCameraOpened()) {
            AppLog.d(TAG, "Camera not opened yet, opening now for " + cameraPos);
            // 先打开当前需要的摄像头（优先保证能显示）
            currentCamera.openCamera();
            // openCamera 是异步的，打开成功后会自动调用 createCameraPreviewSession
            // 延迟一小段时间后再打开其他摄像头，避免批量打开时被系统 CAMERA_DISABLED 拦截
            final MultiCameraManager cm = cameraManager;
            mainHandler.postDelayed(() -> {
                AppLog.d(TAG, "Deferred opening remaining cameras");
                cm.openAllCameras(); // 已打开的会跳过（isCameraOpened guard）
            }, 500);
        } else {
            currentCamera.recreateSession(urgent);
        }
    }

    private void stopCameraPreview() {
        stopCameraPreview(false);
    }

    private void stopCameraPreview(boolean urgent) {
        if (currentCamera != null) {
            // 立即停止推帧，防止 Surface 销毁后 queueBuffer abandoned 刷屏
            currentCamera.stopRepeatingNow();
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession(urgent);
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                windowManager.addView(this, params);
                if (textureView != null && textureView.isAvailable() && cachedSurface != null && cachedSurface.isValid()) {
                    startCameraPreview(cachedSurface);
                } else {
                    scheduleRetryBind();
                }
                applyTransformNow();
            } else {
                updateLayout();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing main floating window: " + e.getMessage());
        }
    }

    /**
     * 更新主屏悬浮窗布局
     */
    public void updateLayout() {
        params.x = appConfig.getMainFloatingX();
        params.y = appConfig.getMainFloatingY();
        params.width = appConfig.getMainFloatingWidth();
        params.height = appConfig.getMainFloatingHeight();
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.e(TAG, "Error updating main floating window layout: " + e.getMessage());
        }
        applyTransformNow();
    }

    public void dismiss() {
        cancelRetryBind();
        stopCameraPreview();
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * 更新当前显示的摄像头
     * @param cameraPos 摄像头位置
     */
    public void updateCamera(String cameraPos) {
        updateCamera(cameraPos, false);
    }

    /**
     * 更新当前显示的摄像头
     * @param cameraPos 摄像头位置
     * @param urgent 紧急模式（补盲转向灯触发时使用，最小化延迟）
     */
    public void updateCamera(String cameraPos, boolean urgent) {
        desiredCameraPos = cameraPos;
        if (urgent) urgentPending = true; // 保留紧急标记，供 SurfaceTexture 就绪回调使用
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            scheduleRetryBind();
            return;
        }

        SingleCamera newCamera = cameraManager.getCamera(cameraPos);
        if (newCamera == currentCamera) {
            AppLog.d(TAG, "Camera same as current, skip updateCamera: " + cameraPos);
            return;
        }

        stopCameraPreview(urgent);
        currentCamera = newCamera;
        if (currentCamera != null && textureView.isAvailable()) {
            android.graphics.SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                Size previewSize = currentCamera.getPreviewSize();
                if (previewSize != null) {
                    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                }
            }
            if (cachedSurface == null || !cachedSurface.isValid()) {
                if (cachedSurface != null) cachedSurface.release();
                cachedSurface = new Surface(textureView.getSurfaceTexture());
            }
            currentCamera.setMainFloatingSurface(cachedSurface);
            currentCamera.recreateSession(urgent);
            cancelRetryBind();
            applyTransformNow();
        } else {
            scheduleRetryBind();
        }
    }

    public void applyTransformNow() {
        String cameraPos = desiredCameraPos != null ? desiredCameraPos : appConfig.getMainFloatingCamera();

        // 矫正旋转 90/270 时，悬浮窗宽高互换，让画面自然填满不裁切
        int correctionRotation = 0;
        if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
            correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
        }
        int baseW = appConfig.getMainFloatingWidth();
        int baseH = appConfig.getMainFloatingHeight();
        boolean shouldSwap = (correctionRotation == 90 || correctionRotation == 270);
        isCurrentlySwapped = shouldSwap;
        int targetW = shouldSwap ? baseH : baseW;
        int targetH = shouldSwap ? baseW : baseH;

        // 用户正在拖动缩放时，不覆盖 params，以免打断手势
        if (!isResizing) {
            if (params.width != targetW || params.height != targetH) {
                params.width = targetW;
                params.height = targetH;
                try {
                    if (getParent() != null) {
                        windowManager.updateViewLayout(this, params);
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "Error updating layout for rotation: " + e.getMessage());
                }
            }
        }

        BlindSpotCorrection.apply(textureView, appConfig, cameraPos, 0);
    }

    private void scheduleRetryBind() {
        cancelRetryBind();
        retryBindCount++;
        long delayMs;
        if (urgentPending && retryBindCount <= 5) {
            // 紧急模式下前几次快速重试
            delayMs = 50;
        } else if (retryBindCount <= 10) {
            delayMs = 500;
        } else if (retryBindCount <= 30) {
            delayMs = 1000;
        } else {
            delayMs = 3000;
        }
        retryBindRunnable = () -> {
            if (getParent() == null) return;
            if (textureView == null || !textureView.isAvailable()) {
                scheduleRetryBind();
                return;
            }
            if (cachedSurface == null || !cachedSurface.isValid()) {
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st == null) {
                    scheduleRetryBind();
                    return;
                }
                if (cachedSurface != null) {
                    try { cachedSurface.release(); } catch (Exception e) {}
                }
                cachedSurface = new Surface(st);
            }
            boolean urgent = urgentPending;
            urgentPending = false;
            startCameraPreview(cachedSurface, urgent);
        };
        mainHandler.postDelayed(retryBindRunnable, delayMs);
    }

    private void cancelRetryBind() {
        if (retryBindRunnable != null) {
            mainHandler.removeCallbacks(retryBindRunnable);
            retryBindRunnable = null;
        }
        retryBindCount = 0;
    }
}
