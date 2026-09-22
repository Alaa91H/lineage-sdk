#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOGGLE = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ccprovider/Toggle.java"
LIMIT = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ccprovider/Limit.java"
PROVIDER = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ccprovider/ChargingControlProvider.java"

toggle = TOGGLE.read_text(encoding="utf-8")
limit = LIMIT.read_text(encoding="utf-8")
provider = PROVIDER.read_text(encoding="utf-8")

required_toggle = (
    "ESTIMATE_REFRESH_INTERVAL_MS = 60_000L",
    "mBatteryStatsManager = mContext.getSystemService(BatteryStatsManager.class)",
    "batteryStatus != null",
    "getEstimatedChargeTimeRemaining()",
    "invalidateChargeTimeEstimate()",
    "mLastEstimateQueryElapsed",
    "now - mLastEstimateQueryElapsed < ESTIMATE_REFRESH_INTERVAL_MS",
    "if (prevStage != mStage)",
)
for token in required_toggle:
    if token not in toggle:
        raise SystemExit(f"Toggle invariant missing: {token}")

if "Objects.requireNonNull" in toggle:
    raise SystemExit("Toggle must not crash when BatteryStatsManager is unavailable")

estimate_start = toggle.find("private long getEstimatedChargeTimeRemaining()")
estimate_end = toggle.find("private void invalidateChargeTimeEstimate()", estimate_start)
estimate = toggle[estimate_start:estimate_end]
if estimate_start < 0 or estimate_end < 0:
    raise SystemExit("Unable to isolate charge-time estimate helper")
if estimate.count("getBatteryUsageStats()") != 1:
    raise SystemExit("Charge-time estimate helper must perform one BatteryUsageStats query")

stage_start = toggle.find("private chgCtrlStage getNextStage")
stage_end = toggle.find("private long getEstimatedChargeTimeRemaining()", stage_start)
stage = toggle[stage_start:stage_end]
if stage.find("batteryPct < CHARGE_CTRL_MIN_LEVEL") > stage.find("getEstimatedChargeTimeRemaining()"):
    raise SystemExit("BatteryUsageStats must not be queried below the minimum charge level")

if 'private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);' not in limit:
    raise SystemExit("Limit hot-path diagnostics must remain DEBUG-gated")

support_start = provider.find("public final boolean isHALModeSupported(int mode)")
support_end = provider.find("\n    }", support_start)
support = provider[support_start:support_end]
if support.count("mChargingControl.getSupportedMode()") != 1:
    raise SystemExit("HAL supported mode must be queried once per capability check")
if 'Log.d(TAG, "isSupported mode called' not in provider:
    raise SystemExit("HAL capability diagnostics must remain DEBUG-level")

print("Charging Control provider hot-path validation passed")
