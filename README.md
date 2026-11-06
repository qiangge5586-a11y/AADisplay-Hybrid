# AADisplay Hybrid

Android display experimentation workspace. The original Android Auto virtual-display work remains a separate legacy component. The active direction is a **wireless CarPlay sender for OnePlus Ace 5 / Android 16**, intended to connect to DiPlay installed on the vehicle head unit. USB transport is out of scope. The phone must not require root or Shizuku; root, if used at all, is limited to optional receiver-side integration on the head unit.

## Project status

**Wireless sender: research and architecture phase; no working sender APK is available yet.**

The existing Android Auto virtual-display code is not a CarPlay sender. The selected architecture separates the Android phone sender (iAP2 + Wi-Fi + mDNS + AirPlay) from the DiPlay receiver installed on the head unit. The current public CarDeck sender is a useful protocol reference and has reported DiPlay test-receiver interoperability, but its current implementation requires Shizuku. The requested implementation must avoid both root and Shizuku, so it needs a separate public-API display/input path and explicit compatibility tests.

See [Wireless CarPlay Sender Plan](docs/WIRELESS-CARPLAY-SENDER-PLAN.md) for protocol architecture, no-Shizuku constraints and milestone gates. See [Rooted Head-Unit DiPlay Integration Assessment](docs/ROOTED-HEADUNIT-DIPLAY-INTEGRATION.md) for source-level research on rooted receiver projects, Honda safety gates and compatibility limits. The earlier [USB feasibility note](docs/USB-CARPLAY-USB-FEASIBILITY.md) is retained as historical research only.

## Legacy Android Auto integration

The existing integration includes:

- Custom Android Auto virtual render resolution (width × height), with 0 × 0 meaning automatic head-unit-negotiated size.
- Resolution settings UI, Binder API, persistence, live apply, and reconnect re-apply.
- Head-unit negotiated profile kept separate from the render-buffer override.
- Split virtual-display resize rollback protection.
- LSPosed hooks for upstream system_server / Android Auto integration.
- No Shizuku dependency.

These capabilities apply to the legacy Android Auto path only. They do not provide USB gadget control, iAP2, CarPlay authentication, or a wired CarPlay session.

## Wireless CarPlay safety boundaries

- Phone sender: no root, no Shizuku, no USB. Use public Android APIs and explicit user consent for capture, audio and accessibility features.
- Receiver-side root is optional and must be limited to reversible, model-specific integration after firmware identification and backup verification.
- Do not modify USB gadget/sysfs/configfs, Bluetooth identity, or system network settings through privilege escalation.
- Do not forge, extract, reuse or bypass Apple MFi authentication credentials; do not claim Apple certification.
- Preserve OEM MCU/CAN, reverse-camera/AVM, DSP/audio, thermal and factory projection services until a replacement and rollback are physically validated.
- Preserve upstream GPL-3.0 license and notices in redistributed legacy builds.

## Target validation

- Phone: OnePlus Ace 5 / PLC110, Android 16 / ColorOS 16 (user-reported)
- Vehicle: 2019 Honda Civic factory head unit
- Desired link: wireless Wi-Fi + Bluetooth; no USB cable
- Receiver: DiPlay installed on the head unit; actual Honda compatibility is not yet verified
- Phone sender: no working no-Shizuku CarPlay APK is available yet
- Head-unit Android version, board/chipset, firmware and root method: not yet recorded

## Build verification

GitHub Actions currently verifies the legacy Android Auto integration build chain and historical USB probe. A successful legacy build or USB probe does not establish wireless CarPlay compatibility. No wireless sender APK is available at this stage.

## Historical USB diagnostic APK

The separate read-only diagnostic app under [usb-probe](usb-probe/) is retained for historical research only. It is not part of the current wireless target, is not a CarPlay sender and should not be used as evidence of wireless compatibility.

## License

See the upstream GPL-3.0 license and preserve all source notices.

## Honda Civic DiAuto adaptation

The isolated Android Auto receiver compatibility workstream for the 2019 Honda Civic factory head unit (Android 4.2.2 / API 17 / ARMv7) lives in [DiAuto-Civic](DiAuto-Civic/README.md). It is separate from the legacy virtual-display integration and the wireless CarPlay sender. The directory currently contains the scope and compatibility plan; no API-17 port or vehicle-tested APK is claimed yet.
