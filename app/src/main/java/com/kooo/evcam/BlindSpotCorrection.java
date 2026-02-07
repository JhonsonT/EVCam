package com.kooo.evcam;

import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

public final class BlindSpotCorrection {
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 8.0f;
    private static final float MIN_TRANSLATE = -5.0f;
    private static final float MAX_TRANSLATE = 5.0f;

    private BlindSpotCorrection() {}

    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation) {
        if (textureView == null || appConfig == null) return;

        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            Matrix matrix = new Matrix();

            // 获取预览尺寸
            int previewW = 0, previewH = 0;
            if (cameraPos != null) {
                com.kooo.evcam.camera.MultiCameraManager cm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        android.util.Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            previewW = previewSize.getWidth();
                            previewH = previewSize.getHeight();
                        }
                    }
                }
            }

            // 获取矫正旋转角度（仅 0/90/180/270）
            int correctionRotation = 0;
            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
            }

            // 计算总旋转（baseRotation + correctionRotation），用于判断center-crop时的有效宽高比
            int totalRotation = (baseRotation + correctionRotation) % 360;
            if (totalRotation < 0) totalRotation += 360;
            boolean isRotated90or270 = (totalRotation == 90 || totalRotation == 270);

            // 居中填充（center-crop）：考虑总旋转后的有效预览宽高比
            if (previewW > 0 && previewH > 0) {
                // 旋转90/270后预览宽高互换
                float effectivePreviewW = isRotated90or270 ? previewH : previewW;
                float effectivePreviewH = isRotated90or270 ? previewW : previewH;
                float previewAspect = effectivePreviewW / effectivePreviewH;
                float viewAspect = (float) viewWidth / viewHeight;
                float scaleXFill, scaleYFill;
                if (previewAspect > viewAspect) {
                    scaleYFill = 1.0f;
                    scaleXFill = previewAspect / viewAspect;
                } else {
                    scaleXFill = 1.0f;
                    scaleYFill = viewAspect / previewAspect;
                }
                matrix.postScale(scaleXFill, scaleYFill, centerX, centerY);
            }

            // 应用 baseRotation（副屏方向补偿）
            if (baseRotation != 0) {
                matrix.postRotate(baseRotation, centerX, centerY);
                if (baseRotation == 90 || baseRotation == 270) {
                    float scale = (float) viewWidth / (float) viewHeight;
                    matrix.postScale(1f / scale, scale, centerX, centerY);
                }
            }

            // 应用矫正参数
            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                float scaleX = clamp(appConfig.getBlindSpotCorrectionScaleX(cameraPos), MIN_SCALE, MAX_SCALE);
                float scaleY = clamp(appConfig.getBlindSpotCorrectionScaleY(cameraPos), MIN_SCALE, MAX_SCALE);
                float translateX = clamp(appConfig.getBlindSpotCorrectionTranslateX(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
                float translateY = clamp(appConfig.getBlindSpotCorrectionTranslateY(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);

                matrix.postScale(scaleX, scaleY, centerX, centerY);

                // 矫正旋转（0/90/180/270）
                // 悬浮窗已自动交换宽高（由 MainFloatingWindowView/BlindSpotFloatingWindowView 负责）
                // 旋转后需要缩放补偿：旋转使 viewH×viewW 内容显示在 viewW×viewH 窗口中
                // 正确公式：postScale(viewW/viewH, viewH/viewW) 让内容精确填满窗口
                if (correctionRotation != 0) {
                    matrix.postRotate(correctionRotation, centerX, centerY);
                    if (correctionRotation == 90 || correctionRotation == 270) {
                        float scaleRatio = (float) viewWidth / (float) viewHeight;
                        matrix.postScale(scaleRatio, 1f / scaleRatio, centerX, centerY);
                    }
                }

                matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
            }

            textureView.setTransform(matrix);
        });
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

