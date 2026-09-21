/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;
import static lineageos.health.HealthInterface.MODE_NONE;

import static org.lineageos.platform.internal.health.Util.getTimeMillisFromSecondOfDay;
import static org.lineageos.platform.internal.health.Util.msToString;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.DateUtils;
import android.util.Log;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;
import org.lineageos.platform.internal.health.ccprovider.ChargingControlProvider;
import org.lineageos.platform.internal.health.ccprovider.Deadline;
import org.lineageos.platform.internal.health.ccprovider.Limit;
import org.lineageos.platform.internal.health.ccprovider.Toggle;

import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;
import java.util.Calendar;

public class ChargingControlController extends LineageHealthFeature {
    private final IChargingControl mChargingControl;
    private final ContentResolver mContentResolver;
    private ChargingControlNotification mChargingNotification;
    private LineageHealthBatteryBroadcastReceiver mBattReceiver;
    private BroadcastReceiver mAlarmBroadcastReceiver;
    private boolean mIsEnabled = false;

    // Defaults
    private boolean mDefaultEnabled = false;
    private int mDefaultMode;
    private int mDefaultLimit;
    private int mDefaultStartTime;
    private int mDefaultTargetTime;

    // Settings uris
    private final Uri MODE_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_MODE);
    private final Uri LIMIT_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_LIMIT);
    private final Uri ENABLED_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_ENABLED);
    private final Uri START_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_START_TIME);
    private final Uri TARGET_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_TARGET_TIME);
    private final Uri RECHARGE_LEVEL_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_RECHARGE_LEVEL);
    private final Uri LIMIT_SCHEDULE_ENABLED_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED);
    private final Uri LIMIT_START_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_LIMIT_START_TIME);
    private final Uri LIMIT_END_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_LIMIT_END_TIME);

    // Internal state
    private float mBatteryPct;
    private boolean mIsPowerConnected;
    private boolean mIsControlCancelledOnce;
    private long mLimitScheduleAlarmAt;
    private final AlarmManager.OnAlarmListener mLimitScheduleAlarmListener = () -> {
        mLimitScheduleAlarmAt = 0;
        Log.i(TAG, "Limit schedule boundary reached, update charging control");
        updateBatteryInfo();
        updateChargeControl();
    };
    private final BroadcastReceiver mTimeChangedBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Time or timezone changed, update charging control");
            updateChargeControl();
        }
    };

    // Current selected provider
    private ChargingControlProvider mCurrentProvider;
    private Deadline mDeadline;
    private Limit mLimit;
    private Toggle mToggle;

    public ChargingControlController(Context context, Handler handler) {
        super(context, handler);

        mContentResolver = mContext.getContentResolver();
        mChargingControl = IChargingControl.Stub.asInterface(
                ServiceManager.waitForDeclaredService(
                        IChargingControl.DESCRIPTOR + "/default"));

        if (mChargingControl == null) {
            Log.i(TAG, "Lineage Health HAL not found");
            return;
        }

        mChargingNotification = new ChargingControlNotification(context, this);

        mDefaultEnabled = mContext.getResources().getBoolean(
                R.bool.config_chargingControlEnabled);
        mDefaultMode = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlMode);
        mDefaultStartTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlStartTime);
        mDefaultTargetTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlTargetTime);
        mDefaultLimit = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlLimit);

        // Set up charging control providers
        mDeadline = new Deadline(mChargingControl, mContext);
        mLimit = new Limit(mChargingControl, mContext);
        mToggle = new Toggle(mChargingControl, mContext);

        mCurrentProvider = getProviderForMode(getMode());
        if (mCurrentProvider == null) {
            if (mLimit.isSupported()) {
                mCurrentProvider = mLimit;
            } else if (mToggle.isSupported()) {
                mCurrentProvider = mToggle;
            } else if (mDeadline.isSupported()) {
                mCurrentProvider = mDeadline;
            } else {
                Log.wtf(TAG, "No charging control provider is supported");
            }
        }
    }

    @Override
    public boolean isSupported() {
        return mChargingControl != null;
    }

    public boolean isEnabled() {
        return LineageSettings.System.getInt(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_ENABLED, 0) != 0;
    }

    public boolean setEnabled(boolean enabled) {
        putBoolean(LineageSettings.System.CHARGING_CONTROL_ENABLED, enabled);
        return true;
    }

    public int getMode() {
        return LineageSettings.System.getInt(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_MODE,
                mDefaultMode);
    }

    public boolean setMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_LIMIT) {
            return false;
        }

        mCurrentProvider = getProviderForMode(mode);

        if (mCurrentProvider == null) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_MODE, mode);
        return true;
    }

    ChargingControlProvider getProviderForMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_LIMIT) {
            return null;
        }

        if (mode == MODE_LIMIT) {
            if (mLimit.isSupported()) {
                return mLimit;
            }
            if (mToggle.isSupported()) {
                return mToggle;
            }
        } else if (mode == MODE_AUTO || mode == MODE_MANUAL) {
            if (mDeadline.isSupported()) {
                return mDeadline;
            }
            if (mLimit.isSupported()) {
                return mLimit;
            }
            if (mToggle.isSupported()) {
                return mToggle;
            }
        }

        return null;
    }

    public int getStartTime() {
        return LineageSettings.System.getInt(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_START_TIME,
                mDefaultStartTime);
    }

    public boolean setStartTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_START_TIME, time);
        return true;
    }

    public int getTargetTime() {
        return LineageSettings.System.getInt(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_TARGET_TIME,
                mDefaultTargetTime);
    }

    public boolean setTargetTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_TARGET_TIME, time);
        return true;
    }

    public int getLimit() {
        return LineageSettings.System.getInt(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_LIMIT,
                mDefaultLimit);
    }

    public boolean setLimit(int limit) {
        if (limit < 0 || limit > 100) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_LIMIT, limit);
        return true;
    }

    private boolean isLimitScheduleEnabled() {
        return getBoolean(LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED, false);
    }

    private int getLimitScheduleStartTime() {
        return getInt(LineageSettings.System.CHARGING_CONTROL_LIMIT_START_TIME,
                mDefaultStartTime);
    }

    private int getLimitScheduleEndTime() {
        return getInt(LineageSettings.System.CHARGING_CONTROL_LIMIT_END_TIME,
                mDefaultTargetTime);
    }

    private int getRechargeLevel() {
        final int maxRechargeLevel = Math.max(20, getLimit() - 5);
        return Math.max(20, Math.min(getInt(
                LineageSettings.System.CHARGING_CONTROL_RECHARGE_LEVEL,
                maxRechargeLevel), maxRechargeLevel));
    }

    private boolean isWithinLimitSchedule() {
        if (!isLimitScheduleEnabled()) {
            return true;
        }

        final int startTime = getLimitScheduleStartTime();
        final int endTime = getLimitScheduleEndTime();
        if (startTime == endTime) {
            // Equal times represent an all-day window.
            return true;
        }

        final Calendar now = Calendar.getInstance();
        final int secondOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 * 60
                + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);

        if (startTime < endTime) {
            return secondOfDay >= startTime && secondOfDay < endTime;
        }
        // Window crosses midnight, e.g. 22:00 -> 06:00.
        return secondOfDay >= startTime || secondOfDay < endTime;
    }

    private long getNextLimitScheduleBoundary() {
        final int startTime = getLimitScheduleStartTime();
        final int endTime = getLimitScheduleEndTime();
        if (startTime == endTime) {
            return 0;
        }

        final int boundary = isWithinLimitSchedule() ? endTime : startTime;
        final Calendar now = Calendar.getInstance();
        final Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, boundary / (60 * 60));
        next.set(Calendar.MINUTE, (boundary / 60) % 60);
        next.set(Calendar.SECOND, boundary % 60);
        next.set(Calendar.MILLISECOND, 0);

        if (next.getTimeInMillis() <= now.getTimeInMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    private void updateLimitScheduleAlarm() {
        final AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }

        if (mLimitScheduleAlarmAt != 0) {
            alarmManager.cancel(mLimitScheduleAlarmListener);
            mLimitScheduleAlarmAt = 0;
        }

        if (!isEnabled() || getMode() != MODE_LIMIT || !isLimitScheduleEnabled()) {
            return;
        }

        final long nextBoundary = getNextLimitScheduleBoundary();
        if (nextBoundary == 0) {
            return;
        }

        alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextBoundary,
                TAG + ":limit_schedule", mLimitScheduleAlarmListener, mHandler);
        mLimitScheduleAlarmAt = nextBoundary;
        Log.i(TAG, "Scheduled next limit charging boundary at "
                + msToString(mContext, nextBoundary));
    }

    public boolean reset() {
        final boolean result = setEnabled(mDefaultEnabled)
                && setMode(mDefaultMode)
                && setLimit(mDefaultLimit)
                && setStartTime(mDefaultStartTime)
                && setTargetTime(mDefaultTargetTime);

        putInt(LineageSettings.System.CHARGING_CONTROL_RECHARGE_LEVEL,
                Math.max(20, mDefaultLimit - 5));
        putBoolean(LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED, false);
        putInt(LineageSettings.System.CHARGING_CONTROL_LIMIT_START_TIME, mDefaultStartTime);
        putInt(LineageSettings.System.CHARGING_CONTROL_LIMIT_END_TIME, mDefaultTargetTime);
        return result;
    }

    private void updateBatteryInfo(Intent intent) {
        int battStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int battPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        if (battStatus == BatteryManager.BATTERY_STATUS_FULL) {
            mIsControlCancelledOnce = false;
        }

        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            mIsPowerConnected = true;
        } else {
            mIsPowerConnected =
                    battPlugged != 0 || (battStatus != BatteryManager.BATTERY_STATUS_DISCHARGING &&
                            battStatus != BatteryManager.BATTERY_STATUS_UNKNOWN);
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level == -1 || scale == -1) {
            return;
        }

        mBatteryPct = level * 100 / (float) scale;

        Log.i(TAG, "mIsPowerConnected: " + mIsPowerConnected + ", mBatteryPct: " + mBatteryPct);
    }

    private void updateBatteryInfo() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED);
        if (batteryStatus == null) {
            Log.e(TAG, "batteryStatus is NULL!");
            return;
        }
        updateBatteryInfo(batteryStatus);
    }

    @Override
    public void onStart() {
        if (mCurrentProvider == null || mChargingControl == null) {
            return;
        }

        // Register setting observer
        registerSettings(MODE_URI, LIMIT_URI, ENABLED_URI, START_TIME_URI, TARGET_TIME_URI,
                RECHARGE_LEVEL_URI, LIMIT_SCHEDULE_ENABLED_URI, LIMIT_START_TIME_URI,
                LIMIT_END_TIME_URI);

        final IntentFilter timeChangedFilter = new IntentFilter();
        timeChangedFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeChangedFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mTimeChangedBroadcastReceiver, timeChangedFilter);

        handleSettingChange();
    }

    public boolean isChargingModeSupported(int mode) {
        try {
            return isSupported() && (mChargingControl.getSupportedMode() & mode) != 0;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    protected void resetInternalState() {
        if (mCurrentProvider == null) {
            return;
        }

        mIsControlCancelledOnce = false;
        mChargingNotification.cancel();

        mCurrentProvider.reset();
    }

    protected void setChargingCancelledOnce() {
        if (mCurrentProvider == null) {
            return;
        }

        mIsControlCancelledOnce = true;

        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            IntentFilter disconnectFilter = new IntentFilter(
                    Intent.ACTION_POWER_DISCONNECTED);

            // Register a one-time receiver that resets internal state on power
            // disconnection
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "Power disconnected, reset internal states");
                    resetInternalState();
                    mContext.unregisterReceiver(this);
                }
            }, disconnectFilter);
        }

        mCurrentProvider.disable();
        mChargingNotification.cancel();
    }

    private void onPowerConnected() {
        if (mBattReceiver == null) {
            mBattReceiver = new LineageHealthBatteryBroadcastReceiver();
        } else {
            mContext.unregisterReceiver(mBattReceiver);
        }
        IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBattReceiver, battFilter);
    }

    private void onPowerDisconnected() {
        if (mBattReceiver != null) {
            mContext.unregisterReceiver(mBattReceiver);
            mBattReceiver = null;
        }

        // On disconnected, reset internal state
        resetInternalState();
    }

    private void onPowerStatus(boolean enable) {
        // Don't do anything if it is not enabled
        if (!isEnabled()) {
            return;
        }

        if (enable) {
            onPowerConnected();
            updateChargeControl();
        } else {
            onPowerDisconnected();
        }
    }

    private ChargeTime getChargeTime() {
        // Get duration to target full time
        final long currentTime = System.currentTimeMillis();
        Log.i(TAG, "Current time is " + msToString(mContext, currentTime));
        long targetTime = 0, startTime = currentTime;
        int mode = getMode();

        if (mode == MODE_AUTO) {
            // Use alarm as the target time. Maybe someday we can use a model.
            AlarmManager m = mContext.getSystemService(AlarmManager.class);
            if (m == null) {
                Log.e(TAG, "Failed to get alarm service!");
                mChargingNotification.cancel();
                return null;
            }
            AlarmManager.AlarmClockInfo alarmClockInfo = m.getNextAlarmClock();
            if (alarmClockInfo == null) {
                // We didn't find an alarm. Clear waiting flags because we can't predict anyway
                Log.w(TAG, "No alarm found, auto charging control has no effect");
                mChargingNotification.cancel();
                return null;
            }
            targetTime = alarmClockInfo.getTriggerTime();

            // Start time is 9 hours before the alarm
            startTime = targetTime - DateUtils.HOUR_IN_MILLIS * 9;
        } else if (mode == MODE_MANUAL) {
            // User manually controlled time
            startTime = getTimeMillisFromSecondOfDay(getStartTime());
            targetTime = getTimeMillisFromSecondOfDay(getTargetTime());

            if (startTime > targetTime) {
                if (currentTime > targetTime) {
                    targetTime += DateUtils.DAY_IN_MILLIS;
                } else {
                    startTime -= DateUtils.DAY_IN_MILLIS;
                }
            } else if (currentTime >= targetTime) {
                startTime += DateUtils.DAY_IN_MILLIS;
                targetTime += DateUtils.DAY_IN_MILLIS;
            }
        } else {
            Log.e(TAG, "invalid charging control mode " + mode);
            return null;
        }

        Log.i(TAG, "Got target time " + msToString(mContext, targetTime)
                + ", start time " + msToString(mContext, startTime)
                + ", current time " + msToString(mContext, currentTime));
        Log.i(TAG, "Raw: " + targetTime + ", " + startTime + ", " + currentTime);

        return new ChargeTime(startTime, targetTime);
    }

    private void updateAutoAlarmReceiver(int mode) {
        if (mode == MODE_AUTO) {
            if (mAlarmBroadcastReceiver == null) {
                IntentFilter alarmChangedFilter = new IntentFilter(
                        AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
                mAlarmBroadcastReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.i(TAG, "Alarm changed, update charge times");
                        updateChargeControl();
                    }
                };
                mContext.registerReceiver(mAlarmBroadcastReceiver, alarmChangedFilter);
            }
        } else if (mAlarmBroadcastReceiver != null) {
            mContext.unregisterReceiver(mAlarmBroadcastReceiver);
            mAlarmBroadcastReceiver = null;
        }
    }

    protected void updateChargeControl() {
        if (mCurrentProvider == null) {
            return;
        }

        final int mode = getMode();
        updateAutoAlarmReceiver(mode);
        updateLimitScheduleAlarm();

        if (!isEnabled() || mIsControlCancelledOnce || !mIsPowerConnected) {
            mCurrentProvider.disable();
            mChargingNotification.cancel();
            return;
        }

        if (mode == MODE_LIMIT && isLimitScheduleEnabled() && !isWithinLimitSchedule()) {
            Log.i(TAG, "Outside limit charging schedule, restore normal charging");
            mCurrentProvider.disable();
            mChargingNotification.cancel();
            return;
        }

        final int limit = getLimit();

        mCurrentProvider.enable();

        if (mode == MODE_LIMIT) {
            if (mCurrentProvider.update(mBatteryPct, limit, getRechargeLevel())
                    && mIsPowerConnected) {
                mChargingNotification.post(limit, mBatteryPct >= limit);
            } else {
                mChargingNotification.cancel();
            }
        } else {
            ChargeTime chargeTime = getChargeTime();
            if (chargeTime != null) {
                if (mCurrentProvider.update(mBatteryPct, chargeTime.getStartTime(),
                        chargeTime.getTargetTime(), mode)) {
                    mChargingNotification.post(chargeTime.getTargetTime(),
                            mBatteryPct == 100);
                } else {
                    mChargingNotification.cancel();
                }
            }
        }
    }

    /**
     * Whether the current charging control mode supports supports the mode.
     * Available modes:
     *     - ${@link lineageos.health.HealthInterface#MODE_AUTO}
     *     - ${@link lineageos.health.HealthInterface#MODE_MANUAL}
     *     - ${@link lineageos.health.HealthInterface#MODE_LIMIT}
     */
    private boolean isProvideSupportCCMode(int mode) {
        if (mCurrentProvider == null) {
            return false;
        }

        return mCurrentProvider.isChargingControlModeSupported(mode);
    }

    private void handleSettingChange() {
        int mode = getMode();

        if (mIsEnabled != isEnabled()) {
            mIsEnabled = isEnabled();

            if (mIsEnabled) {
                if (mBattReceiver == null) {
                    mBattReceiver = new LineageHealthBatteryBroadcastReceiver();
                } else {
                    mContext.unregisterReceiver(mBattReceiver);
                }
                IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                mContext.registerReceiver(mBattReceiver, battFilter);
                Log.i(TAG, "Enabled charging control, start monitoring battery");
            } else {
                if (mBattReceiver != null) {
                    mContext.unregisterReceiver(mBattReceiver);
                    mBattReceiver = null;
                }
                Log.i(TAG, "Disabled charging control, stop monitoring battery");
            }
        }

        if (!isProvideSupportCCMode(mode)) {
            Log.e(TAG, "Current provider does not support mode: " + mode
                    + ", setting to default mode");
            setMode(mDefaultMode);
        }

        // Reset internal states
        resetInternalState();

        // Update battery info
        updateBatteryInfo();

        // Update based on those values
        updateChargeControl();
    }

    @Override
    protected void onSettingsChanged(Uri uri) {
        handleSettingChange();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("ChargingControlController Configuration:");
        pw.println("  Enabled: " + isEnabled());
        pw.println("  Mode: " + getMode());
        pw.println("  Limit: " + getLimit());
        pw.println("  StartTime: " + getStartTime());
        pw.println("  TargetTime: " + getTargetTime());
        pw.println("  RechargeLevel: " + getRechargeLevel());
        pw.println("  LimitScheduleEnabled: " + isLimitScheduleEnabled());
        pw.println("  LimitScheduleStartTime: " + getLimitScheduleStartTime());
        pw.println("  LimitScheduleEndTime: " + getLimitScheduleEndTime());
        pw.println();
        pw.println("ChargingControlController State:");
        pw.println("  mIsEnabled: " + mIsEnabled);
        pw.println("  mBatteryPct: " + mBatteryPct);
        pw.println("  mIsPowerConnected: " + mIsPowerConnected);
        pw.println("  mIsNotificationPosted: " + mChargingNotification.isPosted());
        pw.println("  mIsDoneNotification: " + mChargingNotification.isDoneNotification());
        pw.println("  mIsControlCancelledOnce: " + mIsControlCancelledOnce);
        pw.println();
        if (mCurrentProvider != null) {
            mCurrentProvider.dump(pw);
        }
    }

    /* Battery Broadcast Receiver */
    private class LineageHealthBatteryBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryInfo(intent);
            updateChargeControl();
        }
    }

    /* A representation of start and target time */
    static final class ChargeTime {
        private final long mStartTime;
        private final long mTargetTime;

        ChargeTime(long startTime, long targetTime) {
            mStartTime = startTime;
            mTargetTime = targetTime;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public long getTargetTime() {
            return mTargetTime;
        }
    }
}
