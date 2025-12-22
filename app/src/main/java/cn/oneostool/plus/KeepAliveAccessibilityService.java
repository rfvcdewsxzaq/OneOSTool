package cn.oneostool.plus;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.content.ContextCompat;

import com.geely.lib.oneosapi.mediacenter.IMediaCenter;
import com.geely.lib.oneosapi.mediacenter.IMusicManager;
import com.geely.lib.oneosapi.mediacenter.listener.IMusicStateListener;
import com.geely.lib.oneosapi.mediacenter.bean.MediaData;

import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.ICar;
import com.ecarx.xui.adaptapi.car.base.ICarFunction;
import com.ecarx.xui.adaptapi.car.vehicle.IDayMode;
import com.ecarx.xui.adaptapi.car.vehicle.IPAS;
import com.ecarx.xui.adaptapi.car.vehicle.IVehicle;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent;
import com.ecarx.xui.adaptapi.FunctionStatus;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.NotificationManager;
import android.text.TextUtils; // Added for TextUtils
import com.geely.lib.oneosapi.OneOSApiManager;

public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAliveService";
    private static final String AUTONAVI_PKG = "com.autonavi.amapauto";

    // Media Session
    private MediaSession mMediaSession;
    private NotificationManager mNotificationManager;
    private NotificationChannel mNotificationChannel; // Added
    private static final String CHANNEL_ID = "media_channel";
    private static final int NOTIFICATION_ID = 1001;
    private MediaMetadata.Builder mMetadataBuilder; // Added
    private boolean mIsMediaNotificationEnabled = true; // Added
    private com.geely.lib.oneosapi.mediacenter.bean.MediaData mCurrentMediaData; // Added field

    private ICarFunction iCarFunction;
    private ISensor iSensor;

    // Brightness Override State
    private boolean mIsOverrideEnabled = false;
    private int mTargetBrightnessDay = 14;
    private int mTargetBrightnessNight = 3;
    private int mTargetBrightnessAvm = 15;
    private android.os.Handler mOverrideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mOverrideRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndEnforceBrightness();
            mOverrideHandler.postDelayed(this, 150); // 150ms
                                                     // loop
        }
    };

    /**/

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Media Playback",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Media playback controls");
        channel.setShowBadge(false);
        mNotificationManager.createNotificationChannel(channel);

        mMediaSession = new MediaSession(this, "OneOS_Session");
        mMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mMediaSession.setActive(true);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service Connected");

        SharedPreferences prefs = getSharedPreferences("navitool_prefs", MODE_PRIVATE);

        // Load initial override state
        mIsOverrideEnabled = prefs.getBoolean("override_brightness_enabled", false);
        mTargetBrightnessDay = prefs.getInt("override_day_value", 14);
        mTargetBrightnessNight = prefs.getInt("override_night_value", 3);
        mTargetBrightnessAvm = prefs.getInt("override_avm_value", 15);

        // Restore mCurrentSource
        mCurrentSource = prefs.getInt("last_media_source", -1); // Default to -1

        // Restore Smart AVM State
        mIsSmartAvmEnabled = prefs.getBoolean("enable_smart_avm", false);

        // Restore Media Notification State
        mIsMediaNotificationEnabled = prefs.getBoolean("enable_media_notification", true);

        // Start enforcement loop
        mOverrideHandler.post(mOverrideRunnable);

        // Restore last media data (Optimistic restoration)
        String lastTitle = prefs.getString("last_media_title", null);
        if (lastTitle != null) {
            com.geely.lib.oneosapi.mediacenter.bean.MediaData restorationData = new com.geely.lib.oneosapi.mediacenter.bean.MediaData();
            restorationData.name = lastTitle;
            restorationData.artist = prefs.getString("last_media_artist", "");
            restorationData.albumName = prefs.getString("last_media_album", "");
            restorationData.duration = prefs.getLong("last_media_duration", 0);

            // Try to restore album art
            try {
                java.io.File file = new java.io.File(getCacheDir(), "album_art.png");
                if (file.exists()) {
                    restorationData.albumCover = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                    Log.i(TAG, "Restored album art from cache.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore album art", e);
            }

            mCurrentMediaData = restorationData;
            // Update Session immediately so HUD shows something
            updateMediaSession(restorationData);
            // Force PAUSE state so timer doesn't run on cold start
            executeMediaSessionStateUpdate(1);
        }

        // Always start monitoring for Data Monitor feature
        startMonitoring();

        // Bind OneOS Service
        bindOneOSService();

        // Log boot event (with cooldown check)
        DebugLogger.logBootEvent(this);

        // Register receiver for config changes
        Log.i(TAG, "Build.VERSION.SDK_INT: " + Build.VERSION.SDK_INT);
        // Register receiver for config changes
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction("cn.oneostool.plus.ACTION_REQUEST_ONEOS_STATUS");
        filter.addAction("cn.oneostool.plus.ACTION_SET_THEME_MODE");
        filter.addAction("cn.oneostool.plus.ACTION_SET_BRIGHTNESS");
        filter.addAction("cn.oneostool.plus.ACTION_SET_BRIGHTNESS_OVERRIDE_CONFIG");
        filter.addAction("cn.oneostool.plus.ACTION_TEST_TURN_SIGNAL");
        filter.addAction("cn.oneostool.plus.ACTION_SET_SMART_AVM_CONFIG");
        filter.addAction("cn.oneostool.plus.ACTION_SET_MEDIA_NOTIFICATION_CONFIG");
        filter.addAction(Intent.ACTION_MEDIA_BUTTON);
        ContextCompat.registerReceiver(this, configChangeReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        ContextCompat.registerReceiver(this, configChangeReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        // Initialize OneOS API
        OneOSApiManager.getInstance().init(this);
        // Delay navigation monitoring init to allow service binding

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (mMediaSession != null) {
            mMediaSession.release();
        }
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
        Log.i(TAG, "Service destroying, cleaning up resources...");
        stopMonitoring();

        try {
            unregisterReceiver(configChangeReceiver);
            Log.d(TAG, "Unregistered config change receiver");
        } catch (Exception e) {
            // Ignore
        }

    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageName = event.getPackageName();
            if (packageName != null && AUTONAVI_PKG.equals(packageName.toString())) {
                Log.d(TAG, "AutoNavi detected in foreground. Syncing theme...");
                syncAutoNaviTheme();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Sync theme when system configuration changes (e.g. Day/Night switch)
        Log.d(TAG, "onConfigurationChanged called.");
        syncAutoNaviTheme();
    }

    private void logConstants() {
        try {
            logClassConstants(com.ecarx.xui.adaptapi.car.vehicle.IVehicle.class, "IVehicle");
            logClassConstants(com.ecarx.xui.adaptapi.car.sensor.ISensor.class, "ISensor");

            // Try to load IBody dynamically since it might not be in the compile-time SDK
            try {
                Class<?> bodyClass = Class.forName("com.ecarx.xui.adaptapi.car.vehicle.IBody");
                logClassConstants(bodyClass, "IBody");
            } catch (ClassNotFoundException e) {
                Log.i(TAG, "IBody class not found via reflection");
            }

            // Try to load ISensorEvent dynamically
            try {
                Class<?> sensorEventClass = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensorEvent");
                logClassConstants(sensorEventClass, "ISensorEvent");
            } catch (ClassNotFoundException e) {
                Log.i(TAG, "ISensorEvent class not found via reflection");
            }
        } catch (NoClassDefFoundError e) {
            // Ignore
        }
    }

    private void logClassConstants(Class<?> clazz, String className) {
        Log.i(TAG, "Scannning constants for " + className);
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                        (field.getType() == int.class || field.getType() == int[].class)) {
                    String name = field.getName();
                    if (name.contains("TURN") || name.contains("SIGNAL") || name.contains("INDICATOR")
                            || name.contains("LIGHT") || name.contains("DAY") || name.contains("NIGHT")) {
                        Object value = field.get(null);
                        Log.i(TAG, "FOUND CONSTANT: " + className + "." + name + " = " + value);
                        DebugLogger.toast(this, "Found: " + name + "=" + value);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // Day/Night Mode Constants
    private static final int FUNC_THEMEMODE_SETTING = 0x20150100;
    // Removed local SENSOR_TYPE_DAY_NIGHT, using ISensor.SENSOR_TYPE_DAY_NIGHT
    private static final int SENSOR_TYPE_SPEED = ISensor.SENSOR_TYPE_CAR_SPEED;
    // 0x100100;
    // Removed FUNC_REVERSE_GEAR
    private static final int FUNC_AVM_STATUS = IPAS.PAS_FUNC_PAC_ACTIVATION;
    private static final int FUNC_BRIGHTNESS_DAY = IVehicle.SETTING_FUNC_BRIGHTNESS_DAY;
    private static final int FUNC_BRIGHTNESS_NIGHT = IVehicle.SETTING_FUNC_BRIGHTNESS_NIGHT;
    private static final int FUNC_TURN_SIGNAL_LEFT = 0x21051100;
    private static final int FUNC_TURN_SIGNAL_RIGHT = 0x21051200;
    private static final int ZONE_DRIVER = 1;
    private static final int ZONE_ALL = 0x80000000;
    private static final int VALUE_THEMEMODE_DAY = 0x20150101;
    private static final int VALUE_THEMEMODE_NIGHT = 0x20150102;
    private static final int VALUE_THEMEMODE_AUTO = 0x20150103;
    private static final int VALUE_THEMEMODE_CUSTOM = 0x20150104;
    private static final int VALUE_THEMEMODE_SUNRISE_AND_SUNSET = 0x20150105;

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mIsMonitoring = false;
    private static final long MONITOR_INTERVAL_MS = 100; // Updated to 100ms

    private final Runnable mMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsMonitoring)
                return;
            checkAndForceAutoDayNight();
            mHandler.postDelayed(this, MONITOR_INTERVAL_MS);
        }
    };

    private void startMonitoring() {
        if (mIsMonitoring)
            return;
        mIsMonitoring = true;
        mHandler.post(mMonitorRunnable);
        Log.i(TAG, "Started Day/Night mode monitoring");
    }

    private void stopMonitoring() {
        mIsMonitoring = false;
        mHandler.removeCallbacks(mMonitorRunnable);
        Log.i(TAG, "Stopped Day/Night mode monitoring");
    }

    private void checkAndForceAutoDayNight() {
        checkDayNightStatus(true);
    }

    // Stores latest values to broadcast when needed
    private int mLastDayNightSensorValue = -1;
    private float mLastSpeedSensorValue = -1f;
    // Removed mLastReverseValue
    private int mLastAvmValue = -1;
    private int mLastBrightnessDayValue = -1;
    private int mLastBrightnessNightValue = -1;
    private int mLastThemeMode = -1; // Added for Theme Mode persistence
    private int mLastTurnSignalLeft = 0;
    private int mLastTurnSignalRight = 0;

    // Smart AVM State
    private boolean mIsSmartAvmEnabled = false;
    private boolean mHasSpeedExceeded30 = false;
    private boolean mIsSmartAvmActive = false;

    private void checkSmartAvmTrigger(float currentSpeed) {
        if (!mIsSmartAvmEnabled || iCarFunction == null)
            return;

        boolean isTurnSignalOn = (mLastTurnSignalLeft == 1 || mLastTurnSignalRight == 1);

        // Logic Step 1: Monitor Speed during Turn
        if (isTurnSignalOn) {
            if (currentSpeed > 30) {
                mHasSpeedExceeded30 = true;
                // Refinement: If speed > 30 and we were active, reset active flag (System
                // likely closed it)
                // Flow effectively resets to "Waiting for trigger"
                if (mIsSmartAvmActive) {
                    Log.i(TAG, "SmartAVM: Speed > 30, resetting active flag (System takes over)");
                    mIsSmartAvmActive = false;
                }
            } else {
                // Speed <= 30
                // Logic Step 2: Trigger Condition
                if (mHasSpeedExceeded30 && !mIsSmartAvmActive) {
                    // Check if AVM is already on to avoid spamming
                    if (mLastAvmValue != 1) {
                        Log.i(TAG, "SmartAVM: Triggering AVM (Speed dropped < 30 after > 30)");
                        try {
                            iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 1);
                            mIsSmartAvmActive = true;
                            DebugLogger.toast(this, "智能360已自动开启");
                        } catch (Exception e) {
                            Log.e(TAG, "SmartAVM: Failed to open AVM", e);
                        }
                    }
                }
            }
        } else {
            // Logic Step 3: Turn Signal Off - Reset
            if (mIsSmartAvmActive) {
                Log.i(TAG, "SmartAVM: Turn signal OFF, closing AVM");
                try {
                    iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 0);
                } catch (Exception e) {
                    Log.e(TAG, "SmartAVM: Failed to close AVM", e);
                }
            }
            // Reset Flags
            mHasSpeedExceeded30 = false;
            mIsSmartAvmActive = false;
        }
    }

    private void pollAndBroadcastBrightness() {
        if (iCarFunction == null)
            return;
        try {
            // Poll Day Brightness
            float brightDay = iCarFunction.getCustomizeFunctionValue(FUNC_BRIGHTNESS_DAY, ZONE_DRIVER);
            if (brightDay != -1f) {
                mLastBrightnessDayValue = (int) brightDay;
            }

            // Poll Night Brightness
            float brightNight = iCarFunction.getCustomizeFunctionValue(FUNC_BRIGHTNESS_NIGHT, ZONE_DRIVER);
            if (brightNight != -1f) {
                mLastBrightnessNightValue = (int) brightNight;
            }

            Log.d(TAG, "Polled Brightness - Day: " + mLastBrightnessDayValue + ", Night: " + mLastBrightnessNightValue);

            broadcastSensorValues(mLastDayNightSensorValue, mLastSpeedSensorValue,
                    mLastAvmValue, mLastBrightnessDayValue, mLastBrightnessNightValue);
        } catch (Exception e) {
            Log.e(TAG, "Error polling brightness", e);
        }
    }

    private void broadcastSensorValues(int dayNightValue, float speedValue, int avmValue,
            int brightnessDay, int brightnessNight) {
        mLastDayNightSensorValue = dayNightValue;
        mLastSpeedSensorValue = speedValue;
        mLastAvmValue = avmValue;
        mLastBrightnessDayValue = brightnessDay;
        mLastBrightnessNightValue = brightnessNight;

        Intent intent = new Intent("cn.oneostool.plus.ACTION_DAY_NIGHT_STATUS");
        // We send just sensor values.
        intent.putExtra("mode", mLastThemeMode); // Include Theme Mode
        intent.putExtra("sensor_day_night", dayNightValue);
        intent.putExtra("sensor_light", speedValue); // Key kept as sensor_light for compatibility
        intent.putExtra("prop_avm", avmValue);
        intent.putExtra("prop_brightness_day", brightnessDay);
        intent.putExtra("prop_brightness_night", brightnessNight);
        intent.putExtra("prop_turn_left", mLastTurnSignalLeft);
        intent.putExtra("prop_turn_right", mLastTurnSignalRight);
        sendBroadcast(intent);
    }

    private void checkDayNightStatus(boolean enforceAuto) {
        new Thread(() -> {
            initCar();
            if (iCarFunction != null) {
                try {
                    int currentMode = iCarFunction.getFunctionValue(FUNC_THEMEMODE_SETTING, ZONE_ALL);

                    // Poll all values...
                    // (Skipping redundant poll logic copy here, assuming it remains same)
                    // ... [Actual Code Block Omitted for Brevity in Tool Call, focusing on removal
                    // of enforcement]
                    // Wait, cannot omit in replace_content. Need to rely on finding the enforcement
                    // block.

                    // Let's target the enforcement block specifically.

                    // Read Sensors (Fallback/Initial)
                    // We rely on listeners for updates, but initial read is good.
                    // However, we just start listener below.
                    // So we can skip manual read here to avoid blocking or errors if synchronous
                    // read isn't supported.

                    // Explicitly poll values to ensure initial state is captured
                    try {
                        // Polling Speed (0x100100) - Assuming float or int? ISensor usually callbacks.
                        // But we can try getFunctionValue? Or wait for listener.
                        // SENSOR_TYPE_SPEED is likely ISensor.
                        // But for properties (Reverse, AVM, Brightness), we SHOULD poll.

                        int avm = iCarFunction.getFunctionValue(FUNC_AVM_STATUS, ZONE_ALL);
                        if (avm != -1)
                            mLastAvmValue = avm;

                        // Poll Day Brightness
                        float brightDay = iCarFunction.getCustomizeFunctionValue(FUNC_BRIGHTNESS_DAY, ZONE_DRIVER);
                        if (brightDay != -1f) {
                            mLastBrightnessDayValue = (int) brightDay;
                        }

                        // Poll Night Brightness
                        float brightNight = iCarFunction.getCustomizeFunctionValue(FUNC_BRIGHTNESS_NIGHT, ZONE_DRIVER);
                        if (brightNight != -1f) {
                            mLastBrightnessNightValue = (int) brightNight;
                        }

                        // Poll Day/Night Sensor (User request: polling every 1s)
                        if (iSensor != null) {
                            int dayNight = iSensor.getSensorEvent(ISensor.SENSOR_TYPE_DAY_NIGHT);
                            if (dayNight != -1) {
                                mLastDayNightSensorValue = dayNight;
                            }
                        }

                        // Note: car.getSensorManager() doesn't usually have "getSensorValue".
                        // UPDATE: User confirmed getSensorEvent is available.
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to poll initial function values: " + e.getMessage());
                    }

                    if (currentMode != -1 && currentMode != 0) {
                        mLastThemeMode = currentMode;
                    }

                    // Broadcast status to UI
                    Intent intent = new Intent("cn.oneostool.plus.ACTION_DAY_NIGHT_STATUS");
                    intent.putExtra("mode", mLastThemeMode);
                    // Include last known sensor values to keep UI in sync
                    intent.putExtra("sensor_day_night", mLastDayNightSensorValue);
                    intent.putExtra("sensor_light", mLastSpeedSensorValue);
                    intent.putExtra("prop_avm", mLastAvmValue);
                    intent.putExtra("prop_brightness_day", mLastBrightnessDayValue);
                    intent.putExtra("prop_brightness_night", mLastBrightnessNightValue);
                    intent.putExtra("prop_turn_left", mLastTurnSignalLeft);
                    intent.putExtra("prop_turn_right", mLastTurnSignalRight);
                    sendBroadcast(intent);

                    // Enforcement logic removed as per user request to replace "Force Auto" with
                    // "Theme Mode Settings"
                    // if (enforceAuto) { ... } removed so we don't override user selection.
                } catch (Exception e) {
                    Log.e(TAG, "Error checking/setting Day/Night mode", e);
                }
            }
        }).start();

    }

    private void handleShortClick(int keyCode) {
        Log.i(TAG, "handleShortClick: " + keyCode);

        // 检测是否有通话，有通话时不处理媒体按键
        if (keyCode == 200087 || keyCode == 200088 || keyCode == 200085) {
            SharedPreferences prefs = getSharedPreferences("navitool_prefs", Context.MODE_PRIVATE);
            boolean isSteeringWheelEnabled = prefs.getBoolean("enable_steering_wheel", false);
            if (!isSteeringWheelEnabled) {
                Log.d(TAG, "Steering wheel control disabled, skipping media key handling");
                return;
            }
        }

        switch (keyCode) {
            case 200087: // R_MEDIA_NEXT - 短按下一曲
                simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case 200088: // R_MEDIA_PREVIOUS - 短按上一曲
                simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case 200085: // R_MEDIA_PLAY_PAUSE - 短按播放/暂停
                simulateMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case 200400: // R_WECHAT - 短按微信按键
                handleWechatShortPress();
                break;
            default:
                Log.d(TAG, "Unhandled short click key code: " + keyCode);
                break;
        }
    }

    private void handleLongPress(int keyCode) {
        Log.i(TAG, "handleLongPress: " + keyCode);
        switch (keyCode) {
            // case 200087: // R_MEDIA_NEXT - 长按下一曲（快进）
            // Log.i(TAG, "Long press NEXT - fast forward");
            // break;
            // case 200088: // R_MEDIA_PREVIOUS - 长按上一曲（快退）
            // Log.i(TAG, "Long press PREVIOUS - rewind");
            // break;
            // case 200085: // R_MEDIA_PLAY_PAUSE - 长按播放/暂停
            // Log.i(TAG, "Long press PLAY_PAUSE");
            // break;
            case 200400: // R_WECHAT - 长按微信按键
                handleWechatLongPress();
                break;
            default:
                Log.d(TAG, "Unhandled long press key code: " + keyCode);
                break;
        }
    }

    private void handleWechatShortPress() {
        SharedPreferences prefs = getSharedPreferences("navitool_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enable_wechat_func", false);
        if (!enabled) {
            Log.d(TAG, "WeChat button function disabled");
            return;
        }

        int action = prefs.getInt("wechat_short_press_action", 0);
        if (action == 1) { // 启动应用
            String packageName = prefs.getString("wechat_short_press_app", "");
            if (!packageName.isEmpty()) {
                launchApp(packageName);
            }
        }
    }

    private void handleWechatLongPress() {
        SharedPreferences prefs = getSharedPreferences("navitool_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enable_wechat_func", false);
        if (!enabled) {
            Log.d(TAG, "WeChat button function disabled");
            return;
        }

        int action = prefs.getInt("wechat_long_press_action", 0);
        if (action == 1) { // 启动应用
            String packageName = prefs.getString("wechat_long_press_app", "");
            if (!packageName.isEmpty()) {
                launchApp(packageName);
            }
        }
    }

    private void launchApp(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.i(TAG, "Launched app: " + packageName);
                DebugLogger.toast(this, "正在启动: " + packageName);
            } else {
                Log.e(TAG, "Could not find launch intent for " + packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching app " + packageName, e);
        }
    }

    // --- Car AdaptAPI Implementation ---

    private void initCar() {
        try {
            if (iCarFunction == null || iSensor == null) {
                ICar car = Car.create(getApplicationContext());
                if (car != null) {
                    iCarFunction = car.getICarFunction();

                    if (car instanceof ISensor) {
                        iSensor = (ISensor) car;
                    }

                    try {
                        if (iSensor == null) {
                            java.lang.reflect.Method method = car.getClass().getMethod("getSensorManager");
                            iSensor = (ISensor) method.invoke(car);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "getSensorManager not found via reflection");
                    }

                    Log.i(TAG, "Car AdaptAPI initialized successfully");

                    if (iSensor != null) {
                        registerSensorListeners();
                    }
                    if (iCarFunction != null) {
                        registerFunctionListeners();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Car AdaptAPI", e);
        }
    }

    private void registerSensorListeners() {
        try {
            if (iSensor != null) {
                ISensor.ISensorListener listener = new ISensor.ISensorListener() {
                    @Override
                    public void onSensorValueChanged(int sensorType, float value) {
                        if (sensorType == ISensor.SENSOR_TYPE_IGNITION_STATE) {
                            int val = (int) value;
                            Log.d(TAG, "Ignition State Changed (float): " + val);
                            if (val == ISensorEvent.IGNITION_STATE_DRIVING) {
                                Log.i(TAG, "Ignition State: DRIVING");
                                DebugLogger.toast(KeepAliveAccessibilityService.this, "检测到行车状态");
                                AppLaunchManager.executeLaunch(KeepAliveAccessibilityService.this);
                            }
                        } else if (sensorType == SENSOR_TYPE_SPEED) {
                            Log.d(TAG, "Speed Sensor Changed (float): " + value);
                            // Speed value (x3.72 as requested by user)
                            float speedVal = value * 3.72f;
                            broadcastSensorValues(mLastDayNightSensorValue, speedVal, mLastAvmValue,
                                    mLastBrightnessDayValue, mLastBrightnessNightValue);
                            checkSmartAvmTrigger(speedVal);
                        }
                    }

                    @Override
                    public void onSensorEventChanged(int sensorType, int value) {
                        if (sensorType == ISensor.SENSOR_TYPE_IGNITION_STATE) {
                            Log.d(TAG, "Ignition State Changed (int): " + value);
                            if (value == ISensorEvent.IGNITION_STATE_DRIVING) {
                                Log.i(TAG, "Ignition State: DRIVING");
                                DebugLogger.toast(KeepAliveAccessibilityService.this, "检测到行车状态");
                                AppLaunchManager.executeLaunch(KeepAliveAccessibilityService.this);
                            }
                        } else if (sensorType == SENSOR_TYPE_SPEED) {
                            Log.d(TAG, "Speed Sensor Changed (int): " + value);
                            float speedVal = value * 3.72f;
                            broadcastSensorValues(mLastDayNightSensorValue, speedVal, mLastAvmValue,
                                    mLastBrightnessDayValue, mLastBrightnessNightValue);
                            checkSmartAvmTrigger(speedVal);
                        }
                        // SENSOR_TYPE_DAY_NIGHT removed - using active polling instead
                    }

                    @Override
                    public void onSensorSupportChanged(int sensorType, com.ecarx.xui.adaptapi.FunctionStatus status) {
                    }
                };

                // Register for Ignition
                iSensor.registerListener(listener, ISensor.SENSOR_TYPE_IGNITION_STATE);

                // Day/Night and Speed are polled actively now. Listener removed to avoid
                // redundancy.
                // iSensor.registerListener(listener, ISensor.SENSOR_TYPE_DAY_NIGHT);

                // Register for Speed Sensor (0x100100)
                iSensor.registerListener(listener, SENSOR_TYPE_SPEED);

                Log.i(TAG, "Sensor listeners registered (Ignition, DayNight 0x100900, Speed 0x100100)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register sensor listeners", e);
        }
    }

    private void registerFunctionListeners() {
        try {
            if (iCarFunction != null) {
                ICarFunction.IFunctionValueWatcher watcher = new ICarFunction.IFunctionValueWatcher() {
                    @Override
                    public void onFunctionValueChanged(int functionId, int zone, int value) {
                        if (functionId == FUNC_AVM_STATUS) {
                            Log.d(TAG, "AVM Status Changed: " + value);
                            broadcastSensorValues(mLastDayNightSensorValue, mLastSpeedSensorValue,
                                    value, mLastBrightnessDayValue, mLastBrightnessNightValue);
                        } else if (functionId == FUNC_BRIGHTNESS_DAY || functionId == FUNC_BRIGHTNESS_NIGHT) {
                            Log.d(TAG, "Brightness Changed (Int): " + value + " for ID: " + functionId);
                            // Always poll both to ensure consistency and avoid flickering
                            pollAndBroadcastBrightness();
                        } else if (functionId == FUNC_TURN_SIGNAL_LEFT) {
                            Log.d(TAG, "Left Turn Signal Changed: " + value);
                            mLastTurnSignalLeft = value;
                            broadcastSensorValues(mLastDayNightSensorValue, mLastSpeedSensorValue, mLastAvmValue,
                                    mLastBrightnessDayValue, mLastBrightnessNightValue);
                            checkSmartAvmTrigger(mLastSpeedSensorValue);
                        } else if (functionId == FUNC_TURN_SIGNAL_RIGHT) {
                            Log.d(TAG, "Right Turn Signal Changed: " + value);
                            mLastTurnSignalRight = value;
                            broadcastSensorValues(mLastDayNightSensorValue, mLastSpeedSensorValue, mLastAvmValue,
                                    mLastBrightnessDayValue, mLastBrightnessNightValue);
                            checkSmartAvmTrigger(mLastSpeedSensorValue);
                        }
                    }

                    @Override
                    public void onCustomizeFunctionValueChanged(int functionId, int zone, float value) {
                        if (functionId == FUNC_BRIGHTNESS_DAY || functionId == FUNC_BRIGHTNESS_NIGHT) {
                            Log.d(TAG, "Brightness Changed (Float): " + value + " for ID: " + functionId);
                            pollAndBroadcastBrightness();
                        }
                    }

                    @Override
                    public void onFunctionChanged(int functionId) {
                        // Not used
                    }

                    @Override
                    public void onSupportedFunctionStatusChanged(int functionId, int zone,
                            com.ecarx.xui.adaptapi.FunctionStatus status) {
                        // Not used
                    }

                    @Override
                    public void onSupportedFunctionValueChanged(int functionId, int[] values) {
                        // Not used
                    }

                };

                // Removed FUNC_REVERSE_GEAR watcher
                iCarFunction.registerFunctionValueWatcher(FUNC_AVM_STATUS, watcher);
                iCarFunction.registerFunctionValueWatcher(FUNC_BRIGHTNESS_DAY, watcher);
                iCarFunction.registerFunctionValueWatcher(FUNC_BRIGHTNESS_NIGHT, watcher);
                iCarFunction.registerFunctionValueWatcher(FUNC_TURN_SIGNAL_LEFT, watcher);
                iCarFunction.registerFunctionValueWatcher(FUNC_TURN_SIGNAL_RIGHT, watcher);
                // FUNC_DAYMODE_SETTING listener removed as per user request (not supported)

                Log.i(TAG, "Function watchers registered (AVM, Brightness)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register function watchers", e);
        }
    }

    private final BroadcastReceiver configChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                Log.d(TAG, "Configuration changed, checking day/night status...");
                checkDayNightStatus(false);
            } else if ("cn.oneostool.plus.ACTION_REQUEST_ONEOS_STATUS".equals(action)) {
                boolean isConnected = (mOneOSServiceManager != null);
                broadcastOneOSStatus(isConnected);
            } else if ("cn.oneostool.plus.ACTION_SET_THEME_MODE".equals(action)) {
                if (iCarFunction != null) {
                    int modeValue = intent.getIntExtra("mode_value", VALUE_THEMEMODE_AUTO);
                    try {
                        Log.i(TAG, "Setting Theme Mode to: " + Integer.toHexString(modeValue));
                        boolean success = iCarFunction.setFunctionValue(FUNC_THEMEMODE_SETTING, ZONE_ALL, modeValue);
                        if (success) {
                            DebugLogger.toast(context, "已设置主题模式");
                            // Force check to update UI
                            checkDayNightStatus(false);
                        } else {
                            Log.e(TAG, "Failed to set Theme Mode");
                            DebugLogger.toast(context, "设置主题模式失败");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting Theme Mode", e);
                    }
                }
            } else if ("cn.oneostool.plus.ACTION_SET_BRIGHTNESS".equals(action)) {
                if (iCarFunction != null) {
                    boolean isDay = intent.getBooleanExtra("is_day", true);
                    int value = intent.getIntExtra("value", 0);
                    int funcId = isDay ? FUNC_BRIGHTNESS_DAY : FUNC_BRIGHTNESS_NIGHT;

                    try {
                        Log.d(TAG, "Setting Brightness - IsDay: " + isDay + ", Value: " + value);
                        boolean success = iCarFunction.setCustomizeFunctionValue(funcId, ZONE_DRIVER, (float) value);
                        Log.d(TAG, "setCustomizeFunctionValue result: " + success);

                        boolean fallbackSuccess = false;
                        if (!success) {
                            Log.w(TAG, "setCustomizeFunctionValue failed, trying setFunctionValue...");
                            fallbackSuccess = iCarFunction.setFunctionValue(funcId, ZONE_DRIVER, value);
                            Log.d(TAG, "setFunctionValue result: " + fallbackSuccess);
                        }

                        // Update local cache immediately (Optimistic)
                        if (success || fallbackSuccess) {
                            if (isDay) {
                                mLastBrightnessDayValue = value;
                            } else {
                                mLastBrightnessNightValue = value;
                            }
                            // Broadcast new state immediately to keep UI in sync
                            broadcastSensorValues(mLastDayNightSensorValue, mLastSpeedSensorValue, mLastAvmValue,
                                    mLastBrightnessDayValue, mLastBrightnessNightValue);
                        }

                        // Also force a check from system
                        checkDayNightStatus(false);

                    } catch (Exception e) {
                        Log.e(TAG, "Error setting brightness", e);
                    }
                }
            } else if ("cn.oneostool.plus.ACTION_SET_BRIGHTNESS_OVERRIDE_CONFIG".equals(action)) {
                mIsOverrideEnabled = intent.getBooleanExtra("enabled", false);
                mTargetBrightnessDay = intent.getIntExtra("day", 5);
                mTargetBrightnessNight = intent.getIntExtra("night", 3);
                mTargetBrightnessAvm = intent.getIntExtra("avm", 15);
                Log.d(TAG, "Override Config Updated: " + mIsOverrideEnabled + " D:" + mTargetBrightnessDay + " N:"
                        + mTargetBrightnessNight + " A:" + mTargetBrightnessAvm);
            } else if ("cn.oneostool.plus.ACTION_TEST_TURN_SIGNAL".equals(action)) {
                String type = intent.getStringExtra("type");
                if (iCarFunction != null) {
                    try {
                        Log.i(TAG, "Test Signal: " + type);
                        if ("avm".equals(type)) {
                            iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 1);
                        } else if ("avm_timed".equals(type)) {
                            iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 1);
                            mHandler.postDelayed(() -> {
                                try {
                                    if (iCarFunction != null) {
                                        iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 0);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error closing AVM in timed test", e);
                                }
                            }, 3000);
                        } else if ("left".equals(type)) {
                            // 0x21051200 = Turn Signal Left (Guessing based on context)
                            iCarFunction.setFunctionValue(0x21051200, ZONE_ALL, 1);
                            mHandler.postDelayed(() -> {
                                try {
                                    if (iCarFunction != null)
                                        iCarFunction.setFunctionValue(0x21051200, ZONE_ALL, 0);
                                } catch (Exception e) {
                                }
                            }, 1000);
                        } else if ("right".equals(type)) {
                            // 0x21051100 = Turn Signal Right
                            iCarFunction.setFunctionValue(0x21051100, ZONE_ALL, 1);
                            mHandler.postDelayed(() -> {
                                try {
                                    if (iCarFunction != null)
                                        iCarFunction.setFunctionValue(0x21051100, ZONE_ALL, 0);
                                } catch (Exception e) {
                                }
                            }, 1000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Test Turn Signal", e);
                    }
                }
            } else if ("cn.oneostool.plus.ACTION_SET_SMART_AVM_CONFIG".equals(action)) {
                mIsSmartAvmEnabled = intent.getBooleanExtra("enabled", false);
                Log.i(TAG, "Smart AVM Config Updated: " + mIsSmartAvmEnabled);
                // Reset state on toggle
                if (!mIsSmartAvmEnabled) {
                    mHasSpeedExceeded30 = false;
                    mIsSmartAvmActive = false;
                }
            } else if ("cn.oneostool.plus.ACTION_SET_MEDIA_NOTIFICATION_CONFIG".equals(action)) {
                mIsMediaNotificationEnabled = intent.getBooleanExtra("enabled", true);
                Log.i(TAG, "Media Notification Config Updated: " + mIsMediaNotificationEnabled);
                if (!mIsMediaNotificationEnabled) {
                    // Cancel notification immediately if disabled
                    updateMediaSession(); // Will handle cancellation logic
                } else {
                    // Re-post if enabled
                    updateMediaSession();
                }
            } else if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                android.view.KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    Log.i(TAG, "Standard MEDIA_BUTTON received: " + event.getKeyCode());
                }
            } else if ("cn.oneostool.plus.ACTION_TEST_TURN_SIGNAL".equals(action)) {
                String type = intent.getStringExtra("type");
                Log.i(TAG, "Received Linkage Test: " + type);
                if (iCarFunction == null) {
                    DebugLogger.toast(context, "Car Service Not Ready");
                    return;
                }

                try {
                    if ("left".equals(type)) {
                        // Try writing 1 (ON) to Turn Signal Left
                        // Assuming zone is driver (or try ALL)
                        boolean s1 = iCarFunction.setCustomizeFunctionValue(FUNC_TURN_SIGNAL_LEFT, ZONE_DRIVER, 1.0f);
                        Log.i(TAG, "Set Turn Left (Cust): " + s1);
                        if (!s1) {
                            // Fallback to setFunctionValue
                            boolean s2 = iCarFunction.setFunctionValue(FUNC_TURN_SIGNAL_LEFT, ZONE_DRIVER, 1);
                            Log.i(TAG, "Set Turn Left (Std): " + s2);
                        }
                    } else if ("right".equals(type)) {
                        boolean s1 = iCarFunction.setCustomizeFunctionValue(FUNC_TURN_SIGNAL_RIGHT, ZONE_DRIVER, 1.0f);
                        Log.i(TAG, "Set Turn Right (Cust): " + s1);
                        if (!s1) {
                            boolean s2 = iCarFunction.setFunctionValue(FUNC_TURN_SIGNAL_RIGHT, ZONE_DRIVER, 1);
                            Log.i(TAG, "Set Turn Right (Std): " + s2);
                        }
                    } else if ("avm".equals(type)) {
                        // FUNC_AVM_STATUS = IPAS.PAS_FUNC_PAC_ACTIVATION (0x29030200)
                        boolean s1 = iCarFunction.setFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 1);
                        Log.i(TAG, "Set AVM (Std): " + s1);
                        if (!s1) {
                            boolean s2 = iCarFunction.setCustomizeFunctionValue(FUNC_AVM_STATUS, ZONE_ALL, 1.0f);
                            Log.i(TAG, "Set AVM (Cust): " + s2);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Linkage Test Failed", e);
                    DebugLogger.toast(context, "测试失败: " + e.getMessage());
                }
            }
        }
    };

    private void checkAndEnforceBrightness() {
        if (!mIsOverrideEnabled || iCarFunction == null)
            return;

        int targetValue;
        int funcId;
        int zone = ZONE_DRIVER; // Assuming driver zone

        // 1. Check AVM Status (Priority)
        // 0 = Inactive, 1 = Active (approx)
        boolean isAvmActive = (mLastAvmValue == 1);

        if (isAvmActive) {
            targetValue = mTargetBrightnessAvm;
            // AVM Mode: Check if we are in Day or Night to set the correct underlying
            // register
            if (mLastDayNightSensorValue == -1) {
                // Safety fallback
                funcId = FUNC_BRIGHTNESS_DAY;
            } else {
                boolean isDay = (mLastDayNightSensorValue == ISensorEvent.DAY_NIGHT_MODE_DAY);
                funcId = isDay ? FUNC_BRIGHTNESS_DAY : FUNC_BRIGHTNESS_NIGHT;
            }
        } else {
            // 2. Normal Mode
            // Strictly require valid sensor data
            if (mLastDayNightSensorValue == -1) {
                // Sensor data not ready, do not enforce anything yet to avoid wrong brightness
                return;
            }

            // Check against official boolean/int constant
            // Assuming ISensorEvent.DAY_NIGHT_MODE_DAY is integer.
            boolean isDay = (mLastDayNightSensorValue == ISensorEvent.DAY_NIGHT_MODE_DAY);
            if (isDay) {
                targetValue = mTargetBrightnessDay;
                funcId = FUNC_BRIGHTNESS_DAY;
            } else {
                targetValue = mTargetBrightnessNight;
                funcId = FUNC_BRIGHTNESS_NIGHT;
            }
        }

        // 3. Compare with current known value
        // We use our cached values which update via sensor polling
        int currentKnownValue = (funcId == FUNC_BRIGHTNESS_DAY) ? mLastBrightnessDayValue : mLastBrightnessNightValue;

        if (currentKnownValue != targetValue) {
            Log.i(TAG, "Enforcing Brightness Override. Target: " + targetValue + " Current: " + currentKnownValue
                    + " Func: " + funcId);
            try {
                // Apply value
                iCarFunction.setCustomizeFunctionValue(funcId, zone, (float) targetValue);
                // Optimistic update of local cache prevents spamming if sensor lags
                if (funcId == FUNC_BRIGHTNESS_DAY)
                    mLastBrightnessDayValue = targetValue;
                else
                    mLastBrightnessNightValue = targetValue;
            } catch (Exception e) {
                Log.e(TAG, "Failed to enforce brightness", e);
            }
        }
    }

    private void syncAutoNaviTheme() {
        SharedPreferences prefs = getSharedPreferences("navitool_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_theme_sync", true)) {
            Log.d(TAG, "Auto theme sync is disabled by user.");
            return;
        }

        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int autoNaviMode = (currentNightMode == Configuration.UI_MODE_NIGHT_YES) ? 2 : 1; // 2: Night, 1: Day

        Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_RECV");
        intent.setComponent(
                new ComponentName(AUTONAVI_PKG, "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"));
        intent.putExtra("KEY_TYPE", 10048);
        intent.putExtra("EXTRA_DAY_NIGHT_MODE", autoNaviMode);

        sendBroadcast(intent);
        Log.d(TAG, "Sent AutoNavi Theme Broadcast: Mode=" + autoNaviMode);
        DebugLogger.toast(this, getString(R.string.sent_autonavi_broadcast, autoNaviMode));
    }

    private void simulateMediaKey(int keyCode) {
        long eventTime = android.os.SystemClock.uptimeMillis();

        // Down
        android.view.KeyEvent keyDown = new android.view.KeyEvent(eventTime, eventTime,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
        dispatchMediaKey(keyDown);

        // Up
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

    // --- OneOS API Implementation ---

    private com.geely.lib.oneosapi.IServiceManager mOneOSServiceManager;
    private com.geely.lib.oneosapi.input.IInputManager mOneOSInputManager;
    private com.geely.lib.oneosapi.input.IInputListener mOneOSInputListener;
    private com.geely.lib.oneosapi.mediacenter.IMediaCenter mMediaCenter;
    private com.geely.lib.oneosapi.mediacenter.IMusicManager mMusicManager;
    private int mRetryCount = 0;
    private int mMediaRetryCount = 0;

    private static final int MAX_RETRIES = 40; // Increased from 10 to 40 for faster polling

    private final ServiceConnection mOneOSConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "========================");
            Log.i(TAG, "OneOSApiService Connected!");
            Log.i(TAG, "ComponentName: " + name);
            Log.i(TAG, "IBinder: " + service);
            Log.i(TAG, "========================");
            DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 服务已连接");

            try {
                if (service == null) {
                    Log.e(TAG, "Service IBinder is NULL!");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS IBinder 为空");
                    return;
                }

                mOneOSServiceManager = com.geely.lib.oneosapi.IServiceManager.Stub.asInterface(service);
                Log.i(TAG, "IServiceManager.Stub.asInterface called, result: " + mOneOSServiceManager);

                if (mOneOSServiceManager != null) {
                    Log.i(TAG, "IServiceManager obtained successfully");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "IServiceManager 获取成功");
                    mRetryCount = 0;
                    mMediaRetryCount = 0;
                    mOneOSInputManager = null; // Reset
                    // before
                    // trying
                    tryGetInputManager();
                    tryGetMediaCenter();
                    broadcastOneOSStatus(true);
                } else {
                    Log.e(TAG, "IServiceManager.Stub.asInterface returned NULL!");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "IServiceManager 为空");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in OneOS Service Connected", e);
                DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 连接异常: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "OneOSApiService Disconnected");
            DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 服务断开");
            mOneOSServiceManager = null;
            mOneOSInputManager = null;
            mMediaCenter = null;
            mMusicManager = null;
            broadcastOneOSStatus(false);
        }
    };

    private void broadcastOneOSStatus(boolean isConnected) {
        Intent intent = new Intent("cn.oneostool.plus.ACTION_ONEOS_STATUS");
        intent.putExtra("is_connected", isConnected);
        sendBroadcast(intent);

        // Sync UI with current media data
        if (mCurrentMediaData != null) {
            Intent mediaIntent = new Intent("cn.oneostool.plus.ACTION_MEDIA_METADATA");
            mediaIntent.putExtra("title", mCurrentMediaData.name);
            mediaIntent.putExtra("artist", mCurrentMediaData.artist);
            mediaIntent.putExtra("album", mCurrentMediaData.albumName);
            // Send has_art status so Main UI knows to load the image
            boolean hasArt = (mCurrentMediaData.albumCover != null);
            mediaIntent.putExtra("has_art", hasArt);
            sendBroadcast(mediaIntent);
        }
    }

    private void tryGetInputManager() {
        if (mOneOSServiceManager == null)
            return;
        if (mOneOSInputManager != null)
            return; // Already initialized

        try {
            // Type 8 for InputManager (based on mediacenter analysis)
            Log.i(TAG, "Calling getService(8) for InputManager...");
            IBinder inputBinder = mOneOSServiceManager.getService(8);
            Log.i(TAG, "getService(8) returned: " + inputBinder);
            // DebugLogger.toast(KeepAliveAccessibilityService.this, "getService(8)
            // returned: " + inputBinder);

            if (inputBinder != null) {
                mOneOSInputManager = com.geely.lib.oneosapi.input.IInputManager.Stub.asInterface(inputBinder);
                Log.i(TAG, "IInputManager obtained: " + mOneOSInputManager);
                DebugLogger.toast(KeepAliveAccessibilityService.this, "IInputManager 获取成功");

                try {
                    int controllerId = mOneOSInputManager.getControlIndex();
                    Log.i(TAG, "getControlIndex() returned: " + controllerId);
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "Controller ID: " + controllerId);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to call getControlIndex(): " + e.getMessage());
                }

                registerOneOSListener();
            } else {
                Log.e(TAG, "Failed to get InputManager binder (type 8) - returned NULL");
                if (mRetryCount < MAX_RETRIES) {
                    mRetryCount++;
                    Log.w(TAG, "InputManager not ready, retrying... (" + mRetryCount + ")");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 初始化中... (" + mRetryCount + ")");
                    mHandler.postDelayed(this::tryGetInputManager, 500); // Reduced from 2000ms to 500ms
                } else {
                    Log.e(TAG, "Failed to get InputManager after max retries");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 初始化失败: 重试超时");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting InputManager", e);
        }
    }

    private void bindOneOSService() {
        try {
            Log.i(TAG, "========================");
            Log.i(TAG, "Attempting to bind OneOSApiService...");
            Log.i(TAG, "========================");

            Intent intent = new Intent();
            intent.setClassName("com.geely.service.oneosapi", "com.geely.service.oneosapi.OneOSApiService");

            Log.i(TAG, "Intent created: " + intent);
            Log.i(TAG, "Package: com.geely.service.oneosapi");
            Log.i(TAG, "Class: com.geely.service.oneosapi.OneOSApiService");

            boolean bound = bindService(intent, mOneOSConnection, Context.BIND_AUTO_CREATE);

            Log.i(TAG, "bindService() returned: " + bound);

            if (bound) {
                DebugLogger.toast(this, "OneOS 服务绑定成功，等待连接...");
            } else {
                Log.e(TAG, "bindService() returned FALSE - service not found or permission denied");
                DebugLogger.toast(this, "OneOS 服务绑定失败！");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while binding OneOSApiService", e);
            DebugLogger.toast(this, "OneOS 绑定异常: " + e.getMessage());
        }
    }

    private void registerOneOSListener() {
        Log.i(TAG, "registerOneOSListener() called, mOneOSInputManager: " + mOneOSInputManager);

        if (mOneOSInputManager == null) {
            Log.e(TAG, "mOneOSInputManager is NULL, cannot register");
            DebugLogger.toast(this, "IInputManager 为空，无法注册");
            return;
        }

        try {
            int[] keyCodes = {
                    200087, 200088, 200085, 200400 // 媒体按键 + 微信按键
            };

            Log.i(TAG, "Creating IInputListener stub...");

            if (mOneOSInputListener == null) {
                mOneOSInputListener = new com.geely.lib.oneosapi.input.IInputListener.Stub() {
                    @Override
                    public void onKeyEvent(int keyCode, int event, int keyController) throws RemoteException {
                        int currentIndex = -1;
                        if (mOneOSInputManager != null) {
                            try {
                                currentIndex = mOneOSInputManager.getControlIndex();
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to get control index in onKeyEvent", e);
                            }
                        }
                        Log.i(TAG, "OneOS onKeyEvent: keyCode=" + keyCode + ", event=" + event
                                + ", paramKeyController=" + keyController + ", globalControlIndex=" + currentIndex);

                        if (event == 1) { // ACTION_UP
                            // 只处理媒体按键，微信按键由 onShortClick/onLongPressTriggered 处理
                            // 关键逻辑修改：不再依赖参数 keyController (始终为0)，而是依赖 getControlIndex() (返回2)
                            // 如果全局 Index 为 2，说明处于媒体控制模式，此时处理按键是安全的
                            /*
                             * if (DebugLogger.isDebugEnabled(KeepAliveAccessibilityService.this)) {
                             * DebugLogger.toast(KeepAliveAccessibilityService.this,
                             * "Key: " + keyCode + " Index: " + currentIndex);
                             * }
                             */
                            if (currentIndex == 2) {
                                if (keyCode == 200087 || keyCode == 200088 || keyCode == 200085) {
                                    handleShortClick(keyCode);
                                }
                            } else {
                                Log.w(TAG, "Ignored media key because globalControlIndex is " + currentIndex
                                        + " (expected 2)");
                            }
                        }
                    }

                    @Override
                    public void onShortClick(int keyCode, int softKeyFunction) throws RemoteException {
                        Log.i(TAG, "OneOS onShortClick: keyCode=" + keyCode);
                        // 只处理微信按键，媒体按键已由 onKeyEvent 处理
                        if (keyCode == 200400) {
                            DebugLogger.toast(KeepAliveAccessibilityService.this, "微信按键短按");
                            handleShortClick(keyCode);
                        }
                    }

                    @Override
                    public void onLongPressTriggered(int keyCode, int softKeyFunction) throws RemoteException {
                        Log.i(TAG, "OneOS onLongPressTriggered: keyCode=" + keyCode);
                        // 只处理微信按键
                        if (keyCode == 200400) {
                            DebugLogger.toast(KeepAliveAccessibilityService.this, "微信按键长按");
                            handleLongPress(keyCode);
                        }
                    }

                    @Override
                    public void onHoldingPressStarted(int keyCode, int softKeyFunction) throws RemoteException {
                        Log.i(TAG, "OneOS onHoldingPressStarted: keyCode=" + keyCode);
                    }

                    @Override
                    public void onHoldingPressStopped(int keyCode, int softKeyFunction) throws RemoteException {
                        Log.i(TAG, "OneOS onHoldingPressStopped: keyCode=" + keyCode);
                    }

                    @Override
                    public void onDoubleClick(int keyCode, int softKeyFunction) throws RemoteException {
                        Log.i(TAG, "OneOS onDoubleClick: keyCode=" + keyCode);
                    }
                };
            }

            Log.i(TAG, "Registering listener: " + mOneOSInputListener);
            String packageName = getPackageName();
            Log.i(TAG, "Package name: " + packageName);
            Log.i(TAG, "Key codes to register: " + java.util.Arrays.toString(keyCodes));

            // Correct signature: registerListener(IInputListener listener, String
            // packageName, int[] keyCodes)
            // Note: The error message said "Found: int[],IInputListener", implying the
            // previous call was registerListener(keyCodes, mOneOSInputListener)
            // But the error also said "Required: IInputListener,String,int[]"
            // So we must use the 3-argument version.
            mOneOSInputManager.registerListener(mOneOSInputListener, packageName, keyCodes);

            Log.i(TAG, "registerListener() COMPLETED SUCCESSFULLY!");

            DebugLogger.toast(this, "OneOS 监听已注册");

        } catch (Exception e) {
            Log.e(TAG, "========================");
            DebugLogger.toast(this, "OneOS 注册失败: " + e.getMessage());
        }
    }

    private void tryGetMediaCenter() {
        if (mOneOSServiceManager == null)
            return;
        if (mMediaCenter != null)
            return;

        try {
            // Type 3 for MediaCenter
            Log.i(TAG, "Calling getService(3) for MediaCenter...");
            IBinder binder = mOneOSServiceManager.getService(3);
            Log.i(TAG, "getService(3) returned: " + binder);

            if (binder != null) {
                mMediaCenter = com.geely.lib.oneosapi.mediacenter.IMediaCenter.Stub.asInterface(binder);
                Log.i(TAG, "IMediaCenter obtained: " + mMediaCenter);

                if (mMediaCenter != null) {
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "OneOS 媒体中心已连接");
                    mMusicManager = mMediaCenter.getMusicManager();
                    Log.i(TAG, "IMusicManager obtained: " + mMusicManager);
                    if (mMusicManager != null) {
                        DebugLogger.toast(KeepAliveAccessibilityService.this, "音乐管理器获取成功");
                        registerMusicListener();
                    }
                }
            } else {
                Log.w(TAG, "MediaCenter service (3) not ready.");
                if (mMediaRetryCount < MAX_RETRIES) {
                    mMediaRetryCount++;
                    Log.w(TAG, "MediaCenter not ready, retrying... (" + mMediaRetryCount + ")");
                    // DebugLogger.toast(KeepAliveAccessibilityService.this, "媒体服务连接中... (" +
                    // mMediaRetryCount + ")");
                    mHandler.postDelayed(this::tryGetMediaCenter, 1000);
                } else {
                    Log.e(TAG, "Failed to get MediaCenter after max retries");
                    DebugLogger.toast(KeepAliveAccessibilityService.this, "媒体服务连接失败: 超时");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting MediaCenter", e);
        }
    }

    private com.geely.lib.oneosapi.mediacenter.listener.IMusicStateListener mMusicStateListener;

    private void registerMusicListener() {
        if (mMusicManager == null)
            return;

        try {
            if (mMusicStateListener == null) {
                mMusicStateListener = new com.geely.lib.oneosapi.mediacenter.listener.IMusicStateListener.Stub() {
                    @Override
                    public void onMediaDataChanged(int source, com.geely.lib.oneosapi.mediacenter.bean.MediaData data)
                            throws RemoteException {
                        Log.i(TAG, "onMediaDataChanged source=" + source + " data=" + data);
                        // Explicit Debug Log for User
                        Log.e("MEDIA_DEBUG", ">>> NOTIFICATION RECEIVED: Source=" + source +
                                " Title=" + (data != null ? data.name : "null") +
                                " Artist=" + (data != null ? data.artist : "null") +
                                " Album=" + (data != null ? data.albumName : "null") +
                                " MediaType=" + (data != null ? data.mediaType : -1));

                        if (data != null) {
                            // Update Current Source
                            mCurrentSource = source;

                            // Try to load bitmap from URI if it's missing
                            if (data.albumCover == null && data.albumCoverUri != null
                                    && !data.albumCoverUri.isEmpty()) {
                                Log.i(TAG, "Attempting to load cover from URI: " + data.albumCoverUri);
                                data.albumCover = loadBitmapFromUri(data.albumCoverUri);
                            }

                            if (data.albumCover != null) {
                                Log.i(TAG, "Cover loaded successfully (Source: "
                                        + (data.albumCoverUri != null ? "URI" : "Bitmap") + ")");
                            } else {
                                Log.w(TAG, "No cover found (Bitmap null, URI load failed/empty)");
                            }

                            // Send broadcast to UI
                            Intent intent = new Intent("cn.oneostool.plus.ACTION_MEDIA_METADATA");
                            intent.putExtra("title", data.name);
                            intent.putExtra("artist", data.artist);
                            intent.putExtra("album", data.albumName);

                            boolean hasArt = false;
                            if (data.albumCover != null) {
                                try {
                                    java.io.File file = new java.io.File(getCacheDir(), "album_art.png");
                                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                                        data.albumCover.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                                    }
                                    hasArt = true;
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to save album art", e);
                                }
                            }
                            intent.putExtra("has_art", hasArt);
                            sendBroadcast(intent);

                            // Save to SharedPreferences for restoration on restart
                            final String fTitle = data.name;
                            final String fArtist = data.artist;
                            final String fAlbum = data.albumName;
                            mOverrideHandler.post(() -> {
                                SharedPreferences p = getSharedPreferences("navitool_prefs", MODE_PRIVATE);
                                p.edit().putString("last_media_title", fTitle)
                                        .putString("last_media_artist", fArtist)
                                        .putString("last_media_album", fAlbum)
                                        .putInt("last_media_source", mCurrentSource)
                                        .putLong("last_media_duration", data.duration)
                                        .apply();
                            });

                            // Update system MediaSession and post notification
                            mCurrentMediaData = data; // Update local field
                            updateMediaSession(data);
                        }
                    }

                    @Override
                    public void onPlayPositionChanged(int source, long current, long total) throws RemoteException {
                        // Source Filtering: Ignore position updates from inactive sources
                        if (mCurrentSource != -1 && source != mCurrentSource) {
                            return;
                        }

                        Intent intent = new Intent("cn.oneostool.plus.ACTION_MEDIA_POSITION");
                        intent.putExtra("current", current);
                        intent.putExtra("total", total);
                        sendBroadcast(intent);

                        // Update MediaSession position
                        updateMediaSessionPosition(current);
                    }

                    @Override
                    public void onPlayStateChanged(int source, int state) throws RemoteException {
                        Log.d(TAG, "onPlayStateChanged source=" + source + " state=" + state);
                        // Explicit Debug Log for User
                        Log.e("MEDIA_DEBUG", ">>> STATE CHANGE: Source=" + source + " State=" + state +
                                " (ActiveSource=" + mCurrentSource + ")");

                        // DEBUG: Toast on Main Thread
                        mOverrideHandler.post(() -> {
                            DebugLogger.toast(KeepAliveAccessibilityService.this,
                                    "PlayState: Src=" + source + " State=" + state);
                        });

                        // Source Filtering for State
                        // Strictly ignore state changes from non-active sources
                        // We rely on onMediaDataChanged to switch the active source (mCurrentSource)
                        if (mCurrentSource != -1 && source != mCurrentSource) {
                            Log.d("MEDIA_DEBUG", "IGNORING state change from non-active source: " + source);
                            return;
                        }

                        updateMediaSessionState(state);
                    }

                    @Override
                    public void onPlayListChanged(int source,
                            java.util.List<com.geely.lib.oneosapi.mediacenter.bean.MediaData> list)
                            throws RemoteException {
                        Log.d(TAG, "onPlayListChanged source=" + source);
                    }

                    @Override
                    public void onFavorStateChanged(int source,
                            com.geely.lib.oneosapi.mediacenter.bean.MediaData mediaData) throws RemoteException {
                    }

                    @Override
                    public void onLrcLoad(int source, String lrc, long time) throws RemoteException {
                    }

                    @Override
                    public void onPlayModeChange(int source, int mode) throws RemoteException {
                    }
                };
            }

            // Register for common sources
            // 4=Online, 2=BT, 1=USB, 6=Yunting
            int[] sources = { 4, 2, 1, 6 };
            for (int s : sources) {
                mMusicManager.addMusicStateListener(s, mMusicStateListener);
                Log.i(TAG, "Registered MusicStateListener for source: " + s);
            }
            DebugLogger.toast(this, "OneOS 媒体监听已注册");

        } catch (Exception e) {
            Log.e(TAG, "Failed to register music listener", e);
            DebugLogger.toast(this, "注册媒体监听失败: " + e.getMessage());
        }
    }

    private long mLastPosition = 0;
    private int mLastPlaybackState = android.media.session.PlaybackState.STATE_NONE;
    private int mCurrentSource = -1; // Track the active audio source
    private boolean mIsDebouncingPause = false; // "Soft Pause" flag

    private void updateMediaSessionPosition(long position) {
        if (!mIsMediaNotificationEnabled)
            return;

        if (mMediaSession == null)
            return;

        // Save position
        mLastPosition = position;

        // If state is unknown, assume playing if getting position updates
        if (mLastPlaybackState == android.media.session.PlaybackState.STATE_NONE) {
            mLastPlaybackState = android.media.session.PlaybackState.STATE_PLAYING;
        }

        try {
            android.media.session.PlaybackState.Builder stateBuilder = new android.media.session.PlaybackState.Builder();
            stateBuilder.setActions(android.media.session.PlaybackState.ACTION_PLAY
                    | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS);

            // If Soft Pause is active, FORCE speed to 0 but keep state PLAYING
            float speed = (mLastPlaybackState == android.media.session.PlaybackState.STATE_PLAYING) ? 1.0f : 0f;
            if (mIsDebouncingPause) {
                speed = 0.0f;
                // Ensure we are logically PLAYING so icon stays as "Play" (which means Pause
                // button shown)
                if (mLastPlaybackState != android.media.session.PlaybackState.STATE_PLAYING) {
                    mLastPlaybackState = android.media.session.PlaybackState.STATE_PLAYING;
                }
            }

            // Use current state and saved position
            stateBuilder.setState(mLastPlaybackState, mLastPosition, speed);

            mMediaSession.setPlaybackState(stateBuilder.build());

            // Ensure session is active if playing (or soft paused)
            if (mLastPlaybackState == android.media.session.PlaybackState.STATE_PLAYING && !mMediaSession.isActive()) {
                mMediaSession.setActive(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update MediaSession position", e);
        }
    }

    private android.os.Handler mStateDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mStateDebounceRunnable;

    private void updateMediaSessionState(int oneOsState) {
        if (mMediaSession == null)
            return;

        // 0 = PLAYING, 1 = PAUSED
        if (oneOsState == 0) {
            // PLAY: Cancel any pending Pause, Reset Soft Pause, Execute Play immediately
            if (mStateDebounceRunnable != null) {
                mStateDebounceHandler.removeCallbacks(mStateDebounceRunnable);
                mStateDebounceRunnable = null;
            }
            mIsDebouncingPause = false;
            executeMediaSessionStateUpdate(0);
        } else {
            // PAUSE:
            // 1. Cancel previous pending runnable to reset timer (if any)
            if (mStateDebounceRunnable != null) {
                mStateDebounceHandler.removeCallbacks(mStateDebounceRunnable);
            }

            // 2. Enable Soft Pause: Keep "Play" icon, but STOP time (Speed 0)
            mIsDebouncingPause = true;
            // Immediate update: VISUAL=PLAYING, LOGICAL=SPEED 0
            // We reuse executeMediaSessionStateUpdate(0) but mIsDebouncingPause=true will
            // force speed=0
            executeMediaSessionStateUpdate(0);

            // 3. Schedule "Hard Pause" after 5 seconds
            mStateDebounceRunnable = () -> {
                Log.d(TAG, "Debounce finished: Soft Pause -> Hard Pause");
                mIsDebouncingPause = false;
                executeMediaSessionStateUpdate(oneOsState); // actually PAUSE
                mStateDebounceRunnable = null;
            };
            mStateDebounceHandler.postDelayed(mStateDebounceRunnable, 5000); // 5000ms debounce
        }
    }

    private void executeMediaSessionStateUpdate(int oneOsState) {
        if (!mIsMediaNotificationEnabled)
            return;

        if (mMediaSession == null)
            return;

        try {
            android.media.session.PlaybackState.Builder stateBuilder = new android.media.session.PlaybackState.Builder();
            stateBuilder.setActions(android.media.session.PlaybackState.ACTION_PLAY
                    | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS);

            int state = android.media.session.PlaybackState.STATE_NONE;
            // OneOS states: 0=PLAYING, 1=PAUSED/STOPPED
            // User confirmed: 0=Play, 1=Pause, No other states.

            boolean isPlaying = false;

            switch (oneOsState) {
                case 0: // playing
                    state = android.media.session.PlaybackState.STATE_PLAYING;
                    isPlaying = true;
                    break;
                case 1: // paused/stopped
                    state = android.media.session.PlaybackState.STATE_PAUSED;
                    break;
                default:
                    state = android.media.session.PlaybackState.STATE_PAUSED; // Fallback
                    break;
            }

            // Save state
            mLastPlaybackState = state;

            // Soft Pause Logic adjustment for Speed
            float speed = isPlaying ? 1.0f : 0f;
            if (mIsDebouncingPause) {
                speed = 0.0f; // Force stop time
                // Force State to PLAYING if it isn't already, to keep icon
                if (!isPlaying) {
                    state = android.media.session.PlaybackState.STATE_PLAYING;
                    mLastPlaybackState = state; // Update tracking
                }
            }

            // Use new state and LAST KNOWN position
            stateBuilder.setState(state, mLastPosition, speed);

            mMediaSession.setPlaybackState(stateBuilder.build());

            // Always force active if we have data, to prevent disappearing info
            mMediaSession.setActive(true);

            if (!isPlaying) {
                // Do NOT cancel notification on stop/pause to ensure persistence
                // Re-posting notification to update icon to "Play" (since we are paused) logic
                // is handled by MediaStyle usually
                // For now, getting the HUD to persist is priority #1.
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to update MediaSession state", e);
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void updateMediaSession() {
        updateMediaSession(mCurrentMediaData);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void updateMediaSession(com.geely.lib.oneosapi.mediacenter.bean.MediaData data) {
        if (mMediaSession == null)
            return;

        // Media Notification Check
        if (!mIsMediaNotificationEnabled) {
            if (mMediaSession != null && mMediaSession.isActive()) {
                mMediaSession.setActive(false);
            }
            if (mNotificationManager != null) {
                mNotificationManager.cancel(NOTIFICATION_ID);
            }
            return;
        }

        if (data == null)
            return;
        // Ignore updates with missing title
        if (android.text.TextUtils.isEmpty(data.name)) {
            Log.w(TAG, "Ignoring MediaData update with empty title");
            return;
        }

        try {
            // Force session active when we receive valid metadata
            if (!mMediaSession.isActive()) {
                mMediaSession.setActive(true);
            }

            // 1. Update Metadata
            android.media.MediaMetadata.Builder metadataBuilder = new android.media.MediaMetadata.Builder();
            metadataBuilder.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, data.name);
            metadataBuilder.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, data.artist);
            metadataBuilder.putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, data.albumName);
            if (data.albumCover != null) {
                metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                        data.albumCover);
            }
            metadataBuilder.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, data.duration);
            mMediaSession.setMetadata(metadataBuilder.build());

            // 3. Post Notification
            android.app.Notification.Builder builder = new android.app.Notification.Builder(
                    KeepAliveAccessibilityService.this, CHANNEL_ID)
                    .setStyle(new android.app.Notification.MediaStyle()
                            .setMediaSession(mMediaSession.getSessionToken()))
                    .setSmallIcon(android.R.drawable.ic_media_play) // Use system icon or app icon
                    .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                    .setContentTitle(data.name)
                    .setContentText(data.artist)
                    .setOngoing(true);

            // Add dummy actions to satisfy MediaStyle look
            builder.addAction(new android.app.Notification.Action(android.R.drawable.ic_media_previous,
                    "Previous", null));
            builder.addAction(new android.app.Notification.Action(android.R.drawable.ic_media_pause,
                    "Pause", null));
            builder.addAction(new android.app.Notification.Action(android.R.drawable.ic_media_next,
                    "Next", null));

            if (data.albumCover != null) {
                builder.setLargeIcon(data.albumCover);
            }

            Log.e("MEDIA_DEBUG", ">>> POSTING SYSTEM NOTIFICATION: Title=" + data.name);
            mNotificationManager.notify(NOTIFICATION_ID, builder.build());

        } catch (Exception e) {
            Log.e(TAG, "Failed to update MediaSession", e);
        }
    }

    private android.graphics.Bitmap loadBitmapFromUri(String uriString) {
        if (android.text.TextUtils.isEmpty(uriString))
            return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(uriString);
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                // Network loading
                java.net.URL url = new java.net.URL(uriString);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url
                        .openConnection();
                connection.setDoInput(true);
                connection.connect();
                try (java.io.InputStream input = connection.getInputStream()) {
                    return android.graphics.BitmapFactory.decodeStream(input);
                }
            } else if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                // ContentResolver loading
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    return android.graphics.BitmapFactory.decodeStream(is);
                }
            } else if ("file".equals(scheme)) {
                // File URI loading
                return android.graphics.BitmapFactory.decodeFile(uri.getPath());
            } else {
                // Assume absolute path
                return android.graphics.BitmapFactory.decodeFile(uriString);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap from URI: " + uriString, e);
            return null;
        }
    }
}
