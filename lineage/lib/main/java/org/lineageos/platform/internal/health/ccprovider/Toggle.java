/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health.ccprovider;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;

import static org.lineageos.platform.internal.health.Util.msToHMSString;
import static org.lineageos.platform.internal.health.Util.msToString;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.SystemClock;
import android.util.Log;

import org.lineageos.platform.internal.R;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class Toggle extends ChargingControlProvider {
    private final int mChargingTimeMargin;
    private final BatteryStatsManager mBatteryStatsManager;

    private final boolean mToggleSetAlways = mContext.getResources().getBoolean(
            R.bool.config_chargingControlToggleSetAlways);
    private boolean mIsLimitSet;
    private long mSavedTargetTime;
    private long mEstimatedFullTime;
    private long mLastEstimateQueryElapsed;
    private long mCachedChargeTimeRemaining = -1;
    private boolean mWasPlugged;
    private chgCtrlStage mStage = chgCtrlStage.STAGE_NONE;

    private enum chgCtrlStage {
        /**
         * It has no effect
         */
        STAGE_NONE,

        /**
         * The battery level is less than 80%
         */
        STAGE_INITIAL,

        /**
         * The battery level reached 80% and is now waiting
         */
        STAGE_WAITING,

        /**
         * The battery is now charging towards 100%
         */
        STAGE_CONTINUE,
    }

    // Only when the battery level is above this limit will the charging control be activated.
    private final static int CHARGE_CTRL_MIN_LEVEL = 80;

    public Toggle(IChargingControl chargingControl,
            Context context) {
        super(context, chargingControl);

        mChargingTimeMargin = mContext.getResources().getInteger(
                R.integer.config_chargingControlTimeMargin) * 60 * 1000;
        mBatteryStatsManager = mContext.getSystemService(BatteryStatsManager.class);
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.TOGGLE);
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return !isHALModeSupported(ChargingControlSupportedMode.BYPASS);
    }

    @Override
    protected boolean onBatteryChanged(float currentPct, int targetPct, int rechargeLevel) {
        mIsLimitSet = shouldStopCharging(currentPct, targetPct, rechargeLevel);
        Log.i(TAG, "Current battery level: " + currentPct + ", target: " + targetPct
                + ", recharge level: " + rechargeLevel + ", limit set: " + mIsLimitSet);
        return setChargingEnabled(!mIsLimitSet);
    }

    private boolean onStage(chgCtrlStage stage) {
        switch (stage) {
            case STAGE_NONE -> {
                setChargingEnabled(true);
                return false;
            }
            case STAGE_INITIAL, STAGE_CONTINUE -> {
                return setChargingEnabled(true);
            }
            case STAGE_WAITING -> {
                return setChargingEnabled(false);
            }
        }

        return false;
    }

    private chgCtrlStage getNextStage(float batteryPct, long startTime, long targetTime) {
        final long currentTime = System.currentTimeMillis();
        chgCtrlStage stage = mStage;

        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        final Intent batteryStatus =
                mContext.registerReceiver(null, filter, Context.RECEIVER_EXPORTED);
        final boolean plugged = batteryStatus != null
                && batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

        if (plugged != mWasPlugged) {
            mWasPlugged = plugged;
            invalidateChargeTimeEstimate();
        }

        if (startTime > currentTime && stage != chgCtrlStage.STAGE_CONTINUE) {
            // Not yet entering user configured time frame.
            return chgCtrlStage.STAGE_NONE;
        }

        if (mSavedTargetTime != targetTime && (mSavedTargetTime == 0
                || mSavedTargetTime >= currentTime)) {
            Log.i(TAG, "User changed target time, reassign it");
            mSavedTargetTime = targetTime;
            invalidateChargeTimeEstimate();
            stage = chgCtrlStage.STAGE_INITIAL;
        }

        final long deltaTime = targetTime - currentTime;
        if (DEBUG) {
            Log.d(TAG, "Current time to target: " + msToHMSString(deltaTime));
        }

        switch (stage) {
            case STAGE_NONE, STAGE_INITIAL -> {
                if (!plugged || batteryPct < CHARGE_CTRL_MIN_LEVEL) {
                    return chgCtrlStage.STAGE_INITIAL;
                }

                final long remaining = getEstimatedChargeTimeRemaining();
                if (remaining < 0) {
                    return chgCtrlStage.STAGE_INITIAL;
                } else if (deltaTime > remaining) {
                    // NONE/INITIAL -> WAITING: battery level >= 80% && have enough time waiting.
                    mEstimatedFullTime = remaining;
                    return chgCtrlStage.STAGE_WAITING;
                } else {
                    // NONE/INITIAL -> CONTINUE: battery level >= 80% && not enough time waiting.
                    return chgCtrlStage.STAGE_CONTINUE;
                }
            }
            case STAGE_WAITING -> {
                return deltaTime <= mEstimatedFullTime
                        ? chgCtrlStage.STAGE_CONTINUE : chgCtrlStage.STAGE_WAITING;
            }
            case STAGE_CONTINUE -> {
                return plugged ? chgCtrlStage.STAGE_CONTINUE : chgCtrlStage.STAGE_INITIAL;
            }
        }

        Log.e(TAG, "Possible bug: code reaches out of switch case");
        return chgCtrlStage.STAGE_NONE;
    }

    private long getEstimatedChargeTimeRemaining() {
        final long now = SystemClock.elapsedRealtime();
        if (mLastEstimateQueryElapsed != 0
                && now - mLastEstimateQueryElapsed < ESTIMATE_REFRESH_INTERVAL_MS) {
            return mCachedChargeTimeRemaining;
        }

        mLastEstimateQueryElapsed = now;
        if (mBatteryStatsManager == null) {
            mCachedChargeTimeRemaining = -1;
            return -1;
        }

        try {
            final BatteryUsageStats stats = mBatteryStatsManager.getBatteryUsageStats();
            final long estimate = stats.getChargeTimeRemainingMs();
            mCachedChargeTimeRemaining =
                    estimate < 0 ? -1 : estimate + mChargingTimeMargin;
            if (DEBUG) {
                Log.d(TAG, "Current estimated time to full: "
                        + msToHMSString(mCachedChargeTimeRemaining));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to query charge time estimate", e);
            mCachedChargeTimeRemaining = -1;
        }
        return mCachedChargeTimeRemaining;
    }

    private void invalidateChargeTimeEstimate() {
        mLastEstimateQueryElapsed = 0;
        mCachedChargeTimeRemaining = -1;
    }

    @Override
    protected boolean onBatteryChanged(float batteryPct, long startTime, long targetTime,
            int configMode) {
        if (configMode != MODE_AUTO && configMode != MODE_MANUAL) {
            Log.e(TAG,
                    "Possible bug: onBatteryChanged called with unsupported mode: " + configMode);
            return false;
        }

        final chgCtrlStage prevStage = mStage;
        mStage = getNextStage(batteryPct, startTime, targetTime);
        if (prevStage != mStage) {
            Log.i(TAG, "State change: " + prevStage + " -> " + mStage);
        }

        return onStage(mStage);
    }

    private boolean setChargingEnabled(boolean enabled) {
        try {
            if (mToggleSetAlways) {
                mChargingControl.setChargingEnabled(enabled);
            } else if (mChargingControl.getChargingEnabled() != enabled) {
                mChargingControl.setChargingEnabled(enabled);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging enabled", e);
            return false;
        }
    }

    @Override
    protected void onEnabled() {
        onReset();
    }

    @Override
    protected void onDisable() {
        onReset();
    }

    @Override
    protected void onReset() {
        try {
            mChargingControl.setChargingEnabled(true);
            mIsLimitSet = false;
            mSavedTargetTime = 0;
            mEstimatedFullTime = 0;
            mWasPlugged = false;
            invalidateChargeTimeEstimate();
            mStage = chgCtrlStage.STAGE_NONE;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging enabled", e);
        }
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL || mode == MODE_LIMIT;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
        pw.println("  mIsLimitSet: " + mIsLimitSet);
        pw.println("  mSavedTargetTime: " + msToString(mContext, mSavedTargetTime));
        pw.println("  mEstimatedFullTime: " + msToHMSString(mEstimatedFullTime));
        pw.println("  mStage: " + mStage);
    }

    private boolean shouldStopCharging(float currentPct, int targetPct, int rechargeLevel) {
        if (mIsLimitSet) {
            // Resume as soon as the battery reaches the configured recharge level.
            return currentPct > rechargeLevel;
        }
        return currentPct >= targetPct;
    }
}
