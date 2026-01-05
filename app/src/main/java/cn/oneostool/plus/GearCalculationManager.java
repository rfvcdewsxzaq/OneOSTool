package cn.oneostool.plus;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent;
import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.ecarx.xui.adaptapi.car.base.ICarFunction;

public class GearCalculationManager implements ISensor.ISensorListener, ICarFunction.IFunctionValueWatcher {
    private static final String TAG = "GearCalcManager";
    public static final String ACTION_UPDATE_RPM = "cn.oneostool.plus.ACTION_UPDATE_RPM";
    public static final String ACTION_UPDATE_GEAR = "cn.oneostool.plus.ACTION_UPDATE_GEAR";
    public static final String ACTION_UPDATE_DRIVE_MODE = "cn.oneostool.plus.ACTION_UPDATE_DRIVE_MODE";
    public static final String EXTRA_RPM = "rpm_value";
    public static final String EXTRA_GEAR = "gear_value";
    public static final String EXTRA_DRIVE_MODE = "drive_mode_value";

    private Context mContext;
    private ISensor mSensorManager;
    private ICarFunction mCarFunction;
    private boolean mIsInitialized = false;

    // Gear Calculation Variables
    private volatile float mLastCarSpeed = 0f;
    private volatile float mLastRpm = 0f;
    private volatile String mCurrentSensorGear = "P";
    private volatile int mCurrentDriveMode = -1;

    // Vehicle Parameters (Geely Xingyue L settings)
    private static final float FINAL_DRIVE_RATIO = 3.329f;
    private static final float[] GEAR_RATIOS = {
            5.25f, // 1st
            3.029f, // 2nd
            1.95f, // 3rd
            1.457f, // 4th
            1.221f, // 5th
            1.0f, // 6th
            0.809f, // 7th
            0.673f // 8th
    };

    // Tire Specs: 245/45 R20
    private static final float TIRE_WIDTH = 245f;
    private static final float ASPECT_RATIO = 0.45f;
    private static final float RIM_DIAMETER = 20f;
    private float mTireRadius;

    // Drive Mode Constants (Copied from BasicDataHandler)
    private static final int DRIVE_MODE_SELECTION_SNOW = 570491145;

    // History for smoothing
    private List<String> mGearHistory = new ArrayList<>();
    private static final int MAX_HISTORY_SIZE = 3;
    private String mLastValidGear = "P";
    private long mLastCalculationTime = 0;

    public GearCalculationManager(Context context) {
        mContext = context;
        mTireRadius = calculateTireRadius(TIRE_WIDTH, ASPECT_RATIO, RIM_DIAMETER);
    }

    public void init(ISensor sensorManager, ICarFunction carFunction) {
        if (mIsInitialized)
            return;
        mSensorManager = sensorManager;
        mCarFunction = carFunction;

        try {
            if (mSensorManager != null) {
                mSensorManager.registerListener(this, ISensor.SENSOR_TYPE_RPM);
                mSensorManager.registerListener(this, ISensor.SENSOR_TYPE_GEAR);
                mSensorManager.registerListener(this, ISensor.SENSOR_TYPE_CAR_SPEED);
            }

            if (mCarFunction != null) {
                mCarFunction.registerFunctionValueWatcher(IDriveMode.DM_FUNC_DRIVE_MODE_SELECT, this);
            }

            mIsInitialized = true;
            Log.i(TAG, "GearCalculationManager initialized and listeners registered.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register sensor listeners", e);
        }
    }

    public void cleanup() {
        if (mIsInitialized) {
            try {
                if (mSensorManager != null) {
                    // Correct: unregisterListener takes only the listener instance and unregisters
                    // all
                    mSensorManager.unregisterListener(this);
                }
                if (mCarFunction != null) {
                    // Correct: unregisterFunctionValueWatcher takes only the listener instance
                    mCarFunction.unregisterFunctionValueWatcher(this);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering listeners", e);
            }
        }
        mIsInitialized = false;
    }

    public void setDriveMode(int mode) {
        if (mCurrentDriveMode != mode) {
            mCurrentDriveMode = mode;
            broadcastDriveMode(mode);
        }
        // Trigger recalc if needed (already called in onFunctionValueChanged indirectly
        // if we wanted,
        // but here we just update state. Real-time calc runs on its own or via sensor
        // updates.)
        // But for snow mode switching we might want immediate recalc?
        // BasicDataHandler logic had immediate recalc.
        // We will just let the next sensor update (which is frequent) handle it, or we
        // could force it.
    }

    private float calculateTireRadius(float widthMm, float aspectRatio, float rimDiameterInch) {
        float sidewallHeight = widthMm * aspectRatio;
        float tireDiameterMm = (rimDiameterInch * 25.4f) + (2 * sidewallHeight);
        return (tireDiameterMm / 1000f) / 2f;
    }

    @Override
    public void onSensorValueChanged(int sensorType, float value) {
        switch (sensorType) {
            case ISensor.SENSOR_TYPE_RPM:
                mLastRpm = value;
                broadcastRpm((int) value);
                calculateAndBroadcastGear();
                break;
            case ISensor.SENSOR_TYPE_CAR_SPEED:
                // Note: The value here might be raw sensor value.
                // In KeepAliveService it multiplied by 3.72f. We should consistent.
                // Assuming raw value here, we match the logic.
                // In MConfigThread it was: val speedKmh = (value * 3.72).roundToInt()
                float speedKmh = value * 3.72f;
                mLastCarSpeed = speedKmh;
                calculateAndBroadcastGear();
                break;
        }
    }

    @Override
    public void onSensorEventChanged(int sensorType, int value) {
        if (sensorType == ISensor.SENSOR_TYPE_GEAR) {
            String newGear = mapGearValueToString(value);
            if (!newGear.equals(mCurrentSensorGear)) {
                mCurrentSensorGear = newGear;
                calculateAndBroadcastGear();
            }
        }
    }

    @Override
    public void onFunctionValueChanged(int functionId, int zone, int value) {
        if (functionId == IDriveMode.DM_FUNC_DRIVE_MODE_SELECT) {
            setDriveMode(value);
        }
    }

    @Override
    public void onFunctionChanged(int functionId) {
        // Implement abstract method
    }

    @Override
    public void onCustomizeFunctionValueChanged(int i, int i1, float v) {
    }

    @Override
    public void onSupportedFunctionStatusChanged(int i, int i1, FunctionStatus functionStatus) {
    }

    @Override
    public void onSupportedFunctionValueChanged(int i, int[] ints) {
    }

    @Override
    public void onSensorSupportChanged(int i, FunctionStatus functionStatus) {
    }

    private String mapGearValueToString(int value) {
        switch (value) {
            case ISensorEvent.GEAR_DRIVE:
                return "D";
            case ISensorEvent.GEAR_REVERSE:
                return "R";
            case ISensorEvent.GEAR_NEUTRAL:
                return "N";
            case ISensorEvent.GEAR_PARK:
                return "P";
            // Manual Mode usually maps to unknown or specific enum in some systems,
            // verifying with MConfigThread logic: GEAR_UNKNOWN -> "M"
            case ISensorEvent.GEAR_UNKNOWN:
                return "M";
            default:
                return "P";
        }
    }

    private void calculateAndBroadcastGear() {
        long now = System.currentTimeMillis();
        // Limit calculation frequency slightly if needed, but usually real-time is fine
        // for UI
        if (now - mLastCalculationTime < 50)
            return;
        mLastCalculationTime = now;

        String finalGear = mCurrentSensorGear;

        // Only calculate for D or M
        if (mCurrentSensorGear.equals("D") || mCurrentSensorGear.equals("M")) {
            // Static/Low Speed handling
            if (mLastCarSpeed <= 0.5f || mLastRpm <= 0.1f) {
                if (mCurrentSensorGear.equals("D")) {
                    if (mCurrentDriveMode == DRIVE_MODE_SELECTION_SNOW) {
                        finalGear = "D2";
                    } else {
                        finalGear = "D1";
                    }
                } else if (mCurrentSensorGear.equals("M")) {
                    finalGear = "M1";
                }
            } else {
                // Calculation Logic
                finalGear = calculateGearByRatio(mLastCarSpeed, mLastRpm);
            }
        }

        // Smooth output
        finalGear = smoothGearOutput(finalGear);

        broadcastGear(finalGear);
    }

    private String calculateGearByRatio(float speedKmh, float rpm) {
        // Safety check - If speed is very low, force starting gear
        if (speedKmh <= 10.0f) {
            if (mCurrentSensorGear.equals("D")) {
                if (mCurrentDriveMode == DRIVE_MODE_SELECTION_SNOW)
                    return "D2";
                return "D1";
            }
            if (mCurrentSensorGear.equals("M"))
                return "M1";
            return mCurrentSensorGear;
        }

        // Special Snow Mode low speed check (redundant with above but kept for logic
        // structure if we lower threshold later)
        if (mCurrentDriveMode == DRIVE_MODE_SELECTION_SNOW && mCurrentSensorGear.equals("D")) {
            if (speedKmh < 15.0f && rpm < 1500f) {
                return "D2";
            }
        }

        float speedMps = speedKmh / 3.6f;
        float rpmRadPerSec = rpm * (2f * (float) Math.PI / 60f);

        // Avoid division by zero
        if (speedMps < 0.001f)
            return mCurrentSensorGear;

        float calculatedRatio = (rpmRadPerSec * mTireRadius) / (speedMps * FINAL_DRIVE_RATIO);

        float minDiff = Float.MAX_VALUE;
        int bestGear = 0;

        for (int i = 0; i < GEAR_RATIOS.length; i++) {
            float diff = Math.abs(calculatedRatio - GEAR_RATIOS[i]);
            if (diff < minDiff) {
                minDiff = diff;
                bestGear = i + 1;
            }
        }

        // Snow mode minimum gear restriction
        if (mCurrentDriveMode == DRIVE_MODE_SELECTION_SNOW && bestGear < 2) {
            bestGear = 2;
        }

        // Error threshold dynamically (from MConfigThread)
        float errorThreshold;
        if (speedKmh < 20)
            errorThreshold = 1.0f;
        else if (speedKmh < 60)
            errorThreshold = 0.6f;
        else
            errorThreshold = 0.4f;

        if (minDiff < errorThreshold) {
            return mCurrentSensorGear + bestGear;
        }

        return mCurrentSensorGear;
    }

    private String smoothGearOutput(String newGear) {
        mGearHistory.add(newGear);
        if (mGearHistory.size() > MAX_HISTORY_SIZE) {
            mGearHistory.remove(0);
        }

        if (mGearHistory.size() < 2) {
            mLastValidGear = newGear;
            return newGear;
        }

        // Find mode (most common element)
        Map<String, Integer> counts = new HashMap<>();
        for (String g : mGearHistory) {
            counts.put(g, counts.getOrDefault(g, 0) + 1);
        }

        String mode = null;
        int maxCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mode = entry.getKey();
            }
        }

        if (mode != null && maxCount >= 2) {
            mLastValidGear = mode;
            return mode;
        }

        return mLastValidGear;
    }

    private void broadcastRpm(int rpm) {
        Intent intent = new Intent(ACTION_UPDATE_RPM);
        intent.putExtra(EXTRA_RPM, rpm);
        mContext.sendBroadcast(intent);
    }

    private void broadcastGear(String gear) {
        Intent intent = new Intent(ACTION_UPDATE_GEAR);
        intent.putExtra(EXTRA_GEAR, gear);
        mContext.sendBroadcast(intent);
    }

    private void broadcastDriveMode(int mode) {
        Intent intent = new Intent(ACTION_UPDATE_DRIVE_MODE);
        intent.putExtra(EXTRA_DRIVE_MODE, mode);
        mContext.sendBroadcast(intent);
    }
}
