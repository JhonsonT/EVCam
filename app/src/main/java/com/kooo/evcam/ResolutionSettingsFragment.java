package com.kooo.evcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画质设置界面 Fragment（分辨率、码率）
 */
public class ResolutionSettingsFragment extends Fragment {

    private static final String TAG = "ResolutionSettings";

    private AppConfig appConfig;
    
    // 分辨率相关
    private Spinner resolutionSpinner;
    private TextView resolutionDescText;
    private List<String> resolutionOptions = new ArrayList<>();
    private String selectedResolution;
    
    // 码率相关
    private Spinner bitrateSpinner;
    private TextView bitrateDescText;
    private List<String> bitrateOptions = new ArrayList<>();
    private String selectedBitrateLevel;
    
    // 帧率相关
    private Spinner framerateSpinner;
    private TextView framerateDescText;
    private List<String> framerateOptions = new ArrayList<>();
    private String selectedFramerateLevel;
    
    // 信息显示
    private TextView currentParamsText;
    private TextView hardwareInfoText;
    
    // 摄像头信息
    private Map<String, CameraInfo> cameraInfoMap = new LinkedHashMap<>();
    
    // 是否正在初始化
    private boolean isInitializing = false;

    /**
     * 摄像头信息类
     */
    private static class CameraInfo {
        String cameraId;
        List<Size> supportedResolutions = new ArrayList<>();
        int maxFps = 30;  // 最大帧率
        int minFps = 15;  // 最小帧率
        String facing = "未知";  // 朝向
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_resolution_settings, container, false);

        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
        }

        // 初始化控件
        initViews(view);

        // 检测摄像头信息
        detectCameraInfo();

        // 初始化分辨率选择器
        initResolutionSpinner();
        
        // 初始化帧率选择器（必须在码率之前，因为码率计算依赖帧率）
        initFramerateSpinner();
        
        // 初始化码率选择器
        initBitrateSpinner();

        // 显示调试信息
        displayDebugInfo();

        // 设置返回按钮
        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // 设置保存按钮
        Button btnSave = view.findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());

        return view;
    }

    private void initViews(View view) {
        resolutionSpinner = view.findViewById(R.id.spinner_resolution);
        resolutionDescText = view.findViewById(R.id.tv_resolution_desc);
        bitrateSpinner = view.findViewById(R.id.spinner_bitrate);
        bitrateDescText = view.findViewById(R.id.tv_bitrate_desc);
        framerateSpinner = view.findViewById(R.id.spinner_framerate);
        framerateDescText = view.findViewById(R.id.tv_framerate_desc);
        currentParamsText = view.findViewById(R.id.tv_current_params);
        hardwareInfoText = view.findViewById(R.id.tv_hardware_info);
    }

    /**
     * 检测所有摄像头信息（分辨率和帧率）
     */
    private void detectCameraInfo() {
        if (getContext() == null) {
            return;
        }

        cameraInfoMap.clear();

        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();

            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    CameraInfo info = new CameraInfo();
                    info.cameraId = cameraId;
                    
                    // 获取摄像头朝向
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null) {
                        switch (facing) {
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                info.facing = "前置";
                                break;
                            case CameraCharacteristics.LENS_FACING_BACK:
                                info.facing = "后置";
                                break;
                            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                                info.facing = "外置";
                                break;
                        }
                    }
                    
                    // 获取支持的帧率范围
                    Range<Integer>[] fpsRanges = characteristics.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (fpsRanges != null && fpsRanges.length > 0) {
                        int maxFps = 0;
                        int minFps = Integer.MAX_VALUE;
                        for (Range<Integer> range : fpsRanges) {
                            if (range.getUpper() > maxFps) {
                                maxFps = range.getUpper();
                            }
                            if (range.getLower() < minFps) {
                                minFps = range.getLower();
                            }
                        }
                        info.maxFps = maxFps;
                        info.minFps = minFps;
                    }

                    // 获取支持的分辨率
                    StreamConfigurationMap map = characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                        if (sizes == null || sizes.length == 0) {
                            sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                        }

                        if (sizes != null && sizes.length > 0) {
                            for (Size size : sizes) {
                                info.supportedResolutions.add(size);
                            }
                            // 按分辨率从大到小排序
                            Collections.sort(info.supportedResolutions, (s1, s2) -> {
                                int pixels1 = s1.getWidth() * s1.getHeight();
                                int pixels2 = s2.getWidth() * s2.getHeight();
                                return pixels2 - pixels1;
                            });
                        }
                    }
                    
                    cameraInfoMap.put(cameraId, info);
                    
                } catch (CameraAccessException e) {
                    AppLog.e(TAG, "获取摄像头 " + cameraId + " 特性失败", e);
                }
            }

            AppLog.d(TAG, "检测到 " + cameraInfoMap.size() + " 个摄像头");

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "获取摄像头列表失败", e);
        }
    }

    /**
     * 初始化分辨率选择器
     */
    private void initResolutionSpinner() {
        if (resolutionSpinner == null || getContext() == null) {
            return;
        }

        isInitializing = true;

        // 构建分辨率选项列表
        resolutionOptions.clear();
        resolutionOptions.add("默认 (1280×800)");

        // 收集所有摄像头支持的分辨率（去重）
        Set<String> allResolutions = new LinkedHashSet<>();
        for (CameraInfo info : cameraInfoMap.values()) {
            for (Size size : info.supportedResolutions) {
                allResolutions.add(size.getWidth() + "x" + size.getHeight());
            }
        }

        // 按像素数从大到小排序
        List<String> sortedResolutions = new ArrayList<>(allResolutions);
        Collections.sort(sortedResolutions, (r1, r2) -> {
            int[] p1 = AppConfig.parseResolution(r1);
            int[] p2 = AppConfig.parseResolution(r2);
            if (p1 == null || p2 == null) return 0;
            return (p2[0] * p2[1]) - (p1[0] * p1[1]);
        });

        resolutionOptions.addAll(sortedResolutions);

        // 设置适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                resolutionOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentResolution = (appConfig != null) ? appConfig.getTargetResolution() : AppConfig.RESOLUTION_DEFAULT;
        selectedResolution = currentResolution;
        int selectedIndex = 0;
        if (!AppConfig.RESOLUTION_DEFAULT.equals(currentResolution)) {
            for (int i = 1; i < resolutionOptions.size(); i++) {
                if (resolutionOptions.get(i).equals(currentResolution)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        resolutionSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing) {
                    return;
                }

                if (position == 0) {
                    selectedResolution = AppConfig.RESOLUTION_DEFAULT;
                    resolutionDescText.setText("默认：优先匹配 1280×800，否则选择最接近的分辨率");
                } else {
                    selectedResolution = resolutionOptions.get(position);
                    resolutionDescText.setText("将优先匹配 " + selectedResolution + "，如果摄像头不支持则选择最接近的");
                }
                
                // 更新码率描述（因为分辨率变化会影响推荐码率）
                updateBitrateDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        resolutionSpinner.post(() -> isInitializing = false);
    }

    /**
     * 初始化码率选择器
     */
    private void initBitrateSpinner() {
        if (bitrateSpinner == null || getContext() == null) {
            return;
        }

        // 构建码率选项
        bitrateOptions.clear();
        bitrateOptions.add("低（省空间）");
        bitrateOptions.add("标准（推荐）");
        bitrateOptions.add("高（高画质）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                bitrateOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bitrateSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentLevel = appConfig.getBitrateLevel();
        selectedBitrateLevel = currentLevel;
        int selectedIndex = 1;  // 默认标准
        if (AppConfig.BITRATE_LOW.equals(currentLevel)) {
            selectedIndex = 0;
        } else if (AppConfig.BITRATE_HIGH.equals(currentLevel)) {
            selectedIndex = 2;
        }
        bitrateSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        selectedBitrateLevel = AppConfig.BITRATE_LOW;
                        break;
                    case 2:
                        selectedBitrateLevel = AppConfig.BITRATE_HIGH;
                        break;
                    default:
                        selectedBitrateLevel = AppConfig.BITRATE_MEDIUM;
                        break;
                }
                updateBitrateDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 初始化描述
        updateBitrateDescription();
    }

    /**
     * 初始化帧率选择器
     */
    private void initFramerateSpinner() {
        if (framerateSpinner == null || getContext() == null) {
            return;
        }

        // 构建帧率选项
        framerateOptions.clear();
        framerateOptions.add("标准（推荐）");
        framerateOptions.add("低（省空间）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                framerateOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        framerateSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentLevel = appConfig.getFramerateLevel();
        selectedFramerateLevel = currentLevel;
        int selectedIndex = 0;  // 默认标准
        if (AppConfig.FRAMERATE_LOW.equals(currentLevel)) {
            selectedIndex = 1;
        }
        framerateSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        framerateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 1) {
                    selectedFramerateLevel = AppConfig.FRAMERATE_LOW;
                } else {
                    selectedFramerateLevel = AppConfig.FRAMERATE_STANDARD;
                }
                updateFramerateDescription();
                updateBitrateDescription();  // 帧率变化会影响码率计算
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 初始化描述
        updateFramerateDescription();
    }

    /**
     * 更新帧率描述
     */
    private void updateFramerateDescription() {
        if (framerateDescText == null) {
            return;
        }

        int standardFps = getStandardFrameRate();
        int lowFps = Math.max(10, standardFps / 2);

        String desc = String.format("标准: %dfps | 低: %dfps", standardFps, lowFps);
        framerateDescText.setText(desc);
    }

    /**
     * 获取标准帧率（接近30fps的硬件支持帧率）
     */
    private int getStandardFrameRate() {
        int maxFps = 30;
        for (CameraInfo info : cameraInfoMap.values()) {
            if (info.maxFps > 0) {
                maxFps = info.maxFps;
                break;  // 假设所有摄像头帧率相同
            }
        }
        return AppConfig.getStandardFrameRate(maxFps);
    }

    /**
     * 根据当前选择获取实际帧率
     */
    private int getSelectedFrameRate() {
        int standardFps = getStandardFrameRate();
        // 防止初始化顺序导致的 null 问题
        if (selectedFramerateLevel != null && AppConfig.FRAMERATE_LOW.equals(selectedFramerateLevel)) {
            return Math.max(10, standardFps / 2);
        }
        return standardFps;
    }

    /**
     * 更新码率描述（显示计算出的码率值）
     */
    private void updateBitrateDescription() {
        if (bitrateDescText == null) {
            return;
        }

        // 获取目标分辨率
        int width = 1280;
        int height = 800;
        if (!AppConfig.RESOLUTION_DEFAULT.equals(selectedResolution)) {
            int[] parsed = AppConfig.parseResolution(selectedResolution);
            if (parsed != null) {
                width = parsed[0];
                height = parsed[1];
            }
        }

        // 获取帧率（根据用户选择的帧率等级）
        int frameRate = getSelectedFrameRate();

        // 计算各等级码率
        int baseBitrate = AppConfig.calculateBitrate(width, height, frameRate);
        
        int lowBitrate = roundToHalfMbps(baseBitrate / 2);
        int mediumBitrate = roundToHalfMbps(baseBitrate);
        int highBitrate = roundToHalfMbps(baseBitrate * 3 / 2);

        String desc = String.format(
                "分辨率 %dx%d @ %dfps\n低: %s | 标准: %s | 高: %s",
                width, height, frameRate,
                AppConfig.formatBitrate(lowBitrate),
                AppConfig.formatBitrate(mediumBitrate),
                AppConfig.formatBitrate(highBitrate)
        );
        bitrateDescText.setText(desc);
    }
    
    /**
     * 四舍五入到 0.5Mbps
     */
    private int roundToHalfMbps(int bitrate) {
        int halfMbps = 500000;
        int rounded = ((bitrate + halfMbps / 2) / halfMbps) * halfMbps;
        return Math.max(halfMbps, Math.min(rounded, 20000000));
    }

    /**
     * 显示调试信息
     */
    private void displayDebugInfo() {
        displayCurrentParams();
        displayHardwareInfo();
    }

    /**
     * 显示当前录制参数
     */
    private void displayCurrentParams() {
        if (currentParamsText == null || getActivity() == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        
        // 从 MainActivity 获取当前分辨率信息
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            String resInfo = mainActivity.getCurrentCameraResolutionsInfo();
            if (resInfo != null && !resInfo.isEmpty()) {
                sb.append("【实际分辨率】\n").append(resInfo).append("\n\n");
            }
        }
        
        // 当前配置
        String targetRes = appConfig.getTargetResolution();
        String bitrateLevel = AppConfig.getBitrateLevelDisplayName(appConfig.getBitrateLevel());
        String framerateLevel = AppConfig.getFramerateLevelDisplayName(appConfig.getFramerateLevel());
        int standardFps = getStandardFrameRate();
        int actualFps = appConfig.getActualFrameRate(standardFps);
        
        sb.append("【当前配置】\n");
        sb.append("目标分辨率: ").append(AppConfig.RESOLUTION_DEFAULT.equals(targetRes) ? "默认 (1280×800)" : targetRes).append("\n");
        sb.append("码率等级: ").append(bitrateLevel).append("\n");
        sb.append("帧率等级: ").append(framerateLevel).append(" (").append(actualFps).append("fps)");

        currentParamsText.setText(sb.toString());
    }

    /**
     * 显示硬件信息
     */
    private void displayHardwareInfo() {
        if (hardwareInfoText == null) {
            return;
        }

        if (cameraInfoMap.isEmpty()) {
            hardwareInfoText.setText("未检测到摄像头");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, CameraInfo> entry : cameraInfoMap.entrySet()) {
            String cameraId = entry.getKey();
            CameraInfo info = entry.getValue();

            sb.append("摄像头 ").append(cameraId).append(" (").append(info.facing).append(")\n");
            sb.append("  帧率: ").append(info.minFps).append("-").append(info.maxFps).append(" fps\n");
            sb.append("  分辨率:\n");
            
            int count = 0;
            for (Size size : info.supportedResolutions) {
                sb.append("    ").append(size.getWidth()).append("×").append(size.getHeight());
                count++;
                if (count >= 5) {
                    sb.append("\n    ... 共 ").append(info.supportedResolutions.size()).append(" 种");
                    break;
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        hardwareInfoText.setText(sb.toString().trim());
    }

    /**
     * 保存配置
     */
    private void saveConfig() {
        if (appConfig == null || getContext() == null) {
            return;
        }

        // 保存分辨率
        String oldResolution = appConfig.getTargetResolution();
        appConfig.setTargetResolution(selectedResolution);
        
        // 保存码率
        String oldBitrate = appConfig.getBitrateLevel();
        appConfig.setBitrateLevel(selectedBitrateLevel);
        
        // 保存帧率
        String oldFramerate = appConfig.getFramerateLevel();
        appConfig.setFramerateLevel(selectedFramerateLevel);

        String resolutionName = AppConfig.RESOLUTION_DEFAULT.equals(selectedResolution) 
                ? "默认 (1280×800)" 
                : selectedResolution;
        String bitrateName = AppConfig.getBitrateLevelDisplayName(selectedBitrateLevel);
        String framerateName = AppConfig.getFramerateLevelDisplayName(selectedFramerateLevel);
        
        Toast.makeText(getContext(), 
                "设置已保存\n分辨率: " + resolutionName + "\n码率: " + bitrateName + 
                "\n帧率: " + framerateName + "\n重启应用后生效", 
                Toast.LENGTH_LONG).show();
        
        AppLog.d(TAG, "配置已保存: 分辨率 " + oldResolution + " -> " + selectedResolution + 
                ", 码率 " + oldBitrate + " -> " + selectedBitrateLevel +
                ", 帧率 " + oldFramerate + " -> " + selectedFramerateLevel);

        // 返回上一级
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }
}
