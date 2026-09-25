/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health.ccprovider;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;

import android.content.Context;
import android.util.Log;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.ChargingLimitInfo;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class Limit extends ChargingControlProvider {
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int UNKNOWN_LIMIT = -1;

    private int mAppliedMin = UNKNOWN_LIMIT;
    private int mAppliedMax = UNKNOWN_LIMIT;

    public Limit(IChargingControl chargingControl, Context context) {
        super(context, chargingControl);
    }

    @Override
    protected boolean onBatteryChanged(float currentPct, int targetPct, int rechargeLevel) {
        if (DEBUG) {
            Log.d(TAG, "Current battery level: " + currentPct + ", target: " + targetPct
                    + ", recharge level: " + rechargeLevel);
        }
        return setChargingLimit(targetPct, rechargeLevel);
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
        // The HAL may have changed while this provider was disabled. Force one synchronization
        // on every reset, then let the applied-state cache suppress identical battery updates.
        mAppliedMin = UNKNOWN_LIMIT;
        mAppliedMax = UNKNOWN_LIMIT;
        setChargingLimit(100, 0);
    }

    private boolean setChargingLimit(int targetPct, int rechargeLevel) {
        final int minPct = targetPct == 100 ? 0 : rechargeLevel;
        if (mAppliedMax == targetPct && mAppliedMin == minPct) {
            return true;
        }

        try {
            final ChargingLimitInfo limit = new ChargingLimitInfo();
            limit.min = minPct;
            limit.max = targetPct;
            mChargingControl.setChargingLimit(limit);
            mAppliedMin = minPct;
            mAppliedMax = targetPct;
            return true;
        } catch (Exception e) {
            // The HAL state is unknown after an exception. Invalidate the cache so the next
            // battery update retries instead of assuming the requested limit was applied.
            mAppliedMin = UNKNOWN_LIMIT;
            mAppliedMax = UNKNOWN_LIMIT;
            Log.e(TAG, "Failed to set charging limit", e);
            return false;
        }
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.LIMIT);
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return !isHALModeSupported(ChargingControlSupportedMode.BYPASS);
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL || mode == MODE_LIMIT;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
        pw.println("  mAppliedMin: " + mAppliedMin);
        pw.println("  mAppliedMax: " + mAppliedMax);
    }
}
