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

            // 获取矫正旋转角度（0~360 任意角度）
            int correctionRotation = 0;
            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
            }

            // 计算总旋转（baseRotation + correctionRotation），用于判断center-crop时的有效宽高比
            int totalRotation = (baseRotation + correctionRotation) % 360;
            if (totalRotation < 0) totalRotation += 360;
            boolean isMorePortrait = isCloserToPortrait(totalRotation);

            // 居中填充（center-crop）：考虑总旋转后的有效预览宽高比
            if (previewW > 0 && previewH > 0) {
                // 更接近竖屏时，预览宽高互换
                float effectivePreviewW = isMorePortrait ? previewH : previewW;
                float effectivePreviewH = isMorePortrait ? previewW : previewH;
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

                // 矫正旋转（支持 0~360 任意角度）
                // 悬浮窗在更接近竖屏时已自动交换宽高（由 MainFloatingWindowView/BlindSpotFloatingWindowView 负责）
                // 旋转后需要缩放补偿，使旋转后的内容完全覆盖窗口（无黑边）
                if (correctionRotation != 0) {
                    matrix.postRotate(correctionRotation, centerX, centerY);
                    // 通用缩放补偿：旋转 θ 后，原始 W×H 矩形的包围盒尺寸
                    // boundW = W*|cosθ| + H*|sinθ|,  boundH = W*|sinθ| + H*|cosθ|
                    // 需要缩放 boundW/W, boundH/H 使内容填满窗口
                    double rad = Math.toRadians(correctionRotation);
                    float absCos = (float) Math.abs(Math.cos(rad));
                    float absSin = (float) Math.abs(Math.sin(rad));
                    float compensateX = absCos + ((float) viewHeight / viewWidth) * absSin;
                    float compensateY = ((float) viewWidth / viewHeight) * absSin + absCos;
                    matrix.postScale(compensateX, compensateY, centerX, centerY);
                }

                matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
            }

            textureView.setTransform(matrix);
        });
    }

    /**
     * 判断旋转角度是否更接近竖屏（即需要交换宽高）
     * 45°~135° 和 225°~315° 范围视为更接近竖屏
     */
    public static boolean isCloserToPortrait(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360; // 归一化到 0~359
        int mod180 = normalized % 180; // 映射到 0~179
        return (mod180 >= 45 && mod180 < 135);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

