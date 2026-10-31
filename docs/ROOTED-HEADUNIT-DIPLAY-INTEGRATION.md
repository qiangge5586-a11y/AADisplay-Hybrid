# Rooted Head-Unit DiPlay Integration Assessment

**Status:** research only; no Honda-specific installer, Magisk module, or receiver APK has been produced.

**Project target:** 2019 Honda Civic factory head unit running Android (exact board, Android/API level, firmware and root implementation not yet recorded). The phone remains a OnePlus Ace 5 / Android 16 sender. The required link is wireless only. The phone must not require root or Shizuku.

## Findings from source inspection

### 1. DiPlay-SM-T285: the most relevant rooted receiver reference

Repository: https://github.com/windowsoffender/DiPlay-SM-T285

This fork documents a real rooted Android receiver setup on a Samsung Galaxy Tab A 2016 (SM-T285, Android 5.1.1), with Magisk and DiPlay configured as the home screen. It demonstrates receiver-side integration tasks rather than phone-side sending:

- Install a DiPlay build and optionally make it the HOME activity.
- Configure a built-in Wi-Fi hotspot for the phone to join.
- Use root-assisted scripts to set hotspot name/channel and disable selected tablet apps.
- Restore disabled packages through the paired restore path.
- Use DiPlay's own sleep/wake feature for vehicle power behavior.

Important limits:

- It is tailored to the SM-T285 and its Samsung firmware, not a generic head-unit installer.
- The fork's README says not to add Magisk boot scripts on that tablet: its kernel blocks the spawned processes and can cause a boot loop. This is device-specific, not a universal Magisk rule.
- Its debloat script disables packages rather than deleting them, but the package list is Samsung-specific. Do not run it on a Honda unit.
- Its documented DiPlay source snapshot is v0.2.15. The project notes that accessory identity/runtime assets are external to the public source tree and that the resulting preview is not Apple-certified. Do not extract, copy, or redistribute identity/private-key material.

Reusable design ideas only: reversible package disable/enable, explicit hotspot configuration, startup/sleep behavior behind a device capability check, and a receiver-first validation checklist. Do not reuse its shell scripts unchanged.

### 2. Headunit Buttons: useful root/Xposed integration pattern, but not DiPlay itself

Repository: https://github.com/BadAccuracyID/headunit-buttons

Source inspected: src/com/efran/buttons/ButtonHook.java and README.

The module is narrowly scoped to the GT6 firmware package com.szchoiceway.eventcenter. It checks for expected methods and fields before installing hooks; if the firmware shape differs, it retains stock behavior. It observes MCU/parser data already read by the OEM service rather than opening the serial device itself. Its remapping defaults off, records events in a bounded queue, and preserves stock behavior during reverse-camera/power-off states.

The README reports physical testing on GT6-CAR with a particular EventCenter firmware, Vector/Xposed and Magisk. That is not evidence of compatibility with Honda's factory head unit. The selected-CarPlay-app shortcut and receiver switcher are separate from DiPlay's wireless transport.

No reusable license file was found in the inspected repository root. Treat it as a behavioral reference only; do not copy its source into this repository until its licensing terms are clarified.

Reusable design ideas only: strict firmware fingerprinting, fail-closed hooks, remapping disabled by default, bounded diagnostics, and a hard guard for reverse-camera/power states. Do not hook Honda MCU/CAN services without exact firmware identification and a safe rollback path.

### 3. JunsunV1Plus: broad architecture notes, not a validated Honda port

Repository: https://github.com/toczke/JunsunV1Plus

Its README identifies the project as experimental work in progress for a Junsun V1 Plus / Topway TS8667 platform. Its architecture notes separate the user interface/projection layer from automotive services such as MCU/CAN, reverse camera/AVM, DSP and steering-wheel controls.

The repository's own warning describes risks from bootloader, partition, root and custom-ROM changes. Some proposed actions—removing OEM projection daemons, repacking system images and replacing system components—are plans, not proof of a safe production procedure. Its MT8667/TS8667 hardware and firmware are not the user's Honda unit.

Decision: use only the high-level principle of preserving OEM vehicle services. Do not flash its images, use its root procedures, or remove Honda packages based on this project.

## Integration decision for this project

Keep the architecture split into two independent devices:

1. **Phone sender (OnePlus Ace 5):** Android 16, no root, no Shizuku, no USB. Use public Android APIs and explicit user consent for display capture, audio capture, Wi-Fi/network changes and touch accessibility. CarDeck remains a protocol reference, not a drop-in no-Shizuku build.
2. **Vehicle receiver (Honda head unit):** DiPlay is installed here. Root may be used only for optional device-specific integration after the exact firmware and recovery route are known. The core receiver should first be tested without modifying system partitions.

A rooted head unit does not remove Android 16 restrictions on the phone. Likewise, being able to install an APK on the head unit does not prove that Wi-Fi Direct, hotspot handoff, Bluetooth/iAP2, AirPlay, audio, touch return or automatic reconnect work.

## Honda validation gates (in order)

- [ ] Record head-unit manufacturer/board, Android API level, build fingerprint, kernel, screen resolution and available Wi-Fi bands.
- [ ] Record root implementation and verify a known-good full backup/restore route. Do not expose serial numbers, IMEI, Wi-Fi passwords or ADB pairing keys in reports.
- [ ] Confirm factory reverse camera, steering-wheel keys, CAN/MCU, DSP/audio, Bluetooth hands-free and OEM CarPlay still work before testing.
- [ ] Install an unmodified, publicly released DiPlay build as a normal app. Do not replace the launcher or disable OEM packages.
- [ ] Test receiver hotspot or same-LAN mode while parked; record whether the phone discovers the receiver and whether the Wi-Fi link remains stable.
- [ ] Validate each CarPlay stage separately: Bluetooth bootstrap, Wi-Fi handoff, mDNS discovery, session/authentication, first video frame, audio both directions, touch return, reconnect and sustained session.
- [ ] Only after the above passes, consider reversible root-assisted startup or button-launch integration. Keep OEM services and a stock-launch fallback.

## Non-goals and safety boundaries

- No USB transport or USB gadget/sysfs/configfs modification.
- No root, Shizuku, silent capture or hidden-API privilege escalation on the phone.
- No MFi credential extraction, private-key reuse, authentication forgery or certification claims.
- No deletion of OEM MCU/CAN, reverse-camera/AVM, DSP, thermal or factory projection services before a verified replacement and rollback.
- No flashing or system-image replacement based on another head-unit model.

## References

- DiPlay rooted tablet fork: https://github.com/windowsoffender/DiPlay-SM-T285
- Headunit Buttons Xposed module: https://github.com/BadAccuracyID/headunit-buttons
- Junsun V1 Plus / TS8667 research: https://github.com/toczke/JunsunV1Plus
- DiPlay upstream: https://github.com/shihabal3amri/DiPlay
- CarDeck phone-side sender reference: https://github.com/shaonialife/cardeck
