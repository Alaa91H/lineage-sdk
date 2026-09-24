#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HEALTH = ROOT / "sdk/src/java/lineageos/health/HealthInterface.java"

source = HEALTH.read_text(encoding="utf-8")

required = (
    "public static synchronized IHealthInterface getService()",
    "sService != null && sService.asBinder().isBinderAlive()",
    "ServiceManager.getService(",
    "private static synchronized void clearService(IHealthInterface service)",
    "if (sService == service)",
    "sService = null;",
)
for token in required:
    if token not in source:
        raise SystemExit(f"HealthInterface recovery invariant missing: {token}")

if "checkService()" in source:
    raise SystemExit("HealthInterface must not rely on a stale cached-service null check")

local_service_count = source.count("IHealthInterface service = getService();")
if local_service_count != 17:
    raise SystemExit(
        f"Expected 17 HealthInterface API calls to use local service references, found "
        f"{local_service_count}"
    )

clear_count = source.count("clearService(service);")
if clear_count != 17:
    raise SystemExit(
        f"Expected 17 RemoteException paths to invalidate the cached service, found {clear_count}"
    )

stale_calls = re.findall(
    r"sService\.(?:isCharging|getCharging|setCharging|resetCharging|allowFine|"
    r"isFast|getSupportedFast|getFast|setFast)",
    source,
)
if stale_calls:
    raise SystemExit(
        "HealthInterface API calls must use the per-call local service reference"
    )

if "return service.getChargingControlLimit();" not in source:
    raise SystemExit("Charging limit call must use the local service reference")

print("HealthInterface binder recovery validation passed")
