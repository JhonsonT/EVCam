package com.kooo.evcam;

import android.content.Context;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 自定义车型布局管理器
 * 管理摄像头窗口和按钮区域的自由操控功能：拖动、缩放、隐藏、旋转、镜像
 */
public class CustomLayoutManager {
    private static final String TAG = "CustomLayoutManager";

    // 尺寸调整比例
    private static final float SCALE_STEP = 0.05f;  // 每次5%
    private static final float MIN_SCALE = 0.2f;    // 最小20%
    private static final float MAX_SCALE = 3.0f;    // 最大300%
    
    // 网格吸附像素
    private static final int GRID_SIZE = 10;

    private final Context context;
    private final AppConfig appConfig;
    
    // 编辑模式状态
    private boolean editModeEnabled = false;
    
    // 管理的视图
    private FrameLayout frameFront;
    private FrameLayout frameBack;
    private FrameLayout frameLeft;
    private FrameLayout frameRight;
    private FrameLayout frameVehicleControl;
    private ViewGroup buttonContainer;
    
    // TextureView 引用（用于旋转和镜像）
    private TextureView textureFront;
    private TextureView textureBack;
    private TextureView textureLeft;
    private TextureView textureRight;
    
    // 编辑控制视图
    private View editControlsView;
    
    // 摄像头总容器（用于计算拖动边界）
    private View containerCameras;
    
    // 配置的摄像头数量
    private int cameraCount = 4;
    
    // 按钮布局变更回调
    private OnButtonLayoutChangeListener buttonLayoutChangeListener;
    
    // 布局数据
    private LayoutData layoutData;
    
    /**
     * 按钮布局变更监听器
     */
    public interface OnButtonLayoutChangeListener {
        void onButtonLayoutChange(String orientation);
    }

    public CustomLayoutManager(Context context) {
        this.context = context;
        this.appConfig = new AppConfig(context);
        this.layoutData = new LayoutData();
        loadLayoutData();
    }

    /**
     * 设置按钮布局变更监听器
     */
    public void setOnButtonLayoutChangeListener(OnButtonLayoutChangeListener listener) {
        this.buttonLayoutChangeListener = listener;
    }
    
    /**
     * 设置摄像头数量
     */
    public void setCameraCount(int count) {
        this.cameraCount = count;
    }
    
    /**
     * 更新按钮容器引用（当按钮方向切换时调用）
     * @param newContainer 新的按钮容器
     */
    public void updateButtonContainer(ViewGroup newContainer) {
        this.buttonContainer = newContainer;
        if (newContainer != null) {
            setupButtonContainer(newContainer);
        }
        AppLog.d(TAG, "按钮容器已更新");
    }
    
    /**
     * 初始化自由操控功能
     */
    public void setupFloatingViews(FrameLayout frameFront, FrameLayout frameBack,
                                   FrameLayout frameLeft, FrameLayout frameRight, FrameLayout frameVehicleControl,
                                   ViewGroup buttonContainer, View editControlsView,
                                   View containerCameras,
                                   TextureView textureFront, TextureView textureBack,
                                   TextureView textureLeft, TextureView textureRight) {
        this.frameFront = frameFront;
        this.frameBack = frameBack;
        this.frameLeft = frameLeft;
        this.frameRight = frameRight;
        this.frameVehicleControl = frameVehicleControl;
        this.buttonContainer = buttonContainer;
        this.editControlsView = editControlsView;
        this.containerCameras = containerCameras;
        this.textureFront = textureFront;
        this.textureBack = textureBack;
        this.textureLeft = textureLeft;
        this.textureRight = textureRight;
        
        // 恢复保存的布局或设置默认位置
        if (!restoreLayout()) {
            // 没有保存的布局，设置默认位置
            containerCameras.post(this::setupDefaultPositions);
        }
        
        // 设置拖动和缩放功能
        if (frameFront != null) {
            setupCameraFrame(frameFront, "front");
            setupRotateMirrorButtons(frameFront, "front", textureFront);
        }
        if (frameBack != null) {
            setupCameraFrame(frameBack, "back");
            setupRotateMirrorButtons(frameBack, "back", textureBack);
        }
        if (frameLeft != null) {
            setupCameraFrame(frameLeft, "left");
            setupRotateMirrorButtons(frameLeft, "left", textureLeft);
        }
        if (frameRight != null) {
            setupCameraFrame(frameRight, "right");
            setupRotateMirrorButtons(frameRight, "right", textureRight);
        }
        if (frameVehicleControl != null) {
            setupVehicleControlButtons();
        }
        if (buttonContainer != null) {
            setupButtonContainer(buttonContainer);
        }
        
        // 设置编辑控制面板的按钮
        setupEditControlButtons();
        
        // 初始化编辑模式
        setEditMode(appConfig.isCustomFreeControlEnabled());
        
        // 延迟应用保存的旋转和镜像配置（需要等待视图布局完成）
        if (containerCameras != null) {
            containerCameras.post(this::applySavedRotationAndMirror);
        }
    }
    
    /**
     * 应用保存的旋转和镜像配置
     * 在视图布局完成后调用，以确保 TextureView 有正确的尺寸
     */
    private void applySavedRotationAndMirror() {
        if (textureFront != null) {
            int rotation = appConfig.getCameraRotation("front");
            boolean mirror = appConfig.getCameraMirror("front");
            if (rotation != 0) {
                applyRotationWithScale(textureFront, rotation);
            }
            if (mirror) {
                applyMirrorWithRotation(textureFront, "front", mirror);
            }
        }
        if (textureBack != null) {
            int rotation = appConfig.getCameraRotation("back");
            boolean mirror = appConfig.getCameraMirror("back");
            if (rotation != 0) {
                applyRotationWithScale(textureBack, rotation);
            }
            if (mirror) {
                applyMirrorWithRotation(textureBack, "back", mirror);
            }
        }
        if (textureLeft != null) {
            int rotation = appConfig.getCameraRotation("left");
            boolean mirror = appConfig.getCameraMirror("left");
            if (rotation != 0) {
                applyRotationWithScale(textureLeft, rotation);
            }
            if (mirror) {
                applyMirrorWithRotation(textureLeft, "left", mirror);
            }
        }
        if (textureRight != null) {
            int rotation = appConfig.getCameraRotation("right");
            boolean mirror = appConfig.getCameraMirror("right");
            if (rotation != 0) {
                applyRotationWithScale(textureRight, rotation);
            }
            if (mirror) {
                applyMirrorWithRotation(textureRight, "right", mirror);
            }
        }
        
        // 应用保存的裁剪配置
        applySavedCrops();
    }
    
    /**
     * 应用旋转并调整缩放，使画面填满容器
     * 当旋转90°或270°时，需要缩放画面以填满原来的容器
     */
    private void applyRotationWithScale(TextureView textureView, int rotation) {
        textureView.setRotation(rotation);
        
        // 当旋转90°或270°时，宽高交换，需要计算缩放比例
        if (rotation == 90 || rotation == 270) {
            int width = textureView.getWidth();
            int height = textureView.getHeight();
            
            if (width > 0 && height > 0) {
                // 计算需要的缩放比例，使旋转后的画面填满容器
                float scale = Math.max((float) width / height, (float) height / width);
                textureView.setScaleY(scale);
                // 检查是否有镜像，镜像时 scaleX 为负值
                float currentScaleX = textureView.getScaleX();
                if (currentScaleX < 0) {
                    textureView.setScaleX(-scale);
                } else {
                    textureView.setScaleX(scale);
                }
                AppLog.d(TAG, "旋转 " + rotation + "° 缩放: " + scale);
            }
        } else {
            // 0°或180°时，恢复正常缩放
            float currentScaleX = textureView.getScaleX();
            if (currentScaleX < 0) {
                textureView.setScaleX(-1.0f);
            } else {
                textureView.setScaleX(1.0f);
            }
            textureView.setScaleY(1.0f);
        }
    }
    
    /**
     * 应用镜像，同时考虑当前的旋转状态
     */
    private void applyMirrorWithRotation(TextureView textureView, String cameraKey, boolean mirror) {
        int rotation = appConfig.getCameraRotation(cameraKey);
        
        float baseScale = 1.0f;
        // 如果当前是90°或270°旋转，需要保持缩放
        if (rotation == 90 || rotation == 270) {
            int width = textureView.getWidth();
            int height = textureView.getHeight();
            if (width > 0 && height > 0) {
                baseScale = Math.max((float) width / height, (float) height / width);
            }
        }
        
        // 应用镜像（负值表示镜像）
        textureView.setScaleX(mirror ? -baseScale : baseScale);
    }
    
    /**
     * 设置旋转和镜像按钮
     */
    private void setupRotateMirrorButtons(FrameLayout frame, String cameraKey, TextureView textureView) {
        // 旋转按钮
        int rotateBtnId = context.getResources().getIdentifier(
                "btn_rotate_" + cameraKey, "id", context.getPackageName());
        View rotateBtn = frame.findViewById(rotateBtnId);
        if (rotateBtn != null) {
            rotateBtn.setOnClickListener(v -> {
                int currentRotation = appConfig.getCameraRotation(cameraKey);
                int newRotation = (currentRotation + 90) % 360;
                appConfig.setCameraRotation(cameraKey, newRotation);
                
                if (textureView != null) {
                    applyRotationWithScale(textureView, newRotation);
                }
                
                Toast.makeText(context, cameraKey + " 旋转: " + newRotation + "°", 
                        Toast.LENGTH_SHORT).show();
                AppLog.d(TAG, cameraKey + " 旋转设置为: " + newRotation + "°");
            });
        }
        
        // 镜像按钮
        int mirrorBtnId = context.getResources().getIdentifier(
                "btn_mirror_" + cameraKey, "id", context.getPackageName());
        View mirrorBtn = frame.findViewById(mirrorBtnId);
        if (mirrorBtn != null) {
            mirrorBtn.setOnClickListener(v -> {
                boolean currentMirror = appConfig.getCameraMirror(cameraKey);
                boolean newMirror = !currentMirror;
                appConfig.setCameraMirror(cameraKey, newMirror);
                
                if (textureView != null) {
                    applyMirrorWithRotation(textureView, cameraKey, newMirror);
                }
                
                Toast.makeText(context, cameraKey + " 镜像: " + (newMirror ? "开" : "关"), 
                        Toast.LENGTH_SHORT).show();
                AppLog.d(TAG, cameraKey + " 镜像设置为: " + newMirror);
            });
        }
        
        // 设置裁剪按钮
        setupCropButtons(frame, cameraKey, textureView);
    }
    
    /**
     * 设置裁剪按钮
     * 每个方向点击一次裁剪 10 像素
     */
    private void setupCropButtons(FrameLayout frame, String cameraKey, TextureView textureView) {
        final int CROP_STEP = 10;  // 每次裁剪 10 像素
        
        // ========== 往里裁剪按钮（黄色） ==========
        
        // 上裁剪按钮
        int cropTopBtnId = context.getResources().getIdentifier(
                "btn_crop_top_" + cameraKey, "id", context.getPackageName());
        View cropTopBtn = frame.findViewById(cropTopBtnId);
        if (cropTopBtn != null) {
            cropTopBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "top");
                int newValue = current + CROP_STEP;
                appConfig.setCameraCrop(cameraKey, "top", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 上裁剪+: " + newValue + "px");
            });
        }
        
        // 下裁剪按钮
        int cropBottomBtnId = context.getResources().getIdentifier(
                "btn_crop_bottom_" + cameraKey, "id", context.getPackageName());
        View cropBottomBtn = frame.findViewById(cropBottomBtnId);
        if (cropBottomBtn != null) {
            cropBottomBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "bottom");
                int newValue = current + CROP_STEP;
                appConfig.setCameraCrop(cameraKey, "bottom", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 下裁剪+: " + newValue + "px");
            });
        }
        
        // 左裁剪按钮
        int cropLeftBtnId = context.getResources().getIdentifier(
                "btn_crop_left_" + cameraKey, "id", context.getPackageName());
        View cropLeftBtn = frame.findViewById(cropLeftBtnId);
        if (cropLeftBtn != null) {
            cropLeftBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "left");
                int newValue = current + CROP_STEP;
                appConfig.setCameraCrop(cameraKey, "left", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 左裁剪+: " + newValue + "px");
            });
        }
        
        // 右裁剪按钮
        int cropRightBtnId = context.getResources().getIdentifier(
                "btn_crop_right_" + cameraKey, "id", context.getPackageName());
        View cropRightBtn = frame.findViewById(cropRightBtnId);
        if (cropRightBtn != null) {
            cropRightBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "right");
                int newValue = current + CROP_STEP;
                appConfig.setCameraCrop(cameraKey, "right", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 右裁剪+: " + newValue + "px");
            });
        }
        
        // ========== 往外恢复按钮（绿色） ==========
        
        // 上恢复按钮
        int uncropTopBtnId = context.getResources().getIdentifier(
                "btn_uncrop_top_" + cameraKey, "id", context.getPackageName());
        View uncropTopBtn = frame.findViewById(uncropTopBtnId);
        if (uncropTopBtn != null) {
            uncropTopBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "top");
                int newValue = Math.max(0, current - CROP_STEP);
                appConfig.setCameraCrop(cameraKey, "top", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 上裁剪-: " + newValue + "px");
            });
        }
        
        // 下恢复按钮
        int uncropBottomBtnId = context.getResources().getIdentifier(
                "btn_uncrop_bottom_" + cameraKey, "id", context.getPackageName());
        View uncropBottomBtn = frame.findViewById(uncropBottomBtnId);
        if (uncropBottomBtn != null) {
            uncropBottomBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "bottom");
                int newValue = Math.max(0, current - CROP_STEP);
                appConfig.setCameraCrop(cameraKey, "bottom", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 下裁剪-: " + newValue + "px");
            });
        }
        
        // 左恢复按钮
        int uncropLeftBtnId = context.getResources().getIdentifier(
                "btn_uncrop_left_" + cameraKey, "id", context.getPackageName());
        View uncropLeftBtn = frame.findViewById(uncropLeftBtnId);
        if (uncropLeftBtn != null) {
            uncropLeftBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "left");
                int newValue = Math.max(0, current - CROP_STEP);
                appConfig.setCameraCrop(cameraKey, "left", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 左裁剪-: " + newValue + "px");
            });
        }
        
        // 右恢复按钮
        int uncropRightBtnId = context.getResources().getIdentifier(
                "btn_uncrop_right_" + cameraKey, "id", context.getPackageName());
        View uncropRightBtn = frame.findViewById(uncropRightBtnId);
        if (uncropRightBtn != null) {
            uncropRightBtn.setOnClickListener(v -> {
                int current = appConfig.getCameraCrop(cameraKey, "right");
                int newValue = Math.max(0, current - CROP_STEP);
                appConfig.setCameraCrop(cameraKey, "right", newValue);
                applyCrop(textureView, cameraKey);
                AppLog.d(TAG, cameraKey + " 右裁剪-: " + newValue + "px");
            });
        }
    }
    
    /**
     * 设置车辆控制按钮
     * 前轮和后轮按键的选中/未选中状态切换（互斥选择）
     */
    private void setupVehicleControlButtons() {
        if (frameVehicleControl == null) return;

        Button btnFrontWheel = frameVehicleControl.findViewById(R.id.btn_front_wheel);
        Button btnRearWheel = frameVehicleControl.findViewById(R.id.btn_rear_wheel);
        ImageView ivVehicleOutline = frameVehicleControl.findViewById(R.id.iv_vehicle_outline);

        if (btnFrontWheel != null) {
            btnFrontWheel.setOnClickListener(v -> {
                boolean isSelected = btnFrontWheel.getTag() != null && (Boolean) btnFrontWheel.getTag();

                if (isSelected) {
                    // 已选中，取消选中
                    isSelected = false;
                    btnFrontWheel.setTag(isSelected);
                    setButtonUnselected(btnFrontWheel);
                    // 切换到普通模式，恢复默认车辆轮廓
                    if (ivVehicleOutline != null) {
                        ivVehicleOutline.setImageResource(R.drawable.ic_vehicle_outline_normal);
                    }
                    applyNormalModeLayout();
                } else {
                    // 未选中，选中前轮并取消后轮选中
                    isSelected = true;
                    btnFrontWheel.setTag(isSelected);
                    setButtonSelected(btnFrontWheel);

                    // 取消后轮选中状态
                    if (btnRearWheel != null) {
                        btnRearWheel.setTag(false);
                        setButtonUnselected(btnRearWheel);
                    }

                    // 更新车辆轮廓示意图，前轮显示绿色
                    if (ivVehicleOutline != null) {
                        ivVehicleOutline.setImageResource(R.drawable.ic_vehicle_outline_front);
                    }

                    // 切换到前轮模式
                    applyFrontWheelModeLayout();
                }

                AppLog.d(TAG, "前轮按键状态: " + (isSelected ? "选中" : "未选中"));
            });

            // 长按事件：弹出前轮模式设置弹窗
            btnFrontWheel.setOnLongClickListener(v -> {
                showWheelSettingsDialog("front");
                return true;
            });
        }

        if (btnRearWheel != null) {
            btnRearWheel.setOnClickListener(v -> {
                boolean isSelected = btnRearWheel.getTag() != null && (Boolean) btnRearWheel.getTag();

                if (isSelected) {
                    // 已选中，取消选中
                    isSelected = false;
                    btnRearWheel.setTag(isSelected);
                    setButtonUnselected(btnRearWheel);
                    // 切换到普通模式，恢复默认车辆轮廓
                    if (ivVehicleOutline != null) {
                        ivVehicleOutline.setImageResource(R.drawable.ic_vehicle_outline_normal);
                    }
                    applyNormalModeLayout();
                } else {
                    // 未选中，选中后轮并取消前轮选中
                    isSelected = true;
                    btnRearWheel.setTag(isSelected);
                    setButtonSelected(btnRearWheel);

                    // 取消前轮选中状态
                    if (btnFrontWheel != null) {
                        btnFrontWheel.setTag(false);
                        setButtonUnselected(btnFrontWheel);
                    }

                    // 更新车辆轮廓示意图，后轮显示绿色
                    if (ivVehicleOutline != null) {
                        ivVehicleOutline.setImageResource(R.drawable.ic_vehicle_outline_rear);
                    }

                    // 切换到后轮模式
                    applyRearWheelModeLayout();
                }

                AppLog.d(TAG, "后轮按键状态: " + (isSelected ? "选中" : "未选中"));
            });

            // 长按事件：弹出后轮模式设置弹窗
            btnRearWheel.setOnLongClickListener(v -> {
                showWheelSettingsDialog("rear");
                return true;
            });
        }
    }

    /**
     * 显示车轮模式设置弹窗
     * @param mode "front" 前轮模式, "rear" 后轮模式
     */
    private void showWheelSettingsDialog(String mode) {
        if (containerCameras == null) return;

        // 创建弹窗
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        View dialogView = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_wheel_settings, null);
        builder.setView(dialogView);

        // 设置标题
        TextView tvTitle = dialogView.findViewById(R.id.tv_title);
        tvTitle.setText(mode.equals("front") ? "前轮模式设置" : "后轮模式设置");

        // 获取当前容器尺寸
        int containerWidth = containerCameras.getWidth();
        int containerHeight = containerCameras.getHeight();
        
        // 前轮模式默认值
        int frontLeftWidth = 1120;
        int frontLeftHeight = 662;
        int frontLeftX = 10;
        int frontLeftY = 397;
        int frontLeftRotation = 270;
        int frontRightWidth = 1211;
        int frontRightHeight = 662;
        int frontRightX = -76;
        int frontRightY = 502;
        int frontRightRotation = 90;
        
        // 后轮模式默认值
        int rearLeftWidth = 1120;
        int rearLeftHeight = 662;
        int rearLeftX = 10;
        int rearLeftY = -624;
        int rearLeftRotation = 270;
        int rearRightWidth = 1298;
        int rearRightHeight = 662;
        int rearRightX = -164;
        int rearRightY = -702;
        int rearRightRotation = 90;

        // 获取SeekBar和TextView引用
        android.widget.SeekBar sbLeftWidth = dialogView.findViewById(R.id.sb_left_width);
        android.widget.SeekBar sbLeftHeight = dialogView.findViewById(R.id.sb_left_height);
        android.widget.SeekBar sbLeftX = dialogView.findViewById(R.id.sb_left_x);
        android.widget.SeekBar sbLeftY = dialogView.findViewById(R.id.sb_left_y);
        android.widget.SeekBar sbLeftRotation = dialogView.findViewById(R.id.sb_left_rotation);
        android.widget.SeekBar sbRightWidth = dialogView.findViewById(R.id.sb_right_width);
        android.widget.SeekBar sbRightHeight = dialogView.findViewById(R.id.sb_right_height);
        android.widget.SeekBar sbRightX = dialogView.findViewById(R.id.sb_right_x);
        android.widget.SeekBar sbRightY = dialogView.findViewById(R.id.sb_right_y);
        android.widget.SeekBar sbRightRotation = dialogView.findViewById(R.id.sb_right_rotation);

        TextView tvLeftWidthValue = dialogView.findViewById(R.id.tv_left_width_value);
        TextView tvLeftHeightValue = dialogView.findViewById(R.id.tv_left_height_value);
        TextView tvLeftXValue = dialogView.findViewById(R.id.tv_left_x_value);
        TextView tvLeftYValue = dialogView.findViewById(R.id.tv_left_y_value);
        TextView tvLeftRotationValue = dialogView.findViewById(R.id.tv_left_rotation_value);
        TextView tvRightWidthValue = dialogView.findViewById(R.id.tv_right_width_value);
        TextView tvRightHeightValue = dialogView.findViewById(R.id.tv_right_height_value);
        TextView tvRightXValue = dialogView.findViewById(R.id.tv_right_x_value);
        TextView tvRightYValue = dialogView.findViewById(R.id.tv_right_y_value);
        TextView tvRightRotationValue = dialogView.findViewById(R.id.tv_right_rotation_value);

        // 加载当前保存的值
        // 使用各自模式的默认值
        int[] leftValues = new int[5];
        int[] rightValues = new int[5];
        
        if (mode.equals("front")) {
            leftValues[0] = appConfig.getFrontWheelLeftWidth(frontLeftWidth);
            leftValues[1] = appConfig.getFrontWheelLeftHeight(frontLeftHeight);
            leftValues[2] = appConfig.getFrontWheelLeftX(frontLeftX);
            leftValues[3] = appConfig.getFrontWheelLeftY(frontLeftY);
            leftValues[4] = appConfig.getFrontWheelLeftRotation(frontLeftRotation);
            rightValues[0] = appConfig.getFrontWheelRightWidth(frontRightWidth);
            rightValues[1] = appConfig.getFrontWheelRightHeight(frontRightHeight);
            rightValues[2] = appConfig.getFrontWheelRightX(frontRightX);
            rightValues[3] = appConfig.getFrontWheelRightY(frontRightY);
            rightValues[4] = appConfig.getFrontWheelRightRotation(frontRightRotation);
        } else {
            leftValues[0] = appConfig.getRearWheelLeftWidth(rearLeftWidth);
            leftValues[1] = appConfig.getRearWheelLeftHeight(rearLeftHeight);
            leftValues[2] = appConfig.getRearWheelLeftX(rearLeftX);
            leftValues[3] = appConfig.getRearWheelLeftY(rearLeftY);
            leftValues[4] = appConfig.getRearWheelLeftRotation(rearLeftRotation);
            rightValues[0] = appConfig.getRearWheelRightWidth(rearRightWidth);
            rightValues[1] = appConfig.getRearWheelRightHeight(rearRightHeight);
            rightValues[2] = appConfig.getRearWheelRightX(rearRightX);
            rightValues[3] = appConfig.getRearWheelRightY(rearRightY);
            rightValues[4] = appConfig.getRearWheelRightRotation(rearRightRotation);
        }

        // 设置SeekBar初始值和最大值
        // X和Y位置的范围为-1000到1000，SeekBar的0-2000对应实际值-1000到1000
        final int POSITION_OFFSET = 1000;
        final int POSITION_MAX = 2000;
        
        sbLeftWidth.setMax(containerWidth);
        sbLeftHeight.setMax(containerHeight);
        sbLeftX.setMax(POSITION_MAX);
        sbLeftY.setMax(POSITION_MAX);
        sbLeftRotation.setMax(360);
        sbRightWidth.setMax(containerWidth);
        sbRightHeight.setMax(containerHeight);
        sbRightX.setMax(POSITION_MAX);
        sbRightY.setMax(POSITION_MAX);
        sbRightRotation.setMax(360);

        sbLeftWidth.setProgress(leftValues[0]);
        sbLeftHeight.setProgress(leftValues[1]);
        // X和Y位置：实际值+OFFSET作为SeekBar的progress
        sbLeftX.setProgress(leftValues[2] + POSITION_OFFSET);
        sbLeftY.setProgress(leftValues[3] + POSITION_OFFSET);
        sbLeftRotation.setProgress(leftValues[4]);
        sbRightWidth.setProgress(rightValues[0]);
        sbRightHeight.setProgress(rightValues[1]);
        sbRightX.setProgress(rightValues[2] + POSITION_OFFSET);
        sbRightY.setProgress(rightValues[3] + POSITION_OFFSET);
        sbRightRotation.setProgress(rightValues[4]);

        // 更新TextView显示（X和Y显示实际值：progress-OFFSET）
        tvLeftWidthValue.setText(String.valueOf(leftValues[0]));
        tvLeftHeightValue.setText(String.valueOf(leftValues[1]));
        tvLeftXValue.setText(String.valueOf(leftValues[2]));
        tvLeftYValue.setText(String.valueOf(leftValues[3]));
        tvLeftRotationValue.setText(leftValues[4] + "°");
        tvRightWidthValue.setText(String.valueOf(rightValues[0]));
        tvRightHeightValue.setText(String.valueOf(rightValues[1]));
        tvRightXValue.setText(String.valueOf(rightValues[2]));
        tvRightYValue.setText(String.valueOf(rightValues[3]));
        tvRightRotationValue.setText(rightValues[4] + "°");

        // 画框默认位置和大小（根据模式选择）
        final int defaultLeftX = mode.equals("front") ? frontLeftX : rearLeftX;
        final int defaultLeftY = mode.equals("front") ? frontLeftY : rearLeftY;
        final int defaultLeftWidth = mode.equals("front") ? frontLeftWidth : rearLeftWidth;
        final int defaultLeftHeight = mode.equals("front") ? frontLeftHeight : rearLeftHeight;
        final int defaultRightX = mode.equals("front") ? frontRightX : rearRightX;
        final int defaultRightY = mode.equals("front") ? frontRightY : rearRightY;
        final int defaultRightWidth = mode.equals("front") ? frontRightWidth : rearRightWidth;
        final int defaultRightHeight = mode.equals("front") ? frontRightHeight : rearRightHeight;

        // 创建弹窗
        android.app.AlertDialog dialog = builder.create();

        // 实时预览更新的Runnable
        final Runnable previewUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (frameLeft == null || frameRight == null) return;
                
                int leftWidth = sbLeftWidth.getProgress();
                int leftHeight = sbLeftHeight.getProgress();
                // X和Y位置：SeekBar的progress-OFFSET作为实际值
                int leftX = sbLeftX.getProgress() - POSITION_OFFSET;
                int leftY = sbLeftY.getProgress() - POSITION_OFFSET;
                int leftRotation = sbLeftRotation.getProgress();
                int rightWidth = sbRightWidth.getProgress();
                int rightHeight = sbRightHeight.getProgress();
                int rightX = sbRightX.getProgress() - POSITION_OFFSET;
                int rightY = sbRightY.getProgress() - POSITION_OFFSET;
                int rightRotation = sbRightRotation.getProgress();

                // 实时应用布局到视图（画框位置和大小保持默认值）
                setViewPosition(frameLeft, defaultLeftX, defaultLeftY, defaultLeftWidth, defaultLeftHeight);
                setViewPosition(frameRight, defaultRightX, defaultRightY, defaultRightWidth, defaultRightHeight);

                // 实时应用位置和大小（只调整画面和测试文字）
                if (textureLeft != null) {
                    textureLeft.setX(leftX);
                    textureLeft.setY(leftY);
                    android.widget.FrameLayout.LayoutParams leftParams = new android.widget.FrameLayout.LayoutParams(leftWidth, leftHeight);
                    textureLeft.setLayoutParams(leftParams);
                    // 延迟应用旋转，确保布局已更新
                    textureLeft.post(() -> applyRotationWithScale(textureLeft, leftRotation));
                }
                if (textureRight != null) {
                    textureRight.setX(rightX);
                    textureRight.setY(rightY);
                    android.widget.FrameLayout.LayoutParams rightParams = new android.widget.FrameLayout.LayoutParams(rightWidth, rightHeight);
                    textureRight.setLayoutParams(rightParams);
                    // 延迟应用旋转，确保布局已更新
                    textureRight.post(() -> applyRotationWithScale(textureRight, rightRotation));
                }
            }
        };

        // SeekBar变化监听器
        android.widget.SeekBar.OnSeekBarChangeListener seekBarChangeListener = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                // 更新对应的TextView
                int id = seekBar.getId();
                if (id == R.id.sb_left_width) {
                    tvLeftWidthValue.setText(String.valueOf(progress));
                } else if (id == R.id.sb_left_height) {
                    tvLeftHeightValue.setText(String.valueOf(progress));
                } else if (id == R.id.sb_left_x) {
                    tvLeftXValue.setText(String.valueOf(progress - POSITION_OFFSET));
                } else if (id == R.id.sb_left_y) {
                    tvLeftYValue.setText(String.valueOf(progress - POSITION_OFFSET));
                } else if (id == R.id.sb_left_rotation) {
                    tvLeftRotationValue.setText(progress + "°");
                } else if (id == R.id.sb_right_width) {
                    tvRightWidthValue.setText(String.valueOf(progress));
                } else if (id == R.id.sb_right_height) {
                    tvRightHeightValue.setText(String.valueOf(progress));
                } else if (id == R.id.sb_right_x) {
                    tvRightXValue.setText(String.valueOf(progress - POSITION_OFFSET));
                } else if (id == R.id.sb_right_y) {
                    tvRightYValue.setText(String.valueOf(progress - POSITION_OFFSET));
                } else if (id == R.id.sb_right_rotation) {
                    tvRightRotationValue.setText(progress + "°");
                }

                // 实时更新预览
                previewUpdateRunnable.run();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        };

        // 设置所有SeekBar的监听器
        sbLeftWidth.setOnSeekBarChangeListener(seekBarChangeListener);
        sbLeftHeight.setOnSeekBarChangeListener(seekBarChangeListener);
        sbLeftX.setOnSeekBarChangeListener(seekBarChangeListener);
        sbLeftY.setOnSeekBarChangeListener(seekBarChangeListener);
        sbLeftRotation.setOnSeekBarChangeListener(seekBarChangeListener);
        sbRightWidth.setOnSeekBarChangeListener(seekBarChangeListener);
        sbRightHeight.setOnSeekBarChangeListener(seekBarChangeListener);
        sbRightX.setOnSeekBarChangeListener(seekBarChangeListener);
        sbRightY.setOnSeekBarChangeListener(seekBarChangeListener);
        sbRightRotation.setOnSeekBarChangeListener(seekBarChangeListener);

        // 设置保存按钮点击事件
        Button btnSave = dialogView.findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            int leftWidth = sbLeftWidth.getProgress();
            int leftHeight = sbLeftHeight.getProgress();
            // X和Y位置：SeekBar的progress-OFFSET作为实际值
            int leftX = sbLeftX.getProgress() - POSITION_OFFSET;
            int leftY = sbLeftY.getProgress() - POSITION_OFFSET;
            int leftRotation = sbLeftRotation.getProgress();
            int rightWidth = sbRightWidth.getProgress();
            int rightHeight = sbRightHeight.getProgress();
            int rightX = sbRightX.getProgress() - POSITION_OFFSET;
            int rightY = sbRightY.getProgress() - POSITION_OFFSET;
            int rightRotation = sbRightRotation.getProgress();

            if (mode.equals("front")) {
                appConfig.setFrontWheelLeftViewParams(leftWidth, leftHeight, leftX, leftY, leftRotation);
                appConfig.setFrontWheelRightViewParams(rightWidth, rightHeight, rightX, rightY, rightRotation);
                Toast.makeText(context, "前轮模式设置已保存", Toast.LENGTH_SHORT).show();
            } else {
                appConfig.setRearWheelLeftViewParams(leftWidth, leftHeight, leftX, leftY, leftRotation);
                appConfig.setRearWheelRightViewParams(rightWidth, rightHeight, rightX, rightY, rightRotation);
                Toast.makeText(context, "后轮模式设置已保存", Toast.LENGTH_SHORT).show();
            }

            dialog.dismiss();
        });

        // 设置重置按钮点击事件
        Button btnReset = dialogView.findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> {
            // 重置为对应模式的默认值
            int resetLeftWidth, resetLeftHeight, resetLeftX, resetLeftY, resetLeftRotation;
            int resetRightWidth, resetRightHeight, resetRightX, resetRightY, resetRightRotation;
            
            if (mode.equals("front")) {
                resetLeftWidth = frontLeftWidth;
                resetLeftHeight = frontLeftHeight;
                resetLeftX = frontLeftX;
                resetLeftY = frontLeftY;
                resetLeftRotation = frontLeftRotation;
                resetRightWidth = frontRightWidth;
                resetRightHeight = frontRightHeight;
                resetRightX = frontRightX;
                resetRightY = frontRightY;
                resetRightRotation = frontRightRotation;
            } else {
                resetLeftWidth = rearLeftWidth;
                resetLeftHeight = rearLeftHeight;
                resetLeftX = rearLeftX;
                resetLeftY = rearLeftY;
                resetLeftRotation = rearLeftRotation;
                resetRightWidth = rearRightWidth;
                resetRightHeight = rearRightHeight;
                resetRightX = rearRightX;
                resetRightY = rearRightY;
                resetRightRotation = rearRightRotation;
            }

            sbLeftWidth.setProgress(resetLeftWidth);
            sbLeftHeight.setProgress(resetLeftHeight);
            // X和Y位置：实际值+OFFSET作为SeekBar的progress
            sbLeftX.setProgress(resetLeftX + POSITION_OFFSET);
            sbLeftY.setProgress(resetLeftY + POSITION_OFFSET);
            sbLeftRotation.setProgress(resetLeftRotation);
            sbRightWidth.setProgress(resetRightWidth);
            sbRightHeight.setProgress(resetRightHeight);
            sbRightX.setProgress(resetRightX + POSITION_OFFSET);
            sbRightY.setProgress(resetRightY + POSITION_OFFSET);
            sbRightRotation.setProgress(resetRightRotation);

            // 更新TextView（X和Y显示实际值）
            tvLeftWidthValue.setText(String.valueOf(resetLeftWidth));
            tvLeftHeightValue.setText(String.valueOf(resetLeftHeight));
            tvLeftXValue.setText(String.valueOf(resetLeftX));
            tvLeftYValue.setText(String.valueOf(resetLeftY));
            tvLeftRotationValue.setText(resetLeftRotation + "°");
            tvRightWidthValue.setText(String.valueOf(resetRightWidth));
            tvRightHeightValue.setText(String.valueOf(resetRightHeight));
            tvRightXValue.setText(String.valueOf(resetRightX));
            tvRightYValue.setText(String.valueOf(resetRightY));
            tvRightRotationValue.setText(resetRightRotation + "°");

            // 实时应用重置后的值
            previewUpdateRunnable.run();

            Toast.makeText(context, "已重置为默认值", Toast.LENGTH_SHORT).show();
        });

        // 设置弹窗背景透明度为15%
        dialog.setOnShowListener(d -> {
            View rootView = dialog.getWindow().getDecorView();
            rootView.setBackgroundColor(android.graphics.Color.parseColor("#26000000"));
        });

        // 弹窗关闭时恢复原始布局（如果未保存）
        dialog.setOnDismissListener(d -> {
            // 重新应用当前模式的保存值
            if (mode.equals("front")) {
                Button btnFrontWheel = frameVehicleControl.findViewById(R.id.btn_front_wheel);
                if (btnFrontWheel != null && btnFrontWheel.getTag() != null && (Boolean) btnFrontWheel.getTag()) {
                    applyFrontWheelModeLayout();
                } else {
                    applyNormalModeLayout();
                }
            } else {
                Button btnRearWheel = frameVehicleControl.findViewById(R.id.btn_rear_wheel);
                if (btnRearWheel != null && btnRearWheel.getTag() != null && (Boolean) btnRearWheel.getTag()) {
                    applyRearWheelModeLayout();
                } else {
                    applyNormalModeLayout();
                }
            }
        });

        dialog.show();
    }

    /**
     * 应用前轮模式布局
     * 初始值与普通模式相同
     */
    private void applyFrontWheelModeLayout() {
        if (frameLeft == null || frameRight == null) return;

        int containerWidth = containerCameras.getWidth();
        int containerHeight = containerCameras.getHeight();
        
        AppLog.d(TAG, "前轮模式 - 容器尺寸: " + containerWidth + "x" + containerHeight);
        
        if (containerWidth == 0 || containerHeight == 0) {
            AppLog.e(TAG, "前轮模式 - 容器尺寸为0，延迟重试");
            containerCameras.post(this::applyFrontWheelModeLayout);
            return;
        }
        
        int halfHeight = (containerHeight - 20) / 2;
        int padding = 10;
        int vehicleControlWidth = 280;

        // 前轮模式下显示车辆控制区域和左右视图
        if (frameVehicleControl != null) {
            frameVehicleControl.setVisibility(View.VISIBLE);
        }
        frameLeft.setVisibility(View.VISIBLE);
        frameRight.setVisibility(View.VISIBLE);

        // 前轮模式默认值
        int defaultLeftWidth = 1120;
        int defaultLeftHeight = 662;
        int defaultLeftX = 10;
        int defaultLeftY = 397;
        int defaultLeftRotation = 270;
        int defaultRightWidth = 1211;
        int defaultRightHeight = 662;
        int defaultRightX = -76;
        int defaultRightY = 502;
        int defaultRightRotation = 90;

        // 获取保存的参数
        int leftWidth = appConfig.getFrontWheelLeftWidth(defaultLeftWidth);
        int leftHeight = appConfig.getFrontWheelLeftHeight(defaultLeftHeight);
        int leftX = appConfig.getFrontWheelLeftX(defaultLeftX);
        int leftY = appConfig.getFrontWheelLeftY(defaultLeftY);
        int leftRotation = appConfig.getFrontWheelLeftRotation(defaultLeftRotation);

        int rightWidth = appConfig.getFrontWheelRightWidth(defaultRightWidth);
        int rightHeight = appConfig.getFrontWheelRightHeight(defaultRightHeight);
        int rightX = appConfig.getFrontWheelRightX(defaultRightX);
        int rightY = appConfig.getFrontWheelRightY(defaultRightY);
        int rightRotation = appConfig.getFrontWheelRightRotation(defaultRightRotation);

        // 应用布局到画框（画框保持默认位置和大小）
        setViewPosition(frameLeft, defaultLeftX, defaultLeftY, defaultLeftWidth, defaultLeftHeight);
        setViewPosition(frameRight, defaultRightX, defaultRightY, defaultRightWidth, defaultRightHeight);

        // 应用参数到画面（位置、大小、旋转）
        // 注意：画面位置使用保存的绝对位置，与实时预览一致
        if (textureLeft != null) {
            textureLeft.setX(leftX);
            textureLeft.setY(leftY);
            android.widget.FrameLayout.LayoutParams leftParams = new android.widget.FrameLayout.LayoutParams(leftWidth, leftHeight);
            textureLeft.setLayoutParams(leftParams);
            // 延迟应用旋转，确保布局已更新
            textureLeft.post(() -> applyRotationWithScale(textureLeft, leftRotation));
        }
        if (textureRight != null) {
            textureRight.setX(rightX);
            textureRight.setY(rightY);
            android.widget.FrameLayout.LayoutParams rightParams = new android.widget.FrameLayout.LayoutParams(rightWidth, rightHeight);
            textureRight.setLayoutParams(rightParams);
            // 延迟应用旋转，确保布局已更新
            textureRight.post(() -> applyRotationWithScale(textureRight, rightRotation));
        }

        AppLog.d(TAG, "前轮模式布局已应用 - 左视图: (" + leftX + "," + leftY + ") " + leftWidth + "x" + leftHeight + ", 右视图: (" + rightX + "," + rightY + ") " + rightWidth + "x" + rightHeight);
    }

    /**
     * 应用后轮模式布局
     * 初始值与普通模式相同
     */
    private void applyRearWheelModeLayout() {
        if (frameLeft == null || frameRight == null) return;

        int containerWidth = containerCameras.getWidth();
        int containerHeight = containerCameras.getHeight();
        
        AppLog.d(TAG, "后轮模式 - 容器尺寸: " + containerWidth + "x" + containerHeight);
        
        if (containerWidth == 0 || containerHeight == 0) {
            AppLog.e(TAG, "后轮模式 - 容器尺寸为0，延迟重试");
            containerCameras.post(this::applyRearWheelModeLayout);
            return;
        }
        
        int halfHeight = (containerHeight - 20) / 2;
        int padding = 10;
        int vehicleControlWidth = 280;

        // 后轮模式下显示车辆控制区域和左右视图
        if (frameVehicleControl != null) {
            frameVehicleControl.setVisibility(View.VISIBLE);
        }
        frameLeft.setVisibility(View.VISIBLE);
        frameRight.setVisibility(View.VISIBLE);

        // 后轮模式默认值
        int defaultLeftWidth = 1120;
        int defaultLeftHeight = 662;
        int defaultLeftX = 10;
        int defaultLeftY = -624;
        int defaultLeftRotation = 270;
        int defaultRightWidth = 1298;
        int defaultRightHeight = 662;
        int defaultRightX = -164;
        int defaultRightY = -702;
        int defaultRightRotation = 90;

        // 获取保存的参数
        int leftWidth = appConfig.getRearWheelLeftWidth(defaultLeftWidth);
        int leftHeight = appConfig.getRearWheelLeftHeight(defaultLeftHeight);
        int leftX = appConfig.getRearWheelLeftX(defaultLeftX);
        int leftY = appConfig.getRearWheelLeftY(defaultLeftY);
        int leftRotation = appConfig.getRearWheelLeftRotation(defaultLeftRotation);

        int rightWidth = appConfig.getRearWheelRightWidth(defaultRightWidth);
        int rightHeight = appConfig.getRearWheelRightHeight(defaultRightHeight);
        int rightX = appConfig.getRearWheelRightX(defaultRightX);
        int rightY = appConfig.getRearWheelRightY(defaultRightY);
        int rightRotation = appConfig.getRearWheelRightRotation(defaultRightRotation);

        // 应用布局到画框（画框保持默认位置和大小）
        setViewPosition(frameLeft, defaultLeftX, defaultLeftY, defaultLeftWidth, defaultLeftHeight);
        setViewPosition(frameRight, defaultRightX, defaultRightY, defaultRightWidth, defaultRightHeight);

        // 应用参数到画面（位置、大小、旋转）
        // 注意：画面位置使用保存的绝对位置，与实时预览一致
        if (textureLeft != null) {
            textureLeft.setX(leftX);
            textureLeft.setY(leftY);
            android.widget.FrameLayout.LayoutParams leftParams = new android.widget.FrameLayout.LayoutParams(leftWidth, leftHeight);
            textureLeft.setLayoutParams(leftParams);
            // 延迟应用旋转，确保布局已更新
            textureLeft.post(() -> applyRotationWithScale(textureLeft, leftRotation));
        }
        if (textureRight != null) {
            textureRight.setX(rightX);
            textureRight.setY(rightY);
            android.widget.FrameLayout.LayoutParams rightParams = new android.widget.FrameLayout.LayoutParams(rightWidth, rightHeight);
            textureRight.setLayoutParams(rightParams);
            // 延迟应用旋转，确保布局已更新
            textureRight.post(() -> applyRotationWithScale(textureRight, rightRotation));
        }

        AppLog.d(TAG, "后轮模式布局已应用 - 左视图: (" + leftX + "," + leftY + ") " + leftWidth + "x" + leftHeight + ", 右视图: (" + rightX + "," + rightY + ") " + rightWidth + "x" + rightHeight);
    }

    /**
     * 应用普通模式布局（默认模式）
     * 普通模式下保持车辆控制区域可见
     */
    private void applyNormalModeLayout() {
        if (frameLeft == null || frameRight == null) return;

        int containerWidth = containerCameras.getWidth();
        int containerHeight = containerCameras.getHeight();
        int halfWidth = (containerWidth - 20) / 2;
        int halfHeight = (containerHeight - 20) / 2;
        int padding = 10;
        int vehicleControlWidth = 280;

        // 普通模式下保持车辆控制区域和左右视图可见
        if (frameVehicleControl != null) {
            frameVehicleControl.setVisibility(View.VISIBLE);
        }
        frameLeft.setVisibility(View.VISIBLE);
        frameRight.setVisibility(View.VISIBLE);

        // 普通模式下左右视图宽度，右视图右边缘与后视图对齐
        // 后视图右边缘 = containerWidth - padding
        // 右视图右边缘 = rightX + rightWidth = containerWidth - padding
        // rightX = padding * 3 + leftWidth + vehicleControlWidth
        // 所以 rightWidth = containerWidth - padding - rightX = containerWidth - padding - (padding * 3 + leftWidth + vehicleControlWidth)
        // 简化：leftWidth + rightWidth + vehicleControlWidth + padding * 4 = containerWidth
        // 让 leftWidth = rightWidth，则 2 * leftWidth = containerWidth - vehicleControlWidth - padding * 4
        int totalViewWidth = containerWidth - vehicleControlWidth - padding * 4;
        int defaultLeftWidth = totalViewWidth / 2;
        int defaultRightWidth = totalViewWidth / 2;
        int defaultHeight = halfHeight;
        int defaultLeftX = padding;
        int defaultLeftY = padding * 2 + halfHeight;
        int defaultRightX = padding * 2 + defaultLeftWidth + vehicleControlWidth;
        int defaultRightY = padding * 2 + halfHeight;

        // 获取保存的参数
        int leftWidth = appConfig.getNormalLeftWidth(defaultLeftWidth);
        int leftHeight = appConfig.getNormalLeftHeight(defaultHeight);
        int leftX = appConfig.getNormalLeftX(defaultLeftX);
        int leftY = appConfig.getNormalLeftY(defaultLeftY);
        int leftRotation = appConfig.getNormalLeftRotation(0);

        int rightWidth = appConfig.getNormalRightWidth(defaultRightWidth);
        int rightHeight = appConfig.getNormalRightHeight(defaultHeight);
        int rightX = appConfig.getNormalRightX(defaultRightX);
        int rightY = appConfig.getNormalRightY(defaultRightY);
        int rightRotation = appConfig.getNormalRightRotation(0);

        // 应用布局到画框
        setViewPosition(frameLeft, leftX, leftY, leftWidth, leftHeight);
        setViewPosition(frameRight, rightX, rightY, rightWidth, rightHeight);

        // 应用参数到画面（位置、大小、旋转）
        if (textureLeft != null) {
            applyRotationWithScale(textureLeft, leftRotation);
            textureLeft.setX(0);
            textureLeft.setY(0);
            android.widget.FrameLayout.LayoutParams leftParams = new android.widget.FrameLayout.LayoutParams(leftWidth, leftHeight);
            textureLeft.setLayoutParams(leftParams);
        }
        if (textureRight != null) {
            applyRotationWithScale(textureRight, rightRotation);
            textureRight.setX(0);
            textureRight.setY(0);
            android.widget.FrameLayout.LayoutParams rightParams = new android.widget.FrameLayout.LayoutParams(rightWidth, rightHeight);
            textureRight.setLayoutParams(rightParams);
        }

        AppLog.d(TAG, "普通模式布局已应用");
    }

    /**
     * 设置按钮为选中状态
     */
    private void setButtonSelected(Button button) {
        button.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#007AFF")));
    }

    /**
     * 设置按钮为未选中状态
     */
    private void setButtonUnselected(Button button) {
        button.setTextColor(android.graphics.Color.parseColor("#808080"));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#00000000")));
    }

    /**
     * 应用裁剪效果
     * 使用 clipBounds 来裁剪 TextureView 的显示区域
     */
    private void applyCrop(TextureView textureView, String cameraKey) {
        applyCropWithRetry(textureView, cameraKey, 0);
    }
    
    /**
     * 带重试的裁剪应用
     * @param retryCount 当前重试次数
     */
    private void applyCropWithRetry(TextureView textureView, String cameraKey, int retryCount) {
        if (textureView == null) return;
        
        // 最多重试 10 次，每次延迟 100ms
        final int MAX_RETRY = 10;
        
        int cropTop = appConfig.getCameraCrop(cameraKey, "top");
        int cropBottom = appConfig.getCameraCrop(cameraKey, "bottom");
        int cropLeft = appConfig.getCameraCrop(cameraKey, "left");
        int cropRight = appConfig.getCameraCrop(cameraKey, "right");
        
        // 如果没有裁剪配置，直接返回
        if (cropTop == 0 && cropBottom == 0 && cropLeft == 0 && cropRight == 0) {
            textureView.setClipBounds(null);
            return;
        }
        
        int width = textureView.getWidth();
        int height = textureView.getHeight();
        
        if (width <= 0 || height <= 0) {
            // 视图尚未布局完成，延迟应用
            if (retryCount < MAX_RETRY) {
                textureView.postDelayed(() -> applyCropWithRetry(textureView, cameraKey, retryCount + 1), 100);
                AppLog.d(TAG, cameraKey + " 裁剪等待布局，重试 " + (retryCount + 1));
            } else {
                AppLog.w(TAG, cameraKey + " 裁剪应用失败：视图尺寸为 0，已达最大重试次数");
            }
            return;
        }
        
        // 计算裁剪区域
        int left = cropLeft;
        int top = cropTop;
        int right = width - cropRight;
        int bottom = height - cropBottom;
        
        // 确保裁剪区域有效
        if (left >= right || top >= bottom) {
            // 裁剪区域无效，重置
            appConfig.resetCameraCrop(cameraKey);
            textureView.setClipBounds(null);
            Toast.makeText(context, "裁剪过大，已重置", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 应用裁剪
        android.graphics.Rect clipBounds = new android.graphics.Rect(left, top, right, bottom);
        textureView.setClipBounds(clipBounds);
        
        AppLog.d(TAG, cameraKey + " 裁剪应用成功: left=" + cropLeft + ", top=" + cropTop + 
                ", right=" + cropRight + ", bottom=" + cropBottom + " (视图尺寸: " + width + "x" + height + ")");
    }
    
    /**
     * 立即应用所有裁剪（用于布局恢复时，在容器显示之前调用）
     */
    private void applyAllCropsImmediately() {
        if (textureFront != null) applyCrop(textureFront, "front");
        if (textureBack != null) applyCrop(textureBack, "back");
        if (textureLeft != null) applyCrop(textureLeft, "left");
        if (textureRight != null) applyCrop(textureRight, "right");
        AppLog.d(TAG, "已立即应用裁剪配置");
    }
    
    /**
     * 应用所有摄像头的保存的裁剪配置
     * 使用更长的延迟确保 TextureView 已经有正确的尺寸
     * 仅在没有通过 restoreLayout 恢复时使用
     */
    private void applySavedCrops() {
        // 如果已经有布局数据（会在 restoreLayout 中应用裁剪），则跳过
        String savedData = appConfig.getCustomLayoutData();
        if (savedData != null && !savedData.isEmpty()) {
            AppLog.d(TAG, "布局数据存在，裁剪将在布局恢复时应用");
            return;
        }
        
        // 延迟 500ms 后应用裁剪，确保摄像头预览已经开始
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (textureFront != null) applyCrop(textureFront, "front");
            if (textureBack != null) applyCrop(textureBack, "back");
            if (textureLeft != null) applyCrop(textureLeft, "left");
            if (textureRight != null) applyCrop(textureRight, "right");
            AppLog.d(TAG, "已触发裁剪配置恢复（无布局数据模式）");
        }, 500);
    }
    
    /**
     * 设置编辑控制面板的按钮
     */
    private void setupEditControlButtons() {
        if (editControlsView == null) return;
        
        // 保存按钮
        Button btnSave = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_save_layout", "id", context.getPackageName()));
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                saveLayout();
                Toast.makeText(context, "布局已保存", Toast.LENGTH_SHORT).show();
            });
        }
        
        // 重置按钮
        Button btnReset = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_reset_layout", "id", context.getPackageName()));
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                resetLayout();
                Toast.makeText(context, "布局已重置", Toast.LENGTH_SHORT).show();
            });
        }
        
        // 重启按钮
        Button btnRestart = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_restart_app", "id", context.getPackageName()));
        if (btnRestart != null) {
            btnRestart.setOnClickListener(v -> {
                restartApp();
            });
        }
        
        // 按钮容器缩小
        Button btnButtonsShrink = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_buttons_shrink", "id", context.getPackageName()));
        if (btnButtonsShrink != null) {
            btnButtonsShrink.setOnClickListener(v -> adjustButtonSize(false));
        }
        
        // 按钮容器放大
        Button btnButtonsEnlarge = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_buttons_enlarge", "id", context.getPackageName()));
        if (btnButtonsEnlarge != null) {
            btnButtonsEnlarge.setOnClickListener(v -> adjustButtonSize(true));
        }
        
        // 按钮方向切换
        Button btnButtonsRotate = editControlsView.findViewById(
                context.getResources().getIdentifier("btn_buttons_rotate", "id", context.getPackageName()));
        if (btnButtonsRotate != null) {
            btnButtonsRotate.setOnClickListener(v -> {
                String currentOrientation = appConfig.getCustomButtonOrientation();
                String newOrientation = AppConfig.BUTTON_ORIENTATION_VERTICAL.equals(currentOrientation) ?
                        AppConfig.BUTTON_ORIENTATION_HORIZONTAL : AppConfig.BUTTON_ORIENTATION_VERTICAL;
                appConfig.setCustomButtonOrientation(newOrientation);
                
                // 通知监听器重新加载按钮布局
                if (buttonLayoutChangeListener != null) {
                    buttonLayoutChangeListener.onButtonLayoutChange(newOrientation);
                }
                
                Toast.makeText(context, "按钮方向: " + 
                        (newOrientation.equals(AppConfig.BUTTON_ORIENTATION_VERTICAL) ? "竖版" : "横版"), 
                        Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    /**
     * 设置单个摄像头容器的自由操控
     */
    private void setupCameraFrame(FrameLayout frame, String cameraId) {
        // 直接使用整个frame作为拖动区域（不需要角标）
        frame.setOnTouchListener(new DragTouchListener(frame, cameraId));
        
        // 查找缩小按钮
        int shrinkBtnId = context.getResources().getIdentifier(
                "btn_shrink_" + cameraId, "id", context.getPackageName());
        View shrinkBtn = frame.findViewById(shrinkBtnId);
        if (shrinkBtn != null) {
            shrinkBtn.setOnClickListener(v -> adjustSize(frame, cameraId, false));
        }
        
        // 查找放大按钮
        int enlargeBtnId = context.getResources().getIdentifier(
                "btn_enlarge_" + cameraId, "id", context.getPackageName());
        View enlargeBtn = frame.findViewById(enlargeBtnId);
        if (enlargeBtn != null) {
            enlargeBtn.setOnClickListener(v -> adjustSize(frame, cameraId, true));
        }
        
        // 查找隐藏按钮
        int hideBtnId = context.getResources().getIdentifier(
                "btn_hide_" + cameraId, "id", context.getPackageName());
        View hideBtn = frame.findViewById(hideBtnId);
        if (hideBtn != null) {
            hideBtn.setOnClickListener(v -> toggleVisibility(frame, cameraId));
        }
    }
    
    /**
     * 设置按钮容器的自由操控
     */
    private void setupButtonContainer(ViewGroup container) {
        // 按钮容器可以整体拖动
        // 需要先移除 layout_gravity，否则位置设置会被覆盖
        if (container.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) container.getLayoutParams();
            // 移除 gravity，改为使用绝对位置
            params.gravity = android.view.Gravity.NO_GRAVITY;
            // 如果宽度是 match_parent，改为 wrap_content 以支持拖动
            if (params.width == FrameLayout.LayoutParams.MATCH_PARENT) {
                params.width = FrameLayout.LayoutParams.WRAP_CONTENT;
            }
            container.setLayoutParams(params);
        }
        container.setOnTouchListener(new DragTouchListener(container, "buttons"));
    }
    
    /**
     * 调整按钮容器大小
     */
    public void adjustButtonSize(boolean enlarge) {
        if (buttonContainer != null) {
            adjustSize(buttonContainer, "buttons", enlarge);
        }
    }

    /**
     * 设置编辑模式
     * @param enabled true 显示编辑控制按钮，false 隐藏
     */
    public void setEditMode(boolean enabled) {
        this.editModeEnabled = enabled;
        
        // 显示/隐藏编辑控制视图
        if (editControlsView != null) {
            editControlsView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        
        // 显示/隐藏各个摄像头的控制按钮
        setControlButtonsVisibility(frameFront, "front", enabled);
        setControlButtonsVisibility(frameBack, "back", enabled);
        setControlButtonsVisibility(frameLeft, "left", enabled);
        setControlButtonsVisibility(frameRight, "right", enabled);
        
        AppLog.d(TAG, "编辑模式: " + (enabled ? "开启" : "关闭"));
    }
    
    /**
     * 设置控制按钮的可见性
     */
    private void setControlButtonsVisibility(FrameLayout frame, String cameraId, boolean visible) {
        if (frame == null) return;
        
        int visibility = visible ? View.VISIBLE : View.GONE;
        
        // 控制按钮容器（controls_front, controls_back 等）
        int controlsId = context.getResources().getIdentifier(
                "controls_" + cameraId, "id", context.getPackageName());
        View controlsContainer = frame.findViewById(controlsId);
        if (controlsContainer != null) {
            controlsContainer.setVisibility(visibility);
            AppLog.d(TAG, "控制按钮容器 controls_" + cameraId + " 可见性: " + visible);
        } else {
            AppLog.w(TAG, "未找到控制按钮容器: controls_" + cameraId);
        }
        
        // 拖动手柄（如果有的话）
        int dragHandleId = context.getResources().getIdentifier(
                "drag_handle_" + cameraId, "id", context.getPackageName());
        View dragHandle = frame.findViewById(dragHandleId);
        if (dragHandle != null) {
            dragHandle.setVisibility(visibility);
        }
    }

    /**
     * 切换视图可见性
     * 使用透明度而不是GONE，避免TextureView初始化问题
     */
    private void toggleVisibility(View view, String id) {
        boolean isCurrentlyVisible = layoutData.isVisible(id);
        
        if (isCurrentlyVisible) {
            // 隐藏
            view.setAlpha(0f);
            view.setClickable(false);
            view.setFocusable(false);
            layoutData.setVisible(id, false);
            AppLog.d(TAG, id + " 已隐藏");
        } else {
            // 显示
            view.setAlpha(1f);
            view.setClickable(true);
            view.setFocusable(true);
            layoutData.setVisible(id, true);
            AppLog.d(TAG, id + " 已显示");
        }
    }

    /**
     * 调整视图大小
     * @param view 目标视图
     * @param id 视图ID
     * @param enlarge true=放大, false=缩小
     */
    private void adjustSize(View view, String id, boolean enlarge) {
        float currentScale = layoutData.getScale(id);
        float newScale;
        
        if (enlarge) {
            newScale = Math.min(currentScale + SCALE_STEP, MAX_SCALE);
        } else {
            newScale = Math.max(currentScale - SCALE_STEP, MIN_SCALE);
        }
        
        if (newScale != currentScale) {
            layoutData.setScale(id, newScale);
            applyScale(view, newScale);
            AppLog.d(TAG, id + " 缩放: " + Math.round(newScale * 100) + "%");
        }
    }
    
    /**
     * 应用缩放比例
     * 从左上角开始缩放，使角标始终保持在左上角位置
     * 同时反向缩放控制按钮使其保持固定大小
     */
    private void applyScale(View view, float scale) {
        // 设置缩放中心点为左上角 (0, 0)
        // 这样缩放时左上角保持不动，角标自然在正确位置
        view.setPivotX(0);
        view.setPivotY(0);
        view.setScaleX(scale);
        view.setScaleY(scale);
        
        // 反向缩放控制按钮，使其保持固定大小
        if (view instanceof FrameLayout) {
            compensateControlButtonsScale((FrameLayout) view, scale);
        }
    }
    
    /**
     * 反向缩放控制按钮和角标，使其在画面缩放时保持固定大小
     */
    private void compensateControlButtonsScale(FrameLayout frame, float parentScale) {
        // 查找控制按钮容器和角标
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            String resourceName = "";
            try {
                resourceName = context.getResources().getResourceEntryName(child.getId());
            } catch (Exception e) {
                continue;
            }
            
            // 只反向缩放控制按钮，角标跟随画面缩放（小画面配小角标更直观）
            if (resourceName.startsWith("controls_")) {
                float compensateScale = 1.0f / parentScale;
                child.setScaleX(compensateScale);
                child.setScaleY(compensateScale);
                AppLog.d(TAG, "控制按钮补偿缩放: " + resourceName + " -> " + Math.round(compensateScale * 100) + "%");
            }
        }
    }

    /**
     * 保存当前布局
     */
    public void saveLayout() {
        // 更新布局数据中的位置和尺寸信息
        saveViewLayout(frameFront, "front");
        saveViewLayout(frameBack, "back");
        saveViewLayout(frameLeft, "left");
        saveViewLayout(frameRight, "right");
        saveViewLayout(frameVehicleControl, "vehicle");
        saveViewLayout(buttonContainer, "buttons");
        
        // 保存裁剪数据
        saveCropData("front");
        saveCropData("back");
        saveCropData("left");
        saveCropData("right");
        
        // 保存到配置
        appConfig.setCustomLayoutData(layoutData.toJson());
        AppLog.d(TAG, "布局已保存: " + layoutData.toJson());
    }
    
    /**
     * 保存单个摄像头的裁剪数据到布局数据
     */
    private void saveCropData(String cameraKey) {
        int top = appConfig.getCameraCrop(cameraKey, "top");
        int bottom = appConfig.getCameraCrop(cameraKey, "bottom");
        int left = appConfig.getCameraCrop(cameraKey, "left");
        int right = appConfig.getCameraCrop(cameraKey, "right");
        layoutData.setCrop(cameraKey, top, bottom, left, right);
    }
    
    /**
     * 从布局数据恢复裁剪配置到 AppConfig
     */
    private void restoreCropDataFromLayout() {
        restoreCropForCamera("front");
        restoreCropForCamera("back");
        restoreCropForCamera("left");
        restoreCropForCamera("right");
    }
    
    /**
     * 恢复单个摄像头的裁剪数据
     */
    private void restoreCropForCamera(String cameraKey) {
        if (layoutData.hasCrop(cameraKey)) {
            int top = layoutData.getCrop(cameraKey, "top");
            int bottom = layoutData.getCrop(cameraKey, "bottom");
            int left = layoutData.getCrop(cameraKey, "left");
            int right = layoutData.getCrop(cameraKey, "right");
            
            if (top >= 0) appConfig.setCameraCrop(cameraKey, "top", top);
            if (bottom >= 0) appConfig.setCameraCrop(cameraKey, "bottom", bottom);
            if (left >= 0) appConfig.setCameraCrop(cameraKey, "left", left);
            if (right >= 0) appConfig.setCameraCrop(cameraKey, "right", right);
            
            AppLog.d(TAG, cameraKey + " 裁剪恢复: top=" + top + ", bottom=" + bottom + 
                    ", left=" + left + ", right=" + right);
        }
    }
    
    /**
     * 保存单个视图的布局数据（位置和尺寸）
     */
    private void saveViewLayout(View view, String id) {
        if (view == null) return;
        
        // 保存位置（四舍五入为整数，避免浮点精度问题）
        float x = Math.round(view.getX());
        float y = Math.round(view.getY());
        layoutData.setPosition(id, x, y);
        
        // 保存尺寸（考虑缩放后的实际尺寸）
        int width = view.getWidth();
        int height = view.getHeight();
        if (width > 0 && height > 0) {
            layoutData.setSize(id, width, height);
            AppLog.d(TAG, id + " 保存尺寸: " + width + "x" + height + " 位置: (" + x + ", " + y + ")");
        }
    }

    /**
     * 重置布局到默认状态
     */
    public void resetLayout() {
        layoutData = new LayoutData();
        appConfig.clearCustomLayoutData();
        
        // 重置所有视图缩放和旋转（FrameLayout）
        resetViewTransform(frameFront);
        resetViewTransform(frameBack);
        resetViewTransform(frameLeft);
        resetViewTransform(frameRight);
        resetViewTransform(buttonContainer);
        
        // 重置所有裁剪配置
        resetAllCrops();
        
        // 重置摄像头旋转和镜像配置
        resetAllRotationAndMirror();
        
        // 重新设置初始位置（四宫格/双摄/单摄）
        if (containerCameras != null) {
            setupDefaultPositions();
        }
        
        // 显示所有视图
        showAllViews();
        
        AppLog.d(TAG, "布局已重置");
    }
    
    /**
     * 重启应用
     */
    /**
     * 重载界面（重新创建 Activity）
     */
    private void restartApp() {
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            
            // 重新创建 Activity，不关闭应用
            Toast.makeText(context, "正在重载界面...", Toast.LENGTH_SHORT).show();
            activity.recreate();
        }
    }
    
    /**
     * 重置所有摄像头的旋转和镜像配置
     */
    private void resetAllRotationAndMirror() {
        // 重置 AppConfig 中的配置
        appConfig.setCameraRotation("front", 0);
        appConfig.setCameraRotation("back", 0);
        appConfig.setCameraRotation("left", 0);
        appConfig.setCameraRotation("right", 0);
        appConfig.setCameraMirror("front", false);
        appConfig.setCameraMirror("back", false);
        appConfig.setCameraMirror("left", false);
        appConfig.setCameraMirror("right", false);
        
        // 重置 TextureView 的旋转和缩放
        resetTextureViewTransform(textureFront);
        resetTextureViewTransform(textureBack);
        resetTextureViewTransform(textureLeft);
        resetTextureViewTransform(textureRight);
        
        AppLog.d(TAG, "摄像头旋转和镜像已重置");
    }
    
    /**
     * 重置单个 TextureView 的变换
     */
    private void resetTextureViewTransform(TextureView textureView) {
        if (textureView == null) return;
        textureView.setRotation(0f);
        textureView.setScaleX(1.0f);
        textureView.setScaleY(1.0f);
    }
    
    /**
     * 重置所有摄像头的裁剪配置
     */
    private void resetAllCrops() {
        appConfig.resetCameraCrop("front");
        appConfig.resetCameraCrop("back");
        appConfig.resetCameraCrop("left");
        appConfig.resetCameraCrop("right");
        
        // 清除裁剪效果
        if (textureFront != null) textureFront.setClipBounds(null);
        if (textureBack != null) textureBack.setClipBounds(null);
        if (textureLeft != null) textureLeft.setClipBounds(null);
        if (textureRight != null) textureRight.setClipBounds(null);
    }
    
    /**
     * 重置单个视图的变换（缩放、旋转）
     */
    private void resetViewTransform(View view) {
        if (view == null) return;
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
        view.setRotation(0f);
        
        // 如果是 FrameLayout，也需要重置内部控制按钮容器的缩放
        if (view instanceof FrameLayout) {
            FrameLayout frame = (FrameLayout) view;
            for (int i = 0; i < frame.getChildCount(); i++) {
                View child = frame.getChildAt(i);
                try {
                    String resourceName = context.getResources().getResourceEntryName(child.getId());
                    if (resourceName.startsWith("controls_")) {
                        child.setScaleX(1.0f);
                        child.setScaleY(1.0f);
                        AppLog.d(TAG, "重置控制按钮容器缩放: " + resourceName);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }
    
    /**
     * 设置默认的摄像头位置
     */
    private void setupDefaultPositions() {
        int containerWidth = containerCameras.getWidth();
        int containerHeight = containerCameras.getHeight();
        
        if (containerWidth == 0 || containerHeight == 0) {
            return;
        }
        
        int padding = 4;
        int halfWidth = (containerWidth - padding * 3) / 2;
        int halfHeight = (containerHeight - padding * 3) / 2;
        
        // 根据配置的摄像头数量设置布局
        if (cameraCount == 1) {
            // 1摄：全屏
            if (frameFront != null) {
                frameFront.setVisibility(View.VISIBLE);
                setViewPosition(frameFront, padding, padding, containerWidth - padding * 2, containerHeight - padding * 2);
            }
        } else if (cameraCount == 2) {
            // 2摄：左右并排
            if (frameFront != null) {
                frameFront.setVisibility(View.VISIBLE);
                setViewPosition(frameFront, padding, padding, halfWidth, containerHeight - padding * 2);
            }
            if (frameBack != null) {
                frameBack.setVisibility(View.VISIBLE);
                setViewPosition(frameBack, padding * 2 + halfWidth, padding, halfWidth, containerHeight - padding * 2);
            }
        } else {
            // 4摄：四宫格，左右视图中间有车辆控制区域
            if (frameFront != null) {
                frameFront.setVisibility(View.VISIBLE);
                setViewPosition(frameFront, padding, padding, halfWidth, halfHeight);
            }
            if (frameBack != null) {
                frameBack.setVisibility(View.VISIBLE);
                setViewPosition(frameBack, padding * 2 + halfWidth, padding, halfWidth, halfHeight);
            }
            // 左视图宽度减少120dp，为车辆控制区域留出空间
            int leftViewWidth = halfWidth - 140;
            int rightViewWidth = halfWidth - 140;
            int vehicleControlWidth = 280;
            if (frameLeft != null) {
                frameLeft.setVisibility(View.VISIBLE);
                setViewPosition(frameLeft, padding, padding * 2 + halfHeight, leftViewWidth, halfHeight);
            }
            // 车辆控制区域放在左右视图中间
            if (frameVehicleControl != null) {
                frameVehicleControl.setVisibility(View.VISIBLE);
                setViewPosition(frameVehicleControl, padding * 2 + leftViewWidth, padding * 2 + halfHeight + (halfHeight - 520) / 2, vehicleControlWidth, 520);
            }
            if (frameRight != null) {
                frameRight.setVisibility(View.VISIBLE);
                setViewPosition(frameRight, padding * 3 + leftViewWidth + vehicleControlWidth, padding * 2 + halfHeight, rightViewWidth, halfHeight);
            }
        }
    }
    
    /**
     * 设置视图位置和大小
     */
    private void setViewPosition(View view, int x, int y, int width, int height) {
        if (view == null) return;
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(width, height);
        view.setLayoutParams(params);
        view.setX(x);
        view.setY(y);
    }

    /**
     * 显示所有视图
     */
    public void showAllViews() {
        showView(frameFront, "front");
        showView(frameBack, "back");
        showView(frameLeft, "left");
        showView(frameRight, "right");
        showView(frameVehicleControl, "vehicle");
        showView(buttonContainer, "buttons");
    }
    
    /**
     * 显示单个视图
     */
    private void showView(View view, String id) {
        if (view == null) return;
        view.setAlpha(1f);
        view.setClickable(true);
        view.setFocusable(true);
        layoutData.setVisible(id, true);
    }

    /**
     * 恢复保存的布局
     * @return 是否有保存的布局数据
     */
    private boolean restoreLayout() {
        String savedData = appConfig.getCustomLayoutData();
        boolean hasData = savedData != null && !savedData.isEmpty();
        
        if (hasData) {
            layoutData = LayoutData.fromJson(savedData);
            
            // 检查是否有完整的尺寸数据（至少有一个摄像头有尺寸数据）
            boolean hasCompleteData = layoutData.getWidth("front") > 0 || 
                                      layoutData.getWidth("back") > 0 ||
                                      layoutData.getWidth("left") > 0 ||
                                      layoutData.getWidth("right") > 0;
            
            if (hasCompleteData) {
                // 先设置容器透明（保持布局但不可见），避免恢复过程中的闪烁
                containerCameras.setAlpha(0f);
                
                // 从布局数据恢复裁剪配置到 AppConfig
                restoreCropDataFromLayout();
                
                // 等待容器布局完成后直接恢复保存的布局
                containerCameras.post(() -> {
                    // 直接恢复保存的位置、尺寸和其他状态
                    restoreViewState(frameFront, "front");
                    restoreViewState(frameBack, "back");
                    restoreViewState(frameLeft, "left");
                    restoreViewState(frameRight, "right");
                    restoreViewState(frameVehicleControl, "vehicle");
                    restoreViewState(buttonContainer, "buttons");
                    
                    // 延迟应用裁剪并显示容器（等待 TextureView 有有效尺寸）
                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                    handler.postDelayed(() -> {
                        applyAllCropsImmediately();
                        // 裁剪应用后显示容器
                        containerCameras.setAlpha(1f);
                        AppLog.d(TAG, "布局和裁剪恢复完成");
                    }, 300);
                });
            } else {
                // 没有完整的尺寸数据，使用默认布局
                AppLog.d(TAG, "保存的布局数据不完整，使用默认布局");
                return false;
            }
        }
        
        return hasData;
    }
    
    /**
     * 恢复单个视图的状态
     */
    private void restoreViewState(View view, String id) {
        if (view == null) return;
        
        // 恢复尺寸（如果有保存的尺寸）
        int savedWidth = layoutData.getWidth(id);
        int savedHeight = layoutData.getHeight(id);
        if (savedWidth > 0 && savedHeight > 0) {
            android.widget.FrameLayout.LayoutParams params = 
                    new android.widget.FrameLayout.LayoutParams(savedWidth, savedHeight);
            // 移除 gravity，使用绝对位置
            params.gravity = android.view.Gravity.NO_GRAVITY;
            view.setLayoutParams(params);
            AppLog.d(TAG, id + " 恢复尺寸: " + savedWidth + "x" + savedHeight);
        } else if (view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            // 即使没有保存尺寸，也需要移除 gravity 以支持位置恢复
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (params.gravity != android.view.Gravity.NO_GRAVITY) {
                params.gravity = android.view.Gravity.NO_GRAVITY;
                view.setLayoutParams(params);
            }
        }
        
        // 恢复位置
        float x = layoutData.getX(id);
        float y = layoutData.getY(id);
        // 只要有保存的位置数据就恢复（包括 0,0 位置）
        if (layoutData.getWidth(id) > 0) {  // 有保存过数据
            view.setX(x);
            view.setY(y);
            AppLog.d(TAG, id + " 恢复位置: (" + x + ", " + y + ")");
        }
        
        // 恢复缩放（始终应用，包括默认值1.0，以确保控制按钮补偿正确）
        float scale = layoutData.getScale(id);
        applyScale(view, scale);
        
        // 恢复可见性
        boolean visible = layoutData.isVisible(id);
        if (visible) {
            view.setAlpha(1f);
            view.setClickable(true);
            view.setFocusable(true);
        } else {
            view.setAlpha(0f);
            view.setClickable(false);
            view.setFocusable(false);
        }
    }

    /**
     * 加载布局数据
     */
    private void loadLayoutData() {
        String json = appConfig.getCustomLayoutData();
        if (json != null && !json.isEmpty()) {
            layoutData = LayoutData.fromJson(json);
        }
    }

    /**
     * 更新摄像头的宽高比（根据实际分辨率和旋转角度）
     * @param position 位置（front/back/left/right）
     * @param width 原始宽度
     * @param height 原始高度
     * @param rotation 旋转角度
     */
    public void updateCameraAspectRatio(String position, int width, int height, int rotation) {
        int[] displayRatio = AppConfig.calculateDisplayRatio(width, height, rotation);
        layoutData.setAspectRatio(position, displayRatio[0], displayRatio[1]);
        AppLog.d(TAG, position + " 宽高比: " + displayRatio[0] + ":" + displayRatio[1] + 
                " (旋转" + rotation + "°)");
    }
    
    /**
     * 获取摄像头的显示宽高比
     */
    public float getDisplayAspectRatio(String position) {
        return layoutData.getAspectRatio(position);
    }
    
    /**
     * 是否处于编辑模式
     */
    public boolean isEditModeEnabled() {
        return editModeEnabled;
    }

    /**
     * 拖动触摸监听器
     * 拖动时平滑移动，松手时网格吸附
     */
    private class DragTouchListener implements View.OnTouchListener {
        private final View targetView;
        private final String viewId;
        private float startX, startY;      // 触摸开始时视图的位置
        private float startRawX, startRawY; // 触摸开始时手指的屏幕位置
        private boolean isDragging = false;
        private static final float TOUCH_SLOP = 10f;  // 触发拖动的最小移动距离

        public DragTouchListener(View targetView, String viewId) {
            this.targetView = targetView;
            this.viewId = viewId;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            // 只有在编辑模式下才处理拖动
            if (!editModeEnabled) {
                return false;
            }
            
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 直接记录视图当前的 X/Y 位置和手指位置
                    startX = targetView.getX();
                    startY = targetView.getY();
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // 计算手指移动的距离
                    float deltaX = event.getRawX() - startRawX;
                    float deltaY = event.getRawY() - startRawY;
                    
                    // 检查是否超过触发阈值（避免误触）
                    if (!isDragging) {
                        if (Math.abs(deltaX) > TOUCH_SLOP || Math.abs(deltaY) > TOUCH_SLOP) {
                            isDragging = true;
                            // 将起始位置吸附到网格，确保移动基于网格对齐的位置
                            startX = snapToGrid(startX);
                            startY = snapToGrid(startY);
                        } else {
                            return true;  // 还未开始拖动，等待
                        }
                    }
                    
                    // 计算新位置 = 初始位置 + 移动距离，然后吸附到网格
                    float newX = snapToGrid(startX + deltaX);
                    float newY = snapToGrid(startY + deltaY);
                    
                    // 应用边界限制
                    float[] bounded = applyBoundaryLimits(newX, newY);
                    
                    // 设置位置（每次移动都吸附到 20dp 网格，方便对齐）
                    targetView.setX(bounded[0]);
                    targetView.setY(bounded[1]);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDragging) {
                        // 松手时进行网格吸附
                        float finalX = snapToGrid(targetView.getX());
                        float finalY = snapToGrid(targetView.getY());
                        
                        // 边缘吸附并确保在边界内
                        float[] snapped = applyEdgeSnapping(finalX, finalY);
                        
                        targetView.setX(snapped[0]);
                        targetView.setY(snapped[1]);
                        
                        // 更新布局数据
                        layoutData.setPosition(viewId, snapped[0], snapped[1]);
                        
                        // 保存到对应模式的 appConfig 参数
                        savePositionToCurrentMode(viewId, (int) snapped[0], (int) snapped[1], 
                                targetView.getWidth(), targetView.getHeight());
                        
                        AppLog.d(TAG, viewId + " 移动到 (" + (int) snapped[0] + ", " + (int) snapped[1] + ")");
                    }
                    return true;

                default:
                    return false;
            }
        }
        
        /**
         * 应用边界限制（已禁用）
         * 允许视图自由移动到任意位置，包括边界外
         */
        private float[] applyBoundaryLimits(float x, float y) {
            // 不做任何边界限制，直接返回原始坐标
            return new float[]{x, y};
        }
        
        /**
         * 边缘吸附处理（已禁用边界限制）
         * 只保留网格对齐功能，允许自由移动到边界外
         */
        private float[] applyEdgeSnapping(float x, float y) {
            // 只做网格吸附，不做边界限制
            // 坐标已经在 ACTION_MOVE 中吸附过网格，这里直接返回
            return new float[]{x, y};
        }
        
        /**
         * 吸附到网格
         */
        private float snapToGrid(float value) {
            return Math.round(value / GRID_SIZE) * GRID_SIZE;
        }
    }

    /**
     * 保存位置到当前模式的 appConfig 参数
     * 根据当前选中的模式（前轮/后轮/普通）保存到对应的参数
     */
    private void savePositionToCurrentMode(String viewId, int x, int y, int width, int height) {
        // 判断当前模式
        Button btnFrontWheel = null;
        Button btnRearWheel = null;
        if (frameVehicleControl != null) {
            btnFrontWheel = frameVehicleControl.findViewById(R.id.btn_front_wheel);
            btnRearWheel = frameVehicleControl.findViewById(R.id.btn_rear_wheel);
        }

        boolean isFrontWheelMode = btnFrontWheel != null && btnFrontWheel.getTag() != null && (Boolean) btnFrontWheel.getTag();
        boolean isRearWheelMode = btnRearWheel != null && btnRearWheel.getTag() != null && (Boolean) btnRearWheel.getTag();

        // 只保存左视图和右视图的位置
        if ("left".equals(viewId)) {
            if (isFrontWheelMode) {
                appConfig.setFrontWheelLeftViewParams(width, height, x, y, appConfig.getFrontWheelLeftRotation(0));
                AppLog.d(TAG, "前轮模式左视图参数已保存");
            } else if (isRearWheelMode) {
                appConfig.setRearWheelLeftViewParams(width, height, x, y, appConfig.getRearWheelLeftRotation(0));
                AppLog.d(TAG, "后轮模式左视图参数已保存");
            } else {
                appConfig.setNormalLeftViewParams(width, height, x, y, appConfig.getNormalLeftRotation(0));
                AppLog.d(TAG, "普通模式左视图参数已保存");
            }
        } else if ("right".equals(viewId)) {
            if (isFrontWheelMode) {
                appConfig.setFrontWheelRightViewParams(width, height, x, y, appConfig.getFrontWheelRightRotation(0));
                AppLog.d(TAG, "前轮模式右视图参数已保存");
            } else if (isRearWheelMode) {
                appConfig.setRearWheelRightViewParams(width, height, x, y, appConfig.getRearWheelRightRotation(0));
                AppLog.d(TAG, "后轮模式右视图参数已保存");
            } else {
                appConfig.setNormalRightViewParams(width, height, x, y, appConfig.getNormalRightRotation(0));
                AppLog.d(TAG, "普通模式右视图参数已保存");
            }
        }
    }

    /**
     * 布局数据类
     */
    public static class LayoutData {
        private JSONObject data;

        public LayoutData() {
            data = new JSONObject();
        }

        public static LayoutData fromJson(String json) {
            LayoutData layoutData = new LayoutData();
            try {
                layoutData.data = new JSONObject(json);
            } catch (JSONException e) {
                AppLog.e(TAG, "解析布局数据失败", e);
            }
            return layoutData;
        }

        public String toJson() {
            return data.toString();
        }

        private JSONObject getOrCreateObject(String key) {
            try {
                if (!data.has(key)) {
                    data.put(key, new JSONObject());
                }
                return data.getJSONObject(key);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        public void setPosition(String id, float x, float y) {
            try {
                JSONObject obj = getOrCreateObject(id);
                obj.put("x", x);
                obj.put("y", y);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存位置失败", e);
            }
        }

        public float getX(String id) {
            try {
                if (data.has(id)) {
                    return (float) data.getJSONObject(id).optDouble("x", 0);
                }
            } catch (JSONException e) {
                // ignore
            }
            return 0;
        }

        public float getY(String id) {
            try {
                if (data.has(id)) {
                    return (float) data.getJSONObject(id).optDouble("y", 0);
                }
            } catch (JSONException e) {
                // ignore
            }
            return 0;
        }

        public void setScale(String id, float scale) {
            try {
                JSONObject obj = getOrCreateObject(id);
                obj.put("scale", scale);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存缩放失败", e);
            }
        }

        public float getScale(String id) {
            try {
                if (data.has(id)) {
                    return (float) data.getJSONObject(id).optDouble("scale", 1.0);
                }
            } catch (JSONException e) {
                // ignore
            }
            return 1.0f;
        }

        public void setVisible(String id, boolean visible) {
            try {
                JSONObject obj = getOrCreateObject(id);
                obj.put("visible", visible);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存可见性失败", e);
            }
        }

        public boolean isVisible(String id) {
            try {
                if (data.has(id)) {
                    return data.getJSONObject(id).optBoolean("visible", true);
                }
            } catch (JSONException e) {
                // ignore
            }
            return true;
        }

        public void setAspectRatio(String id, int width, int height) {
            try {
                JSONObject obj = getOrCreateObject(id);
                obj.put("ratioWidth", width);
                obj.put("ratioHeight", height);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存宽高比失败", e);
            }
        }

        public float getAspectRatio(String id) {
            try {
                if (data.has(id)) {
                    JSONObject obj = data.getJSONObject(id);
                    int w = obj.optInt("ratioWidth", 16);
                    int h = obj.optInt("ratioHeight", 9);
                    return (float) w / h;
                }
            } catch (JSONException e) {
                // ignore
            }
            return 16f / 9f;  // 默认16:9
        }

        /**
         * 保存视图的宽高
         */
        public void setSize(String id, int width, int height) {
            try {
                JSONObject obj = getOrCreateObject(id);
                obj.put("width", width);
                obj.put("height", height);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存尺寸失败", e);
            }
        }

        /**
         * 获取保存的宽度
         * @return 宽度，-1 表示未设置
         */
        public int getWidth(String id) {
            try {
                if (data.has(id)) {
                    return data.getJSONObject(id).optInt("width", -1);
                }
            } catch (JSONException e) {
                // ignore
            }
            return -1;
        }

        /**
         * 获取保存的高度
         * @return 高度，-1 表示未设置
         */
        public int getHeight(String id) {
            try {
                if (data.has(id)) {
                    return data.getJSONObject(id).optInt("height", -1);
                }
            } catch (JSONException e) {
                // ignore
            }
            return -1;
        }
        
        /**
         * 保存裁剪数据
         * @param id 摄像头标识（front/back/left/right）
         * @param top 上裁剪像素
         * @param bottom 下裁剪像素
         * @param left 左裁剪像素
         * @param right 右裁剪像素
         */
        public void setCrop(String id, int top, int bottom, int left, int right) {
            try {
                JSONObject obj = getOrCreateObject(id);
                JSONObject cropObj = new JSONObject();
                cropObj.put("top", top);
                cropObj.put("bottom", bottom);
                cropObj.put("left", left);
                cropObj.put("right", right);
                obj.put("crop", cropObj);
            } catch (JSONException e) {
                AppLog.e(TAG, "保存裁剪数据失败", e);
            }
        }
        
        /**
         * 获取裁剪数据
         * @param id 摄像头标识
         * @param direction 方向（top/bottom/left/right）
         * @return 裁剪像素值，-1 表示未设置
         */
        public int getCrop(String id, String direction) {
            try {
                if (data.has(id)) {
                    JSONObject obj = data.getJSONObject(id);
                    if (obj.has("crop")) {
                        return obj.getJSONObject("crop").optInt(direction, -1);
                    }
                }
            } catch (JSONException e) {
                // ignore
            }
            return -1;
        }
        
        /**
         * 检查是否有裁剪数据
         */
        public boolean hasCrop(String id) {
            try {
                if (data.has(id)) {
                    return data.getJSONObject(id).has("crop");
                }
            } catch (JSONException e) {
                // ignore
            }
            return false;
        }
    }
}
