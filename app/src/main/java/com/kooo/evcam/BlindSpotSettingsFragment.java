package com.kooo.evcam;

import android.content.res.AssetManager;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 补盲选项设置界面
 */
public class BlindSpotSettingsFragment extends Fragment {
    private static final String TAG = "BlindSpotSettingsFragment";

    private Button openLabButton;

    private SwitchMaterial turnSignalLinkageSwitch;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private EditText turnSignalLeftLogEditText;
    private EditText turnSignalRightLogEditText;

    private SwitchMaterial blindSpotGlobalSwitch;
    private android.widget.LinearLayout subFeaturesContainer;
    private SwitchMaterial secondaryBlindSpotSwitch;
    private Button adjustSecondaryBlindSpotWindowButton;
    private SwitchMaterial mockFloatingSwitch;
    private SwitchMaterial blindSpotCorrectionSwitch;
    private Button adjustBlindSpotCorrectionButton;
    private Button logcatDebugButton;
    private android.widget.EditText logFilterEditText;
    private Button menuButton;
    private Button homeButton;

    private AppConfig appConfig;
    private boolean disclaimerDialogShown = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        initViews(view);
        loadSettings();
        setupListeners();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        maybeShowDisclaimerDialog();
    }

    private void initViews(View view) {
        // 全局开关
        blindSpotGlobalSwitch = view.findViewById(R.id.switch_blind_spot_global);
        subFeaturesContainer = view.findViewById(R.id.blind_spot_sub_features_container);

        openLabButton = view.findViewById(R.id.btn_open_lab);

        // 转向灯联动
        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        turnSignalLeftLogEditText = view.findViewById(R.id.et_turn_signal_left_log);
        turnSignalRightLogEditText = view.findViewById(R.id.et_turn_signal_right_log);

        secondaryBlindSpotSwitch = view.findViewById(R.id.switch_secondary_blind_spot_display);
        adjustSecondaryBlindSpotWindowButton = view.findViewById(R.id.btn_adjust_secondary_blind_spot_window);
        
        mockFloatingSwitch = view.findViewById(R.id.switch_mock_floating);

        blindSpotCorrectionSwitch = view.findViewById(R.id.switch_blind_spot_correction);
        adjustBlindSpotCorrectionButton = view.findViewById(R.id.btn_adjust_blind_spot_correction);
        
        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        // 加载抖音二维码
        ImageView douyinQrCode = view.findViewById(R.id.img_douyin_qrcode);
        loadAssetImage(douyinQrCode, "douyin.jpg");
    }

    private void loadAssetImage(ImageView imageView, String assetName) {
        try {
            AssetManager am = requireContext().getAssets();
            try (InputStream is = am.open(assetName)) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
        } catch (Exception e) {
            imageView.setVisibility(View.GONE);
        }
    }

    private void loadSettings() {
        // 全局开关
        boolean globalEnabled = appConfig.isBlindSpotGlobalEnabled();
        blindSpotGlobalSwitch.setChecked(globalEnabled);
        updateSubFeaturesVisibility(globalEnabled);

        // 转向灯联动
        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        tvTurnSignalTimeout.setText(timeout + "s");
        turnSignalLeftLogEditText.setText(appConfig.getTurnSignalLeftTriggerLog());
        turnSignalRightLogEditText.setText(appConfig.getTurnSignalRightTriggerLog());

        secondaryBlindSpotSwitch.setChecked(appConfig.isSecondaryDisplayEnabled());

        mockFloatingSwitch.setChecked(appConfig.isMockTurnSignalFloatingEnabled());

        blindSpotCorrectionSwitch.setChecked(appConfig.isBlindSpotCorrectionEnabled());
    }

    private void updateSubFeaturesVisibility(boolean globalEnabled) {
        // 全局开关关闭时，隐藏所有子功能区域
        subFeaturesContainer.setVisibility(globalEnabled ? View.VISIBLE : View.GONE);
    }

    private void setupListeners() {
        // 全局开关
        blindSpotGlobalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotGlobalEnabled(isChecked);
            updateSubFeaturesVisibility(isChecked);
            if (!isChecked) {
                // 关闭时，停止补盲服务
                requireContext().stopService(new android.content.Intent(requireContext(), BlindSpotService.class));
            } else {
                // 开启时，如果有子功能已配置，启动服务
                BlindSpotService.update(requireContext());
            }
        });

        openLabButton.setOnClickListener(v -> {
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new BlindSpotLabFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        turnSignalLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                turnSignalLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setTurnSignalLinkageEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSignalTimeout.setText(progress + "s");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setTurnSignalTimeout(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
        });

        android.text.TextWatcher turnSignalLogWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (turnSignalLeftLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomLeftTriggerLog(s.toString());
                } else if (turnSignalRightLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomRightTriggerLog(s.toString());
                } else {
                    return;
                }
                BlindSpotService.update(requireContext());
            }
        };
        turnSignalLeftLogEditText.addTextChangedListener(turnSignalLogWatcher);
        turnSignalRightLogEditText.addTextChangedListener(turnSignalLogWatcher);

        secondaryBlindSpotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                secondaryBlindSpotSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setSecondaryDisplayEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        mockFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                mockFloatingSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setMockTurnSignalFloatingEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        blindSpotCorrectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotCorrectionEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        adjustBlindSpotCorrectionButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new BlindSpotCorrectionFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        adjustSecondaryBlindSpotWindowButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new SecondaryBlindSpotAdjustFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        logcatDebugButton.setOnClickListener(v -> {
            String keyword = logFilterEditText.getText().toString().trim();
            if (keyword.isEmpty()) {
                // 没有输入关键词时弹窗提示
                new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("提示")
                    .setMessage("未输入过滤关键字，日志量可能很大，可能导致界面卡顿。\n\n建议输入关键字进行过滤，是否继续？")
                    .setPositiveButton("继续打开", (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                        intent.putExtra("filter_keyword", "");
                        startActivity(intent);
                    })
                    .setNegativeButton("返回输入", null)
                    .show();
            } else {
                android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                intent.putExtra("filter_keyword", keyword);
                startActivity(intent);
            }
        });

        menuButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).toggleDrawer();
            }
        });

        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });
    }

    private void maybeShowDisclaimerDialog() {
        if (disclaimerDialogShown) return;
        if (appConfig == null) return;
        if (appConfig.isBlindSpotDisclaimerAccepted()) return;
        disclaimerDialogShown = true;
        new BlindSpotDisclaimerDialogFragment().show(getChildFragmentManager(), "blind_spot_disclaimer");
    }

}
