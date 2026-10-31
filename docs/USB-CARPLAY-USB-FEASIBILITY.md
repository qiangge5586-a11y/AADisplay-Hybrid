# Wired CarPlay Sender Feasibility — Phase 0

**Status: investigation only; no working Android-to-wired-CarPlay sender is implemented.**

## Target
- Phone: OnePlus Ace 5, ColorOS 16 (user-reported; not yet probed)
- Vehicle: 2019 Honda Civic factory head unit
- Link: direct USB cable; no wireless CarPlay adapter
- Constraint: no root; no Shizuku. LSPosed is not assumed to grant USB gadget privileges.

## Why this is a separate transport project
The existing AADisplay Hybrid work customizes an Android Auto virtual display. That does not make the phone an Apple CarPlay USB sender. Wired CarPlay involves USB role negotiation, iAP2 transport/session setup, and CarPlay media/control streams. A virtual display resolution setting cannot supply those protocols.

Apple's public MFi material lists CarPlay and iAP2 among licensed technologies and describes authentication coprocessors as MFi components. This project will not emulate, extract, forge, or bypass Apple authentication credentials. Any implementation requiring licensed components or specifications must use an authorized route.

## Phase 0 — non-invasive device probe
Build a standalone diagnostic screen (or a separately installable diagnostic flavor) that reports only information available to an ordinary Android app:
1. Android build fingerprint, manufacturer/model, API level, kernel release.
2. USB host feature and current UsbManager device/accessory enumeration.
3. USB connection/charging state and public USB data-role/power-role information where the OS exposes it.
4. Read-only accessibility checks for /config/usb_gadget, /sys/class/android_usb, and relevant gadget state nodes. Record exists, readable, and writable separately; inaccessible must be reported as unknown/inaccessible, never as absent.
5. Enumerate public USB functions/modes only where exposed by supported Android APIs. Do not infer arbitrary gadget configurability from MTP, PTP, MIDI, tethering, or ADB being present.
6. Export a redacted diagnostic report. Do not collect serial numbers, account data, or unrelated personal information.
The app must not write to sysfs/configfs, change USB mode, alter device descriptors, or run privileged shell commands. It must not request root or Shizuku.

## Go / no-go criteria
**GO to a hardware/protocol prototype only if** the phone exposes a supported, documented way to select device-side USB roles and configure the required gadget interfaces, or an authorized external controller/bridge is available.
**NO-GO for a pure APK on stock ColorOS** if required gadget nodes are inaccessible and the OEM USB Gadget HAL does not expose the needed configuration. A normal app permission, Shizuku, or LSPosed hook does not itself grant kernel gadget control.
Even after USB gadget control is available, the project still needs a lawful iAP2/CarPlay implementation path and compatible authentication. A successful USB descriptor test is not proof of CarPlay compatibility.

## Physical validation sequence
1. Confirm the vehicle's wired CarPlay port and test it with a known-good iPhone/cable.
2. Capture the phone's ordinary USB capability report while connected to a PC and while connected to the vehicle (no privileged capture).
3. Determine whether the phone is USB host or device in each topology; verify whether the vehicle enumerates it.
4. Only then evaluate protocol/session negotiation. Do not claim success based solely on a USB connection notification.
5. Validate display resolution, touch mapping, audio routing, call audio, reconnect, thermal behavior, and frame pacing on the actual vehicle.

## Current known blockers / unknowns
- Stock ColorOS USB gadget configurability on this exact OnePlus Ace 5 is **unknown** until probed.
- The user's head unit has not yet been verified with a known-good iPhone; the CarPlay menu icon alone is not sufficient proof of a working wired port.
- No CarPlay USB sender, iAP2 session, authentication, or real-vehicle test exists in this repository at this stage.

## References
- Apple MFi program technologies and authentication components: https://mfi.apple.com/en/how-it-works.html
- Apple MFi eligibility; iAP2 over USB and vehicles supporting CarPlay over USB are in scope: https://mfi.apple.com/en/who-should-join
- Android USB HAL architecture: https://source.android.com/docs/core/permissions/usb-hal
- Linux USB gadget ConfigFS model: https://docs.kernel.org/usb/gadget_configfs.html
## Running the first probe

A read-only ADB helper is provided at `tools/probe-usb-readonly.sh`. On a computer with Android Platform Tools installed, connect the phone, enable USB debugging, authorize the computer, then run:

```sh
bash tools/probe-usb-readonly.sh
```

The script reports public build/USB properties and read-only path access checks. It does not change USB mode or write to gadget nodes. Share its output for analysis after reviewing it for any device-specific details you do not want to disclose.
