#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ChargingControlController.java"
SETTINGS = ROOT / "sdk/src/java/lineageos/providers/LineageSettings.java"
LIMIT = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ccprovider/Limit.java"
TOGGLE = ROOT / "lineage/lib/main/java/org/lineageos/platform/internal/health/ccprovider/Toggle.java"

def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")

def require(text: str, needle: str, where: str) -> None:
    if needle not in text:
        raise SystemExit(f"{where}: missing invariant: {needle}")

def method_body(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"missing method: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"missing opening brace: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    raise SystemExit(f"unterminated method: {signature}")

controller = read(CONTROLLER)
settings = read(SETTINGS)
limit = read(LIMIT)
toggle = read(TOGGLE)

setting_uris = [
    "MODE_URI",
    "LIMIT_URI",
    "ENABLED_URI",
    "START_TIME_URI",
    "TARGET_TIME_URI",
    "RECHARGE_LEVEL_URI",
    "LIMIT_SCHEDULE_ENABLED_URI",
    "LIMIT_START_TIME_URI",
    "LIMIT_END_TIME_URI",
]
registration = method_body(controller, "public void onStart()")
for uri in setting_uris:
    require(registration, uri, "ChargingControlController.onStart")

require(controller, "private static final class ChargingConfig", "ChargingControlController")
require(controller, "mIsPhysicallyPlugged", "ChargingControlController")
require(controller, "|| !mIsPhysicallyPlugged", "Limit schedule alarm guard")
require(controller, "mCancelOnceDisconnectReceiver", "cancel-once lifecycle")
require(controller, "mLimitScheduleDirty", "schedule dirty-state")
require(controller, "mWithinLimitSchedule", "schedule dirty-state")
require(controller, "if (mLimitScheduleDirty)", "schedule dirty-state")
require(controller, "wasPhysicallyPlugged != mIsPhysicallyPlugged", "schedule dirty-state")
require(controller, "private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);",
        "hot-path logging")
require(controller, "switchProviderForMode", "provider switching lifecycle")
require(controller, "mCurrentProvider.disable();", "provider switching cleanup")
require(controller, "public void onDestroy()", "ChargingControlController")
require(controller, "mAlarmManager.cancel(mLimitScheduleAlarmListener)", "ChargingControlController.onDestroy")

hot_path = method_body(controller, "protected void updateChargeControl()")
for forbidden in (
    "LineageSettings.",
    "getMode()",
    "getLimit()",
    "isEnabled()",
    "getInt(",
    "getBoolean(",
    "isWithinLimitSchedule(config)",
):
    if forbidden in hot_path:
        raise SystemExit(
            "updateChargeControl must use cached ChargingConfig; "
            f"found direct settings access: {forbidden}"
        )

require(settings,
        "private static final Validator sSecondsFromMidnightValidator =\n"
        "            new InclusiveIntegerRangeValidator(0, 86399);",
        "LineageSettings")
require(settings,
        "CHARGING_CONTROL_RECHARGE_LEVEL_VALIDATOR =\n                new InclusiveIntegerRangeValidator(20, 99);",
        "LineageSettings")
require(settings,
        "CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED_VALIDATOR =\n                sBooleanValidator;",
        "LineageSettings")
require(settings,
        "CHARGING_CONTROL_LIMIT_START_TIME_VALIDATOR =\n                sSecondsFromMidnightValidator;",
        "LineageSettings")
require(settings,
        "CHARGING_CONTROL_LIMIT_END_TIME_VALIDATOR =\n                sSecondsFromMidnightValidator;",
        "LineageSettings")

for key in (
    "CHARGING_CONTROL_RECHARGE_LEVEL",
    "CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED",
    "CHARGING_CONTROL_LIMIT_START_TIME",
    "CHARGING_CONTROL_LIMIT_END_TIME",
):
    require(settings, f"VALIDATORS.put({key}", "LineageSettings validator registration")

require(limit, "final int minPct = targetPct == 100 ? 0 : rechargeLevel;", "Limit provider")
require(toggle, "return currentPct > rechargeLevel;", "Toggle provider recharge hysteresis")

print("Charging Control validation passed")
