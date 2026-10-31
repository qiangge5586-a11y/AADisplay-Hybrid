# Wireless CarPlay Sender + DiPlay Receiver Plan

**Target:** OnePlus Ace 5 / Android 16 sender -> DiPlay receiver installed on the vehicle head unit, over Wi-Fi. USB, root and Shizuku are out of scope.

## Architecture

- Phone sender: Bluetooth RFCOMM/iAP2 bootstrap, joins the receiver-provided Wi-Fi network, discovers the AirPlay endpoint with mDNS, negotiates the session, and sends video/audio while receiving touch events.
- Head-unit receiver: DiPlay/xcertplay receiver stack. DiPlay is not the phone sender.
- A successful APK installation on the head unit is only an install/launch result. It does not prove the receiver's Bluetooth, Wi-Fi, iAP2, AirPlay, audio or touch path is compatible with this Honda unit.

## Evidence from public projects

- CarDeck (MIT): https://github.com/shaonialife/cardeck
  - Correct sender role: Android phone acting as the CarPlay sender.
  - Its public documentation reports wireless iAP2 -> receiver hotspot -> mDNS -> AirPlay, video and touch-back validation with a DiPlay test receiver and an Audi MIB3.
  - Current source still depends on Shizuku for trusted virtual displays, touch injection, window/display state, and some device identity / system operations. The current README requires Shizuku. It is not a drop-in solution for the no-Shizuku requirement.
- DiPlay-5.1: https://github.com/Jheenz/DiPlay-5.1
  - Receiver role: Android head unit receiving an iPhone-like sender.
  - Its published release scope is compatible BYD Android head units; other brands are explicitly unsupported. Android 9+ is the stated APK baseline and the wireless Wi-Fi Direct path needs Android 10+ in the related compatibility documentation.
  - The project documents a separate Geely research effort, not a supported release. Do not infer Honda compatibility from APK installation alone.
- CarDeck is MIT. DiPlay/xcertplay code is GPL-3.0 (with separately noted AGPL-3.0 UI material in DiPlay). This repository will not copy DiPlay source files unless the project is deliberately relicensed and all notices/obligations are handled.

## No-Shizuku implementation strategy

Do not claim full parity with CarDeck until each capability is independently implemented and tested.

1. **Sender protocol core:** independently implement/test Bluetooth RFCOMM, iAP2 link/session state, receiver Wi-Fi handoff, mDNS discovery, AirPlay pairing/control, H.264 streaming and audio transport. Keep protocol code isolated from display/input privilege code.
2. **Public-API display mode:** start with a foreground, user-visible sender Activity and MediaProjection consent for screen capture. No hidden/trusted virtual display and no silent capture authorization.
3. **Touch return:** evaluate an explicitly user-enabled AccessibilityService for gesture dispatch. If the device/ROM or target apps prevent reliable interaction, report touch-back as unsupported rather than bypassing Android protections.
4. **Wi-Fi:** use public Android network APIs and their system confirmation UI. Do not silently run shell commands or modify system network settings.
5. **Bluetooth identity:** Android 11+ may expose a redacted Bluetooth MAC to ordinary apps. Detect this explicitly; never invent a hardware MAC. Test whether the receiver can pair/select the sender using an app-generated stable identifier or a receiver-side selection mechanism. If the target receiver requires the real MAC, record this as a blocker.
6. **Audio:** request MediaProjection / AudioPlaybackCapture permissions through their normal system flows; test app eligibility and audio focus separately from video.
7. **Compatibility gates:** validate against DiPlay test receiver first, then the actual Honda head unit. Record install, discovery, iAP2, AirPlay pairing, first frame, audio, touch, reconnect and sustained-session results as separate gates.

## First milestone

Build a **no-Shizuku wireless sender feasibility APK** that:
- checks Android/API and Bluetooth/Wi-Fi capabilities;
- performs explicit receiver discovery and network handoff diagnostics;
- exposes a user-consented MediaProjection test surface;
- records whether Bluetooth identity is redacted;
- exports a privacy-reviewed diagnostic report.

This first APK is a diagnostic milestone, not a completed CarPlay sender. Full projection is only claimed after real receiver logs and sustained playback confirm it.

## Safety and integrity

- No USB transport, root, Shizuku, hidden system API, shell privilege escalation, USB gadget/sysfs/configfs writes, or MFi credential extraction/forgery.
- Do not claim Apple/MFi certification or official compatibility.
- Configure and test while parked; keep the factory interface available as fallback.
