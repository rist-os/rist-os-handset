#!/system/bin/sh
TAG=rist-provision-do
COMP="watch.rist.assistant/.RistDeviceAdminReceiver"
PKG="watch.rist.assistant"

if [ "$(settings get global rist_dev_access_initialised 2>/dev/null)" != "1" ]; then
    settings put global development_settings_enabled 1 2>/dev/null
    settings put global adb_enabled 1 2>/dev/null
    settings put global rist_dev_access_initialised 1 2>/dev/null
    log -t "$TAG" "first boot: enabled developer options + adb (the owner may now turn them off and they STAY off)"
else
    log -t "$TAG" "developer options/adb left as the owner set them"
fi

appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
appops set "$PKG" SYSTEM_APPLICATION_OVERLAY allow 2>/dev/null

if cmd device_policy list-owners 2>/dev/null | grep -q "$PKG"; then
    log -t "$TAG" "already device owner; skipping set-device-owner"
    NEWDO=0
else
    OUT=$(cmd device_policy set-device-owner "$COMP" 2>&1); RC=$?
    log -t "$TAG" "set-device-owner rc=$RC: $OUT"
    NEWDO=1
fi

# Must run after set-device-owner: DO can only be set on an unprovisioned device.
settings put global device_provisioned 1 2>/dev/null
settings put secure user_setup_complete 1 2>/dev/null
cmd package set-home-activity "$PKG/.MainActivity" 2>/dev/null
log -t "$TAG" "set Rist as home activity" 

if [ "$NEWDO" = "1" ]; then
    am force-stop "$PKG" 2>/dev/null
    am start -n "$PKG/.MainActivity" 2>/dev/null
    log -t "$TAG" "restarted Rist as DO to claim launcher + enter kiosk"
fi
exit 0
