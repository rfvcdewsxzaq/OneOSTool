package cn.oneostool.plus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.SeekBar;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent;

import com.google.android.material.switchmaterial.SwitchMaterial;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private final ActivityResultLauncher<Intent> mOverlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                checkPermissions();
                if (Settings.canDrawOverlays(this)) {
                    DebugLogger.toast(this, getString(R.string.status_granted));
                }
            });

    // Touch tracking flags
    private boolean mIsUpdatingSpinner = false; // Flag to prevent feedback loop
    private long mLastInteractionTime = 0;
    private static final long INTERACTION_COOLDOWN_MS = 1500;

    // Theme Sync Control
    private long mLastThemeUserActionTime = 0;
    private boolean mIsUpdatingThemeUI = false;

    private final ActivityResultLauncher<Intent> mStoragePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                checkPermissions();
                if (hasStoragePermission()) {
                    DebugLogger.toast(this, getString(R.string.status_granted));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);

        initUI();
        checkPermissions();

    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions(); // Refresh status on resume
        updateOneOSStatus(false); // Initialize with default styling (disconnected)

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("cn.oneostool.plus.ACTION_DAY_NIGHT_STATUS");
        registerReceiverCompat(mDayNightStatusReceiver, filter);

        android.content.IntentFilter oneOSFilter = new android.content.IntentFilter();
        oneOSFilter.addAction("cn.oneostool.plus.ACTION_ONEOS_STATUS");
        registerReceiverCompat(mOneOSStatusReceiver, oneOSFilter);

        android.content.IntentFilter filterMedia = new android.content.IntentFilter(
                "cn.oneostool.plus.ACTION_MEDIA_METADATA");
        android.content.IntentFilter filterPosition = new android.content.IntentFilter(
                "cn.oneostool.plus.ACTION_MEDIA_POSITION");
        ContextCompat.registerReceiver(this, mMediaInfoReceiver, filterMedia, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, mMediaInfoReceiver, filterMedia, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, mMediaPositionReceiver, filterPosition, ContextCompat.RECEIVER_EXPORTED);

        android.content.IntentFilter gearFilter = new android.content.IntentFilter();
        gearFilter.addAction(GearCalculationManager.ACTION_UPDATE_RPM);
        gearFilter.addAction(GearCalculationManager.ACTION_UPDATE_GEAR);
        gearFilter.addAction(GearCalculationManager.ACTION_UPDATE_DRIVE_MODE);
        ContextCompat.registerReceiver(this, mGearReceiver, gearFilter, ContextCompat.RECEIVER_EXPORTED);

        sendBroadcast(new Intent("cn.oneostool.plus.ACTION_REQUEST_DAY_NIGHT_STATUS"));
        sendBroadcast(new Intent("cn.oneostool.plus.ACTION_REQUEST_ONEOS_STATUS"));
    }

    private void initUI() {
        // Setup Permission Status Items
        setupStatusItem(R.id.statusAutoStart, R.string.perm_auto_start, this::requestAutoStart);
        setupStatusItem(R.id.statusOverlay, R.string.perm_overlay, this::requestOverlayPermission);
        setupStatusItem(R.id.statusStorage, R.string.perm_storage, this::requestStoragePermission);
        setupStatusItem(R.id.statusAccessibility, R.string.perm_accessibility, this::requestAccessibilityPermission);

        setupStatusItem(R.id.statusBattery, R.string.perm_battery, this::requestBatteryOptimization);
        setupAutoStartApps();

        // Initialize Data Monitor Text (Values: Unknown)
        // Labels are static in XML now
        String unknown = getString(R.string.mode_unknown);

        TextView tvPropDayNightTheme = findViewById(R.id.tvPropDayNightTheme);
        if (tvPropDayNightTheme != null)
            tvPropDayNightTheme.setText(unknown);

        TextView tvSensorDayNight = findViewById(R.id.tvSensorDayNight);
        if (tvSensorDayNight != null)
            tvSensorDayNight.setText(unknown);

        TextView tvSensorSpeed = findViewById(R.id.tvSensorSpeed);
        if (tvSensorSpeed != null)
            tvSensorSpeed.setText(unknown);

        TextView tvPropAvm = findViewById(R.id.tvPropAvm);
        if (tvPropAvm != null)
            tvPropAvm.setText(unknown);

        TextView tvDriveMode = findViewById(R.id.tvDriveMode);
        if (tvDriveMode != null)
            tvDriveMode.setText(unknown);

        TextView tvTurnSignal = findViewById(R.id.tvTurnSignal);
        if (tvTurnSignal != null)
            tvTurnSignal.setText(unknown);

        TextView tvRpmValue = findViewById(R.id.tvRpmValue);
        if (tvRpmValue != null)
            tvRpmValue.setText(unknown);

        TextView tvGearValue = findViewById(R.id.tvGearValue);
        if (tvGearValue != null)
            tvGearValue.setText(unknown);

        // Buttons
        LinearLayout layoutDebugButtons = findViewById(R.id.layoutDebugButtons);
        findViewById(R.id.btnForceLight).setOnClickListener(v -> sendAutoNaviBroadcast(1));
        findViewById(R.id.btnForceDark).setOnClickListener(v -> sendAutoNaviBroadcast(2));

        android.content.SharedPreferences prefs = getSharedPreferences("navitool_prefs", Context.MODE_PRIVATE);

        // Auto Switch Toggle
        SwitchMaterial switchAutoTheme = findViewById(R.id.switchAutoTheme);
        switchAutoTheme.setChecked(prefs.getBoolean("auto_theme_sync", true));
        switchAutoTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_theme_sync", isChecked).apply();
            if (isChecked) {
                DebugLogger.toast(this, getString(R.string.auto_theme_sync_enabled));
            } else {
                DebugLogger.toast(this, getString(R.string.auto_theme_sync_disabled));
            }
        });

        // Vehicle Theme Mode Settings
        com.google.android.material.button.MaterialButtonToggleGroup toggleGroupThemeMode = findViewById(
                R.id.toggleGroupThemeMode);

        // Restore saved selection
        int savedThemeIdx = prefs.getInt("vehicle_theme_mode_index", 0);
        int checkedId = R.id.btnThemeAuto;
        switch (savedThemeIdx) {
            case 0:
                checkedId = R.id.btnThemeAuto;
                break;
            case 1:
                checkedId = R.id.btnThemeSunset;
                break;
            case 2:
                checkedId = R.id.btnThemeDay;
                break;
            case 3:
                checkedId = R.id.btnThemeNight;
                break;
        }
        toggleGroupThemeMode.check(checkedId);

        toggleGroupThemeMode.addOnButtonCheckedListener((group, checkedId1, isChecked) -> {
            if (mIsUpdatingThemeUI)
                return; // Ignore programmatic updates
            if (!isChecked)
                return; // Only handle the newly checked button

            mLastThemeUserActionTime = System.currentTimeMillis(); // Timestamp user action

            int position = 0;
            int modeValue = 0x20150103; // Default Auto

            if (checkedId1 == R.id.btnThemeAuto) {
                position = 0;
                modeValue = 0x20150103;
            } else if (checkedId1 == R.id.btnThemeSunset) {
                position = 1;
                modeValue = 0x20150105;
            } else if (checkedId1 == R.id.btnThemeDay) {
                position = 2;
                modeValue = 0x20150101;
            } else if (checkedId1 == R.id.btnThemeNight) {
                position = 3;
                modeValue = 0x20150102;
            }

            // Save selection
            prefs.edit().putInt("vehicle_theme_mode_index", position).apply();

            // Send Broadcast to Service to set mode
            Intent intent = new Intent("cn.oneostool.plus.ACTION_SET_THEME_MODE");
            intent.putExtra("mode_value", modeValue);
            sendBroadcast(intent);

            DebugLogger.toast(this, getString(R.string.sent_autonavi_broadcast, modeValue));
        });

        // Smart AVM Toggle
        SwitchMaterial switchSmartAvm = findViewById(R.id.switchSmartAvm);
        boolean isSmartAvmEnabled = prefs.getBoolean("enable_smart_avm", false);
        switchSmartAvm.setChecked(isSmartAvmEnabled);

        // Initial config send (in case service restarts)
        Intent avmIntent = new Intent("cn.oneostool.plus.ACTION_SET_SMART_AVM_CONFIG");
        avmIntent.putExtra("enabled", isSmartAvmEnabled);
        sendBroadcast(avmIntent);

        switchSmartAvm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_smart_avm", isChecked).apply();
            Intent intent = new Intent("cn.oneostool.plus.ACTION_SET_SMART_AVM_CONFIG");
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);
            if (isChecked) {
                DebugLogger.toast(this, "智能360辅助已启用");
            }
        });

        // Media Notification Toggle
        SwitchMaterial switchMediaNotification = findViewById(R.id.switchMediaNotification);
        boolean isMediaNotificationEnabled = prefs.getBoolean("enable_media_notification", true);
        switchMediaNotification.setChecked(isMediaNotificationEnabled);

        // Initial config send
        Intent mediaIntent = new Intent("cn.oneostool.plus.ACTION_SET_MEDIA_NOTIFICATION_CONFIG");
        mediaIntent.putExtra("enabled", isMediaNotificationEnabled);
        sendBroadcast(mediaIntent);

        switchMediaNotification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_media_notification", isChecked).apply();
            Intent intent = new Intent("cn.oneostool.plus.ACTION_SET_MEDIA_NOTIFICATION_CONFIG");
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);
        });

        // ============================================
        // Brightness Override Logic
        // ============================================
        SwitchMaterial switchOverride = findViewById(R.id.switchOverrideBrightness);
        LinearLayout layoutOverrideControls = findViewById(R.id.layoutOverrideControls);
        SeekBar sbOverrideDay = findViewById(R.id.seekBarOverrideDay);
        SeekBar sbOverrideNight = findViewById(R.id.seekBarOverrideNight);
        SeekBar sbOverrideAvm = findViewById(R.id.seekBarOverrideAvm);
        TextView tvOverrideDay = findViewById(R.id.tvOverrideDayValue);
        TextView tvOverrideNight = findViewById(R.id.tvOverrideNightValue);
        TextView tvOverrideAvm = findViewById(R.id.tvOverrideAvmValue);

        // Load saved prefs
        boolean isOverrideEnabled = prefs.getBoolean("override_brightness_enabled", false);
        int savedDay = prefs.getInt("override_day_value", 5);
        int savedNight = prefs.getInt("override_night_value", 3);
        int savedAvm = prefs.getInt("override_avm_value", 15);

        // Views for custom progress bars
        final View viewOverrideDayProgress = findViewById(R.id.viewOverrideDayProgress);
        final View viewOverrideNightProgress = findViewById(R.id.viewOverrideNightProgress);
        final View viewOverrideAvmProgress = findViewById(R.id.viewOverrideAvmProgress);

        // Define update helper
        final Runnable updateProgress = new Runnable() {
            @Override
            public void run() {
                updateProgressView(sbOverrideDay, viewOverrideDayProgress);
                updateProgressView(sbOverrideNight, viewOverrideNightProgress);
                updateProgressView(sbOverrideAvm, viewOverrideAvmProgress);
            }
        };

        switchOverride.setChecked(isOverrideEnabled);
        layoutOverrideControls.setVisibility(isOverrideEnabled ? View.VISIBLE : View.GONE);
        sbOverrideDay.setProgress(savedDay);
        sbOverrideNight.setProgress(savedNight);
        sbOverrideAvm.setProgress(savedAvm);
        tvOverrideDay.setText(String.valueOf(savedDay));
        tvOverrideNight.setText(String.valueOf(savedNight));
        tvOverrideAvm.setText(String.valueOf(savedAvm));

        // Initial update after layout
        sbOverrideDay.post(updateProgress);

        // Initial broadcast
        if (isOverrideEnabled) {
            sendOverrideConfig(true, savedDay, savedNight, savedAvm);
        }

        switchOverride.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("override_brightness_enabled", isChecked).apply();
            layoutOverrideControls.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                // Ensure views are updated when becoming visible
                sbOverrideDay.post(updateProgress);
            }
            sendOverrideConfig(isChecked,
                    sbOverrideDay.getProgress(),
                    sbOverrideNight.getProgress(),
                    sbOverrideAvm.getProgress());
        });

        SeekBar.OnSeekBarChangeListener overrideListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == sbOverrideDay) {
                    tvOverrideDay.setText(String.valueOf(progress));
                    updateProgressView(sbOverrideDay, viewOverrideDayProgress);
                }
                if (seekBar == sbOverrideNight) {
                    tvOverrideNight.setText(String.valueOf(progress));
                    updateProgressView(sbOverrideNight, viewOverrideNightProgress);
                }
                if (seekBar == sbOverrideAvm) {
                    tvOverrideAvm.setText(String.valueOf(progress));
                    updateProgressView(sbOverrideAvm, viewOverrideAvmProgress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int day = sbOverrideDay.getProgress();
                int night = sbOverrideNight.getProgress();
                int avm = sbOverrideAvm.getProgress();

                prefs.edit()
                        .putInt("override_day_value", day)
                        .putInt("override_night_value", night)
                        .putInt("override_avm_value", avm)
                        .apply();

                sendOverrideConfig(switchOverride.isChecked(), day, night, avm);
            }
        };

        sbOverrideDay.setOnSeekBarChangeListener(overrideListener);
        sbOverrideNight.setOnSeekBarChangeListener(overrideListener);
        sbOverrideAvm.setOnSeekBarChangeListener(overrideListener);

        // Steering Wheel Toggle
        SwitchMaterial switchSteeringWheel = findViewById(R.id.switchSteeringWheel);
        switchSteeringWheel.setChecked(prefs.getBoolean("enable_steering_wheel", true));
        switchSteeringWheel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_steering_wheel", isChecked).apply();
            // Optional: Broadcast/Notify service if needed
        });

        // ============================================
        // WeChat Key Logic
        // ============================================
        SwitchMaterial switchWechatButton = findViewById(R.id.switchWechatButton);
        View layoutWechatShortPress = findViewById(R.id.layoutWechatShortPress);
        View layoutWechatLongPress = findViewById(R.id.layoutWechatLongPress);

        boolean isWechatEnabled = prefs.getBoolean("enable_wechat_func", false);
        switchWechatButton.setChecked(isWechatEnabled);
        layoutWechatShortPress.setVisibility(isWechatEnabled ? View.VISIBLE : View.GONE);
        layoutWechatLongPress.setVisibility(isWechatEnabled ? View.VISIBLE : View.GONE);

        switchWechatButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_wechat_func", isChecked).apply();
            layoutWechatShortPress.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            layoutWechatLongPress.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Initialize Spinners
        Spinner spinnerShortPressAction = findViewById(R.id.spinnerShortPressAction);
        Spinner spinnerLongPressAction = findViewById(R.id.spinnerLongPressAction);

        String[] wechatActions = new String[] { "无操作", "启动应用" };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, wechatActions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerShortPressAction.setAdapter(adapter);
        spinnerLongPressAction.setAdapter(adapter);

        // Initialize App Spinners
        Spinner spinnerShortPressApp = findViewById(R.id.spinnerShortPressApp);
        Spinner spinnerLongPressApp = findViewById(R.id.spinnerLongPressApp);

        List<AppLaunchManager.AppInfo> apps = AppLaunchManager.getInstalledApps(this);
        // Add "None" option
        apps.add(0, new AppLaunchManager.AppInfo("- - - -", ""));

        ArrayAdapter<AppLaunchManager.AppInfo> appAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, apps);
        appAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerShortPressApp.setAdapter(appAdapter);
        spinnerLongPressApp.setAdapter(appAdapter);

        // Restore saved selection
        spinnerShortPressAction.setSelection(prefs.getInt("wechat_short_press_action", 0));
        spinnerLongPressAction.setSelection(prefs.getInt("wechat_long_press_action", 0));

        String savedShortApp = prefs.getString("wechat_short_press_app", "");
        if (!savedShortApp.isEmpty()) {
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).packageName.equals(savedShortApp)) {
                    spinnerShortPressApp.setSelection(i);
                    break;
                }
            }
        }

        String savedLongApp = prefs.getString("wechat_long_press_app", "");
        if (!savedLongApp.isEmpty()) {
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).packageName.equals(savedLongApp)) {
                    spinnerLongPressApp.setSelection(i);
                    break;
                }
            }
        }

        // App Selection Listener
        AdapterView.OnItemSelectedListener appListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppLaunchManager.AppInfo info = (AppLaunchManager.AppInfo) parent.getItemAtPosition(position);
                String key = (parent == spinnerShortPressApp) ? "wechat_short_press_app" : "wechat_long_press_app";
                prefs.edit().putString(key, info.packageName).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spinnerShortPressApp.setOnItemSelectedListener(appListener);
        spinnerLongPressApp.setOnItemSelectedListener(appListener);

        // Action Listener
        AdapterView.OnItemSelectedListener wechatListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isShort = (parent == spinnerShortPressAction);
                String keyAction = isShort ? "wechat_short_press_action" : "wechat_long_press_action";
                Spinner appSpinner = isShort ? spinnerShortPressApp : spinnerLongPressApp;

                // Save action index
                int oldPosition = prefs.getInt(keyAction, 0);
                if (oldPosition != position) {
                    prefs.edit().putInt(keyAction, position).apply();
                }

                if (position == 1) { // Launch App
                    appSpinner.setVisibility(View.VISIBLE);
                } else {
                    appSpinner.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spinnerShortPressAction.setOnItemSelectedListener(wechatListener);
        spinnerLongPressAction.setOnItemSelectedListener(wechatListener);

        // Initialize Media Buttons (Debug Only)
        // Note: These buttons are invisible by default (gone in XML)
        View layoutMediaButtons = findViewById(R.id.layoutMediaButtons);
        findViewById(R.id.btnMediaPrev)
                .setOnClickListener(v -> simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        findViewById(R.id.btnMediaPlayPause)
                .setOnClickListener(v -> simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        findViewById(R.id.btnMediaNext)
                .setOnClickListener(v -> simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT));

        // Initial visibility
        boolean isDebug = DebugLogger.isDebugEnabled(this);

        // AVM Debug Buttons
        View layoutDebugAvmButtons = findViewById(R.id.layoutDebugAvmButtons);
        Button btnForceAvmTimed = findViewById(R.id.btnForceAvmTimed);

        if (btnForceAvmTimed != null) {
            btnForceAvmTimed.setOnClickListener(v -> {
                Intent intent = new Intent("cn.oneostool.plus.ACTION_TEST_TURN_SIGNAL");
                intent.putExtra("type", "avm_timed");
                sendBroadcast(intent);
                DebugLogger.toast(this, "测试：开启360(3秒)");
            });
        }

        // Debug Switch
        SwitchMaterial switchDebug = findViewById(R.id.switchDebug);
        // if (!BuildConfig.DEBUG) {
        // switchDebug.setVisibility(View.GONE);
        // }
        // boolean isDebug = DebugLogger.isDebugEnabled(this); // Moved up
        switchDebug.setChecked(isDebug);

        // Initial visibility
        layoutDebugButtons.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        layoutMediaButtons.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        if (layoutDebugAvmButtons != null) {
            layoutDebugAvmButtons.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        }

        View layoutDebugLaunch = findViewById(R.id.layoutDebugLaunch);
        if (layoutDebugLaunch != null) {
            layoutDebugLaunch.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        }

        // Debug Launch Button Listener
        findViewById(R.id.btnTestLaunch).setOnClickListener(v -> {
            if (AppLaunchManager.loadConfig(MainActivity.this).isEmpty()) {
                DebugLogger.toast(MainActivity.this, "请先选择需要自动启动的应用");
            } else {
                AppLaunchManager.executeLaunch(MainActivity.this);
            }
        });

        // Update visibility in debug switch listener
        switchDebug.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DebugLogger.setDebugEnabled(this, isChecked);
            layoutDebugButtons.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            layoutMediaButtons.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (layoutDebugAvmButtons != null) {
                layoutDebugAvmButtons.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
            // Debug Launch Button
            // Debug Launch Button
            if (layoutDebugLaunch != null) {
                layoutDebugLaunch.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }

            if (isChecked) {
                DebugLogger.toast(this, "调试模式已开启，请向下滚动查看测试按钮");
            } else {
                Toast.makeText(this, R.string.debug_mode_disabled, Toast.LENGTH_SHORT).show();
            }
        });

        // Set Version Info
        TextView tvAppCredit = findViewById(R.id.tvAppCredit);
        if (tvAppCredit != null) {
            tvAppCredit.setText(getString(R.string.app_credit, BuildConfig.VERSION_NAME));
        }

    }

    // Helper to send override config to Service
    private void sendOverrideConfig(boolean enabled, int day, int night, int avm) {
        Intent intent = new Intent("cn.oneostool.plus.ACTION_SET_BRIGHTNESS_OVERRIDE_CONFIG");
        intent.putExtra("enabled", enabled);
        intent.putExtra("day", day);
        intent.putExtra("night", night);
        intent.putExtra("avm", avm);
        sendBroadcast(intent);
    }

    private final android.content.BroadcastReceiver mDayNightStatusReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("cn.oneostool.plus.ACTION_DAY_NIGHT_STATUS".equals(action)) {
                int mode = intent.getIntExtra("mode", 0);
                int dayNightSensor = intent.getIntExtra("sensor_day_night", -1);
                float lightSensor = intent.getFloatExtra("sensor_light", -1f);
                int avmProp = intent.getIntExtra("prop_avm", -1);
                int brightnessDay = intent.getIntExtra("prop_brightness_day", -1);
                int brightnessNight = intent.getIntExtra("prop_brightness_night", -1);
                int leftTurn = intent.getIntExtra("prop_turn_left", 0);
                int rightTurn = intent.getIntExtra("prop_turn_right", 0);

                updateSensorStatus(mode, dayNightSensor, lightSensor, avmProp, brightnessDay, brightnessNight, leftTurn,
                        rightTurn);
            }
        }
    };

    private final android.content.BroadcastReceiver mMediaInfoReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("cn.oneostool.plus.ACTION_MEDIA_METADATA".equals(action)) {
                String artist = intent.getStringExtra("artist");
                String title = intent.getStringExtra("title");
                // String album = intent.getStringExtra("album");
                boolean hasArt = intent.getBooleanExtra("has_art", false);

                TextView tvTitle = findViewById(R.id.tvMediaTitle);
                TextView tvArtist = findViewById(R.id.tvMediaArtist);

                if (tvTitle != null) {
                    if (title == null || "<Unknown>".equals(title)) {
                        tvTitle.setText(R.string.mode_unknown);
                    } else {
                        tvTitle.setText(title);
                    }
                    tvTitle.setSelected(true);
                }

                if (tvArtist != null) {
                    if (artist == null || "<Unknown>".equals(artist)) {
                        tvArtist.setText(R.string.mode_unknown);
                    } else {
                        tvArtist.setText(artist);
                    }
                    tvArtist.setSelected(true);
                }

                ImageView imgArt = findViewById(R.id.imgAlbumArt);
                if (imgArt != null) {
                    if (hasArt) {
                        try {
                            java.io.File file = new java.io.File(getCacheDir(), "album_art.png");
                            if (file.exists()) {
                                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory
                                        .decodeFile(file.getAbsolutePath());
                                imgArt.setImageBitmap(bitmap);
                            } else {
                                imgArt.setImageResource(android.R.drawable.ic_menu_gallery);
                            }
                        } catch (Exception e) {
                            imgArt.setImageResource(android.R.drawable.ic_menu_gallery);
                        }
                    } else {
                        imgArt.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                }
            }
        }
    };

    private final android.content.BroadcastReceiver mMediaPositionReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("cn.oneostool.plus.ACTION_MEDIA_POSITION".equals(action)) {
                long current = intent.getLongExtra("current", 0);
                long total = intent.getLongExtra("total", 0);

                TextView tvMediaPosition = findViewById(R.id.tvMediaPosition);
                if (tvMediaPosition != null) {
                    if (total > 0) {
                        String currentStr = formatMillis(current);
                        String totalStr = formatMillis(total);
                        tvMediaPosition.setText(String.format("%s / %s", currentStr, totalStr));
                    } else {
                        tvMediaPosition.setText("--:-- / --:--");
                    }
                }
            }
        }

        private String formatMillis(long millis) {
            long seconds = (millis / 1000) % 60;
            long minutes = (millis / (1000 * 60)) % 60;
            long hours = (millis / (1000 * 60 * 60)) % 24;
            if (hours > 0) {
                return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
            }
        }
    };

    private final android.content.BroadcastReceiver mGearReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (GearCalculationManager.ACTION_UPDATE_RPM.equals(action)) {
                int rpm = intent.getIntExtra(GearCalculationManager.EXTRA_RPM, 0);
                TextView tvRpm = findViewById(R.id.tvRpmValue);
                if (tvRpm != null) {
                    tvRpm.setText(String.valueOf(rpm));
                }
            } else if (GearCalculationManager.ACTION_UPDATE_GEAR.equals(action)) {
                String gear = intent.getStringExtra(GearCalculationManager.EXTRA_GEAR);
                TextView tvGear = findViewById(R.id.tvGearValue);
                if (tvGear != null && gear != null) {
                    tvGear.setText(gear);
                }
            } else if (GearCalculationManager.ACTION_UPDATE_DRIVE_MODE.equals(action)) {
                int mode = intent.getIntExtra(GearCalculationManager.EXTRA_DRIVE_MODE, -1);
                TextView tvDriveMode = findViewById(R.id.tvDriveMode);
                if (tvDriveMode != null) {
                    tvDriveMode.setText(getDriveModeString(mode));
                }
            }
        }
    };

    private String getDriveModeString(int mode) {
        switch (mode) {
            case 570491137: // ECO
                return "经济";
            case 570491138: // COMFORT
                return "舒适";
            case 570491139: // DYNAMIC (Sport)
                return "运动";
            case 570491158: // ADAPTIVE (Smart)
                return "智能";
            case 570491145: // SNOW
                return "雪地";
            case 570491155: // OFFROAD
                return "越野";
            default:
                return getString(R.string.mode_unknown);
        }
    }

    private final android.content.BroadcastReceiver mOneOSStatusReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("cn.oneostool.plus.ACTION_ONEOS_STATUS".equals(action)) {
                boolean isConnected = intent.getBooleanExtra("is_connected", false);
                updateOneOSStatus(isConnected);
            }
        }
    };

    private void updateOneOSStatus(boolean isConnected) {
        // Media Key Service
        ImageView imgMediaKey = findViewById(R.id.imgMediaKeyServiceIcon);
        // Media Info Service
        ImageView imgMediaInfo = findViewById(R.id.imgMediaInfoServiceIcon);

        if (imgMediaKey != null) {
            updateStatusIcon(imgMediaKey, isConnected);
        }
        if (imgMediaInfo != null) {
            updateStatusIcon(imgMediaInfo, isConnected);
        }

    }

    private void updateStatusIcon(ImageView icon, boolean active) {
        if (active) {
            icon.setImageResource(R.drawable.ic_check);
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            icon.setImageResource(R.drawable.ic_status_error);
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }
    }

    private void syncThemeUI(int targetId) {
        // 1. Check Cooldown
        if (System.currentTimeMillis() - mLastThemeUserActionTime < 1000) {
            return;
        }

        com.google.android.material.button.MaterialButtonToggleGroup toggleGroup = findViewById(
                R.id.toggleGroupThemeMode);
        if (toggleGroup == null)
            return;

        // 2. Check if update is needed
        if (toggleGroup.getCheckedButtonId() == targetId) {
            return;
        }

        // 3. Update UI with Lock
        mIsUpdatingThemeUI = true;
        toggleGroup.check(targetId);
        mIsUpdatingThemeUI = false;
    }

    private void updateSensorStatus(int dayNightThemeMode, int dayNightSensor, float lightSensor, int avmProp,
            int brightnessDay, int brightnessNight, int leftTurn, int rightTurn) {
        TextView tvPropDayNightTheme = findViewById(R.id.tvPropDayNightTheme);
        TextView tvSensorDayNight = findViewById(R.id.tvSensorDayNight);
        TextView tvSensorSpeed = findViewById(R.id.tvSensorSpeed);
        TextView tvPropAvm = findViewById(R.id.tvPropAvm);

        TextView tvPropBrightnessDay = findViewById(R.id.tvPropBrightnessDay);
        TextView tvPropBrightnessNight = findViewById(R.id.tvPropBrightnessNight);

        if (tvPropDayNightTheme != null) {
            String modeStr;
            switch (dayNightThemeMode) {
                case 0x20150103: // DAYMODE_SETTING_AUTO
                    modeStr = getString(R.string.mode_auto);
                    syncThemeUI(R.id.btnThemeAuto);
                    break;
                case 0x20150101: // DAYMODE_SETTING_DAY
                    modeStr = getString(R.string.mode_day);
                    syncThemeUI(R.id.btnThemeDay);
                    break;
                case 0x20150102: // DAYMODE_SETTING_NIGHT
                    modeStr = getString(R.string.mode_night);
                    syncThemeUI(R.id.btnThemeNight);
                    break;
                case 0x20150104: // VALUE_DAYMODE_CUSTOM
                    modeStr = getString(R.string.mode_custom);
                    break;
                case 0x20150105: // VALUE_DAYMODE_SUNRISE_AND_SUNSET
                    modeStr = getString(R.string.mode_sunrise_sunset);
                    syncThemeUI(R.id.btnThemeSunset);
                    break;
                default:
                    modeStr = getString(R.string.mode_unknown);
                    break;
            }
            tvPropDayNightTheme.setText(modeStr);
        }

        if (tvSensorDayNight != null) {
            String valStr;
            if (dayNightSensor == ISensorEvent.DAY_NIGHT_MODE_DAY) {
                valStr = "白天";
            } else if (dayNightSensor == ISensorEvent.DAY_NIGHT_MODE_NIGHT) {
                valStr = "夜间";
            } else {
                valStr = (dayNightSensor == -1) ? getString(R.string.mode_unknown)
                        : String.format(Locale.getDefault(), "0x%s (%d)", Integer.toHexString(dayNightSensor),
                                dayNightSensor);
            }
            tvSensorDayNight.setText(valStr);
        }

        if (tvSensorSpeed != null) {
            String valStr = (lightSensor == -1f) ? getString(R.string.mode_unknown)
                    : String.format(Locale.getDefault(), "%.2f", lightSensor);
            tvSensorSpeed.setText(valStr);
        }

        if (tvPropAvm != null) {
            String valStr;
            if (avmProp == 0) {
                valStr = "未激活";
            } else if (avmProp == 1) {
                valStr = "已激活";
            } else {
                valStr = (avmProp == -1) ? getString(R.string.mode_unknown) : String.valueOf(avmProp);
            }
            tvPropAvm.setText(valStr);
        }

        // Update Turn Signal TV
        TextView tvTurnSignal = findViewById(R.id.tvTurnSignal);
        if (tvTurnSignal != null) {
            String status;
            if (leftTurn > 0 && rightTurn > 0) {
                status = getString(R.string.turn_hazard);
            } else if (leftTurn > 0) {
                status = getString(R.string.turn_left);
            } else if (rightTurn > 0) {
                status = getString(R.string.turn_right);
            } else {
                status = getString(R.string.turn_none);
            }
            tvTurnSignal.setText(status);
        }

        if (tvPropBrightnessDay != null) {
            String valStr = (brightnessDay == -1) ? getString(R.string.mode_unknown) : String.valueOf(brightnessDay);
            tvPropBrightnessDay.setText(valStr);

        }

        if (tvPropBrightnessNight != null) {
            String valStr = (brightnessNight == -1) ? getString(R.string.mode_unknown)
                    : String.valueOf(brightnessNight);
            tvPropBrightnessNight.setText(valStr);
        }

    }

    private void sendBrightnessBroadcast(boolean isDay, int value) {
        Intent intent = new Intent("cn.oneostool.plus.ACTION_SET_BRIGHTNESS");
        intent.putExtra("is_day", isDay);
        intent.putExtra("value", value);
        sendBroadcast(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mDayNightStatusReceiver);
        unregisterReceiver(mOneOSStatusReceiver);
        unregisterReceiver(mMediaInfoReceiver);
        try {
            unregisterReceiver(mMediaPositionReceiver);
        } catch (IllegalArgumentException e) {
            // Ignore if not registered
        }
    }

    private void simulateMediaKey(int keyCode) {
        long eventTime = android.os.SystemClock.uptimeMillis();
        android.view.KeyEvent keyDown = new android.view.KeyEvent(eventTime, eventTime,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
        dispatchMediaKey(keyDown);

        android.view.KeyEvent keyUp = new android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_UP,
                keyCode, 0);
        dispatchMediaKey(keyUp);

        String keyName = getString(R.string.key_unknown);
        if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            keyName = getString(R.string.key_next_track);
        else if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            keyName = getString(R.string.key_prev_track);
        else if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            keyName = getString(R.string.key_play_pause);

        DebugLogger.toast(this, getString(R.string.simulated_media_key, keyName));
    }

    private void dispatchMediaKey(android.view.KeyEvent keyEvent) {
        // Method 1: Dispatch via AudioManager
        android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.dispatchMediaKeyEvent(keyEvent);
        }

        // Method 2: Send as broadcast (backup)
        Intent mediaIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        mediaIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        mediaIntent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        sendBroadcast(mediaIntent);
    }

    private void setupStatusItem(int viewId, int nameResId, Runnable onClickAction) {
        View view = findViewById(viewId);
        TextView txtName = view.findViewById(R.id.txtPermissionName);
        txtName.setText(nameResId);

        // Set click listener on the whole view AND the fix button
        view.setOnClickListener(v -> onClickAction.run());
        Button btnFix = view.findViewById(R.id.btnFix);
        btnFix.setOnClickListener(v -> onClickAction.run());
    }

    private void updateStatusItem(int viewId, boolean granted) {
        View view = findViewById(viewId);
        ImageView imgStatus = view.findViewById(R.id.imgStatus);
        Button btnFix = view.findViewById(R.id.btnFix);

        updateStatusIcon(imgStatus, granted);

        if (granted) {
            btnFix.setVisibility(View.GONE);
        } else {
            btnFix.setVisibility(View.VISIBLE);
        }
    }

    private void checkPermissions() {
        updateStatusItem(R.id.statusAutoStart, true); // Can't easily check, assume true or user manual check
        updateStatusItem(R.id.statusOverlay, Settings.canDrawOverlays(this));
        updateStatusItem(R.id.statusStorage, hasStoragePermission());
        updateStatusItem(R.id.statusAccessibility, isAccessibilityServiceEnabled());

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignoringBattery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        updateStatusItem(R.id.statusBattery, ignoringBattery);
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expectedComponentName = new ComponentName(this, KeepAliveAccessibilityService.class);
        String enabledServicesSetting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null)
            return false;

        android.text.TextUtils.SimpleStringSplitter colonSplitter = new android.text.TextUtils.SimpleStringSplitter(
                ':');
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledComponent = ComponentName.unflattenFromString(componentNameString);
            if (enabledComponent != null && enabledComponent.equals(expectedComponentName))
                return true;
        }
        return false;
    }

    // Permission Request Methods

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            mOverlayPermissionLauncher.launch(intent);
        } else {
            DebugLogger.toast(this, getString(R.string.status_granted));
        }
    }

    private void requestStoragePermission() {
        if (!hasStoragePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    mStoragePermissionLauncher.launch(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    mStoragePermissionLauncher.launch(intent);
                }
            } else {
                ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            DebugLogger.toast(this, getString(R.string.status_granted));
        }
    }

    private void requestBatteryOptimization() {
        Intent intent = new Intent();
        String packageName = getPackageName();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } else {
            DebugLogger.toast(this, getString(R.string.status_granted));
        }
    }

    private void requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, R.string.enable_accessibility_toast, Toast.LENGTH_LONG).show();
        } else {
            DebugLogger.toast(this, getString(R.string.status_granted));
        }
    }

    private void requestAutoStart() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.failed_open_auto_start, Toast.LENGTH_LONG).show();
        }
    }

    private void sendAutoNaviBroadcast(int mode) {
        Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_RECV");
        intent.setComponent(new ComponentName("com.autonavi.amapauto",
                "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"));
        intent.putExtra("KEY_TYPE", 10048);
        intent.putExtra("EXTRA_DAY_NIGHT_MODE", mode);
        sendBroadcast(intent);
        DebugLogger.log(this, "MainActivity", "Sent AutoNavi Broadcast: Mode " + mode);
        DebugLogger.toast(this, getString(R.string.sent_autonavi_broadcast, mode));
    }

    private void setupAutoStartApps() {
        SwitchMaterial switchAutoStart = findViewById(R.id.switchAutoStartApps);
        SwitchMaterial switchReturnToHome = findViewById(R.id.switchReturnToHome);
        ImageButton btnAddApp = findViewById(R.id.btnAddApp);
        LinearLayout llAutoStartAppsList = findViewById(R.id.llAutoStartAppsList);

        boolean isAutoStartEnabled = AppLaunchManager.isAutoStartEnabled(this);
        List<AppLaunchManager.AppConfig> savedConfigs = AppLaunchManager.loadConfig(this);

        switchAutoStart.setChecked(isAutoStartEnabled);
        switchReturnToHome.setChecked(AppLaunchManager.isReturnToHomeEnabled(this));

        // Initialize List
        View tvReturnToHome = findViewById(R.id.tvReturnToHome);
        View layoutControls = findViewById(R.id.layoutAutoStartAppsControls);

        llAutoStartAppsList.removeAllViews();
        for (AppLaunchManager.AppConfig config : savedConfigs) {
            addAppConfigRow(llAutoStartAppsList, config);
        }

        // Update visibility
        if (isAutoStartEnabled) {
            if (layoutControls != null)
                layoutControls.setVisibility(View.VISIBLE);
            llAutoStartAppsList.setVisibility(llAutoStartAppsList.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        } else {
            if (layoutControls != null)
                layoutControls.setVisibility(View.GONE);
            llAutoStartAppsList.setVisibility(View.GONE);
        }

        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppLaunchManager.setAutoStartEnabled(MainActivity.this, isChecked);
            if (isChecked) {
                if (layoutControls != null)
                    layoutControls.setVisibility(View.VISIBLE);
                llAutoStartAppsList.setVisibility(llAutoStartAppsList.getChildCount() > 0 ? View.VISIBLE : View.GONE);
            } else {
                if (layoutControls != null)
                    layoutControls.setVisibility(View.GONE);
                llAutoStartAppsList.setVisibility(View.GONE);
            }
        });

        switchReturnToHome.setOnCheckedChangeListener(
                (buttonView, isChecked) -> AppLaunchManager.setReturnToHomeEnabled(MainActivity.this, isChecked));

        btnAddApp.setOnClickListener(v -> {
            addAppConfigRow(llAutoStartAppsList, null);
            llAutoStartAppsList.setVisibility(View.VISIBLE);
        });

    }

    private void addAppConfigRow(LinearLayout container, AppLaunchManager.AppConfig initialConfig) {
        View itemView = getLayoutInflater().inflate(R.layout.item_app_auto_start, container, false);

        Spinner spinner = itemView.findViewById(R.id.spinnerAppSelection);
        android.widget.EditText etDelay = itemView.findViewById(R.id.etLaunchDelay);
        ImageButton btnMinus = itemView.findViewById(R.id.btnDelayMinus);
        ImageButton btnPlus = itemView.findViewById(R.id.btnDelayPlus);
        ImageButton btnDelete = itemView.findViewById(R.id.btnDeleteConfig);

        btnMinus.setOnClickListener(v -> {
            try {
                int delay = Integer.parseInt(etDelay.getText().toString());
                if (delay > 0) {
                    etDelay.setText(String.valueOf(delay - 1));
                }
            } catch (NumberFormatException e) {
                etDelay.setText("0");
            }
        });

        btnPlus.setOnClickListener(v -> {
            try {
                int delay = Integer.parseInt(etDelay.getText().toString());
                etDelay.setText(String.valueOf(delay + 1));
            } catch (NumberFormatException e) {
                etDelay.setText("1");
            }
        });

        List<AppLaunchManager.AppInfo> apps = AppLaunchManager.getInstalledApps(this);
        List<String> displayNames = new ArrayList<>();
        displayNames.add("- - - -");
        for (AppLaunchManager.AppInfo app : apps) {
            displayNames.add(app.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, displayNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        if (initialConfig != null) {
            etDelay.setText(String.valueOf(initialConfig.delaySeconds));
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).packageName.equals(initialConfig.packageName)) {
                    spinner.setSelection(i + 1);
                    break;
                }
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                saveAllConfigs();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        etDelay.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                saveAllConfigs();
            }
        });

        btnDelete.setOnClickListener(v -> {
            container.removeView(itemView);
            saveAllConfigs();
            if (container.getChildCount() == 0) {
                container.setVisibility(View.GONE);
            }
        });

        container.addView(itemView);
    }

    private void saveAllConfigs() {
        LinearLayout container = findViewById(R.id.llAutoStartAppsList);
        List<AppLaunchManager.AppConfig> configs = new ArrayList<>();
        List<AppLaunchManager.AppInfo> apps = AppLaunchManager.getInstalledApps(this);

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Spinner spinner = child.findViewById(R.id.spinnerAppSelection);
            android.widget.EditText etDelay = child.findViewById(R.id.etLaunchDelay);

            int selectedPos = spinner.getSelectedItemPosition();
            if (selectedPos > 0 && selectedPos <= apps.size()) {
                AppLaunchManager.AppInfo selectedApp = apps.get(selectedPos - 1);
                int delay;
                try {
                    delay = Integer.parseInt(etDelay.getText().toString());
                } catch (NumberFormatException e) {
                    delay = 0;
                }
                configs.add(new AppLaunchManager.AppConfig(selectedApp.packageName, delay));
            }
        }
        AppLaunchManager.saveConfig(this, configs);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiverCompat(android.content.BroadcastReceiver receiver,
            android.content.IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void updateProgressView(SeekBar seekBar, View progressView) {
        if (seekBar == null || progressView == null)
            return;

        int width = seekBar.getWidth();
        if (width == 0)
            return; // Layout not ready

        int paddingStart = seekBar.getPaddingStart();
        int paddingEnd = seekBar.getPaddingEnd();
        int usableWidth = width - paddingStart - paddingEnd;

        int max = seekBar.getMax();
        int min = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            min = seekBar.getMin();
        } else {
            // Hardcoded min=1 based on XML, but let's be safe.
            // Ideally we pass this or read it. For now assume 1 as per XML.
            min = 1;
        }

        int progress = seekBar.getProgress();

        // Prevent division by zero
        if (max <= min)
            return;

        float ratio = (float) (progress - min) / (float) (max - min);

        // Calculate desired width:
        // Start from left (0).
        // Cover paddingStart (Gap) + portion of usable width.
        // If progress is at min (ratio 0), width = paddingStart. (Fills the gap!)
        // If progress is at max (ratio 1), width = paddingStart + usableWidth + (maybe
        // paddingEnd)?
        // Wait, if we want it to go all the way to right edge at max?
        // User said "Thumb shouldn't exceed boundary". Thumb stops at paddingEnd.
        // Should Blue Bar go beyond Thumb?
        // Typically "Filled" means "Up to Thumb Center".
        // Thumb Center at Min = paddingStart.
        // Thumb Center at Max = width - paddingEnd.

        // So width = paddingStart + (usableWidth * ratio).
        // This means at Min, Width = 12dp. (Covers the left cap).
        // At Max, Width = Width - 12dp. (Covers up to right cap start).
        // This looks visually correct for "Filled up to Thumb".

        int targetWidth = Math.round(paddingStart + (usableWidth * ratio));

        android.view.ViewGroup.LayoutParams params = progressView.getLayoutParams();
        params.width = targetWidth;
        progressView.setLayoutParams(params);
    }

}
