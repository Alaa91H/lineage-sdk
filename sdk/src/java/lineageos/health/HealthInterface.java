/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package lineageos.health;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import lineageos.app.LineageContextConstants;

public class HealthInterface {
    /**
     * No config set. This value is invalid and does not have any effects
     */
    public static final int MODE_NONE = 0;

    /**
     * Automatic config
     */
    public static final int MODE_AUTO = 1;

    /**
     * Manual config mode
     */
    public static final int MODE_MANUAL = 2;

    /**
     * Limit config mode
     */
    public static final int MODE_LIMIT = 3;

    private static final String TAG = "HealthInterface";
    private static IHealthInterface sService;
    private static HealthInterface sInstance;
    private Context mContext;

    private HealthInterface(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();

        if (context.getPackageManager().hasSystemFeature(
                LineageContextConstants.Features.HEALTH) && sService == null) {
            throw new RuntimeException("Unable to get HealthInterfaceService. The service" +
                    " either crashed, was not started, or the interface has been called too early" +
                    " in SystemServer init");
        }
    }

    /**
     * Get or create an instance of the {@link lineageos.health.HealthInterface}
     *
     * @param context Used to get the service
     * @return {@link HealthInterface}
     */
    public static synchronized HealthInterface getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HealthInterface(context);
        }

        return sInstance;
    }

    /** @hide **/
    public static synchronized IHealthInterface getService() {
        if (sService != null && sService.asBinder().isBinderAlive()) {
            return sService;
        }

        IBinder binder = ServiceManager.getService(
                LineageContextConstants.LINEAGE_HEALTH_INTERFACE);
        sService = IHealthInterface.Stub.asInterface(binder);

        if (sService == null) {
            Log.e(TAG, "Health interface service is unavailable");
        }

        return sService;
    }

    /**
     * @return true if service is valid
     */
    private static synchronized void clearService(IHealthInterface service) {
        if (sService == service) {
            sService = null;
        }
    }

    /**
     * Returns whether charging control is supported
     *
     * @return true if charging control is supported
     */
    public boolean isChargingControlSupported() {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isChargingControlSupported();
        } catch (RemoteException e) {
            clearService(service);
            Log.e(TAG, e.getLocalizedMessage(), e);
            return false;
        }
    }

    /**
     * Returns whether charging control is supported
     *
     * @return true if charging control is supported
     */
    public static boolean isChargingControlSupported(Context context) {
        try {
            return getInstance(context).isChargingControlSupported();
        } catch (RuntimeException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }

        return false;
    }

    /**
     * Returns the charging control enabled status
     *
     * @return whether charging control has been enabled
     */
    public boolean getEnabled() {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.getChargingControlEnabled();
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Set charging control enable status
     *
     * @param enabled whether charging control should be enabled
     * @return true if the enabled status was successfully set
     */
    public boolean setEnabled(boolean enabled) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setChargingControlEnabled(enabled);
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Returns the current charging control mode
     *
     * @return id of the charging control mode
     */
    public int getMode() {
        IHealthInterface service = getService();
        if (service == null) {
            return MODE_NONE;
        }
        try {
            return service.getChargingControlMode();
        } catch (RemoteException e) {
            clearService(service);
            return MODE_NONE;
        }
    }

    /**
     * Selects the new charging control mode
     *
     * @param mode the new charging control mode
     * @return true if the mode was successfully set
     */
    public boolean setMode(int mode) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setChargingControlMode(mode);
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Gets the charging control start time
     *
     * @return the seconds of the day of the start time
     */
    public int getStartTime() {
        IHealthInterface service = getService();
        if (service == null) {
            return 0;
        }
        try {
            return service.getChargingControlStartTime();
        } catch (RemoteException e) {
            clearService(service);
            return 0;
        }
    }

    /**
     * Sets the charging control start time
     *
     * @param time the seconds of the day of the start time
     * @return true if the start time was successfully set
     */
    public boolean setStartTime(int time) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setChargingControlStartTime(time);
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Gets the charging control target time
     *
     * @return the seconds of the day of the target time
     */
    public int getTargetTime() {
        IHealthInterface service = getService();
        if (service == null) {
            return 0;
        }
        try {
            return service.getChargingControlTargetTime();
        } catch (RemoteException e) {
            clearService(service);
            return 0;
        }
    }

    /**
     * Sets the charging control target time
     *
     * @param time the seconds of the day of the target time
     * @return true if the target time was successfully set
     */
    public boolean setTargetTime(int time) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setChargingControlTargetTime(time);
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Gets the charging control limit
     *
     * @return the charging control limit
     */
    public int getLimit() {
        IHealthInterface service = getService();
        if (service == null) {
            return 100;
        }
        try {
            return service.getChargingControlLimit();
        } catch (RemoteException e) {
            clearService(service);
            return 0;
        }
    }

    /**
     * Sets the charging control limit
     *
     * @param limit the charging control limit
     * @return true if the limit was successfully set
     */
    public boolean setLimit(int limit) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setChargingControlLimit(limit);
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Resets the charging control setting to default
     *
     * @return true if the setting was successfully reset
     */
    public boolean reset() {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.resetChargingControl();
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Returns whether the device's battery control bypasses battery
     *
     * @return true if the charging control bypasses battery
     */
    public boolean allowFineGrainedSettings() {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.allowFineGrainedSettings();
        } catch (RemoteException e) {
            clearService(service);
            return false;
        }
    }

    /**
     * Returns whether fast charge is supported
     *
     * @return true if fast charge is supported
     */
    public boolean isFastChargeSupported() {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isFastChargeSupported();
        } catch (RemoteException e) {
            clearService(service);
            Log.e(TAG, e.getLocalizedMessage(), e);
            return false;
        }
    }

    /**
     * Gets supported fast charge mode
     *
     * @return true supported fast charge modes
     */
    public int[] getSupportedFastChargeModes() {
        IHealthInterface service = getService();
        if (service == null) {
            return new int[0];
        }
        try {
            return service.getSupportedFastChargeModes();
        } catch (RemoteException e) {
            clearService(service);
            Log.e(TAG, e.getLocalizedMessage(), e);
            return new int[0];
        }
    }

    /**
     * Gets current fast charge mode
     *
     * @return true current fast charge mode
     */
    public int getFastChargeMode() {
        IHealthInterface service = getService();
        if (service == null) {
            return 0;
        }
        try {
            return service.getFastChargeMode();
        } catch (RemoteException e) {
            clearService(service);
            Log.e(TAG, e.getLocalizedMessage(), e);
            return 0;
        }
    }

    /**
     * Sets selected fast charge mode
     *
     * @param mode the fast charge mode
     * @return true if fast charge was set
     */
    public boolean setFastChargeMode(int mode) {
        IHealthInterface service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.setFastChargeMode(mode);
        } catch (RemoteException e) {
            clearService(service);
            Log.e(TAG, e.getLocalizedMessage(), e);
            return false;
        }
    }
}
