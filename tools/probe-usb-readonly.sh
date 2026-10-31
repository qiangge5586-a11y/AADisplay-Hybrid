#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo 'ERROR: adb is not installed or not on PATH.' >&2
  exit 1
fi
if ! adb get-state >/dev/null 2>&1; then
  echo 'ERROR: no authorized Android device. Enable USB debugging and authorize this computer.' >&2
  exit 1
fi

echo '=== Device identity (non-unique fields only) ==='
adb shell '
  echo "manufacturer=$(getprop ro.product.manufacturer)"
  echo "model=$(getprop ro.product.model)"
  echo "device=$(getprop ro.product.device)"
  echo "android_release=$(getprop ro.build.version.release)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "fingerprint=$(getprop ro.build.fingerprint)"
  echo "kernel=$(uname -r)"
  echo "usb_config=$(getprop sys.usb.config)"
  echo "usb_state=$(getprop sys.usb.state)"
'

echo
echo '=== USB service (filtered; serials omitted) ==='
adb shell 'dumpsys usb 2>/dev/null | grep -Ei "connected|configured|host|device|data.?role|power.?role|port|function|current mode|usb speed" | head -n 100 || true'

echo
echo '=== Read-only gadget path accessibility ==='
adb shell '
  for p in /config/usb_gadget /config/usb_gadget/g1 /sys/class/android_usb /sys/class/android_usb/android0 /sys/class/udc; do
    if [ -e "$p" ]; then
      [ -r "$p" ] && r=yes || r=no
      [ -w "$p" ] && w=yes || w=no
      [ -x "$p" ] && x=yes || x=no
      echo "$p exists=yes readable=$r writable=$w searchable=$x"
    else
      echo "$p exists=no (or hidden by permissions)"
    fi
  done
  echo "configfs_mount=$(grep -E "[[:space:]]/config[[:space:]]" /proc/mounts | head -n 1 || true)"
'

echo
echo '=== USB-related public properties (filtered) ==='
adb shell 'getprop | grep -Ei "usb|typec|gadget" | grep -vEi "serial|secret|token" | head -n 100 || true'

echo
echo 'Probe complete. No USB mode, gadget configuration, or device descriptor was changed.'