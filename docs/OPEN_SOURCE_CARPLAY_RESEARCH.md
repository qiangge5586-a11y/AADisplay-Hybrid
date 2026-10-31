# Open-source CarPlay research for AADisplay-Hybrid

**Research date:** 2026-10-31  
**Target:** OnePlus PLC110 / Android 16 as a phone-side USB CarPlay source for the 2019 Honda Civic factory head unit.

## Executive conclusion

No reviewed repository provides a drop-in, ordinary Android phone APK that turns an Android phone into an iPhone-compatible wired CarPlay source for an existing factory head unit.

| Project | Actual role | Useful material | Why it is not a drop-in sender |
|---|---|---|---|
| [OpenAutoLink](https://github.com/mossyhub/openautolink) | Android Automotive OS head unit receiving phones; USB CarPlay receiver is implemented | Android USB Host transport, USBMUX/Lockdown/CarKit, iAP2 authentication, NCM bridge, receiver integration and diagnostics | The Android device is the car/head unit. The phone connected by USB is the iPhone. It does not make an ordinary Android phone impersonate an iPhone. |
| [DiPlay-5.1](https://github.com/Jheenz/DiPlay-5.1) | Android head-unit receiver, with separate BYD release and Geely E01 research | Configuration-scoped USB descriptors, USBMUX/Lockdown transport, NCM, careful staged diagnostics and compatibility gates | Its IphoneUsbHost opens an attached iPhone as a USB peripheral. Its own compatibility notes state that the E01 work has not established a complete CarPlay session and keeps production connections disabled. |
| [carplayd](https://github.com/lvalen91/carplayd) | Raspberry Pi wired CarPlay head unit / accessory | Linux USB role-switch and configfs/FunctionFS gadget architecture; iAP2 + NCM accessory implementation | The Pi is the CarPlay receiver. It presents a gadget to an iPhone. It depends on Pi-specific dwc2 role-switch support, kernel configuration, FunctionFS and an external MFi signing oracle. |
| [OpenAuto](https://github.com/JanRi3D/OpenAuto) | Linux head-unit/reference implementation | Protocol and receiver-side architecture references | Receiver-side research, not an Android phone sender implementation. |

## Important source-level findings

### OpenAutoLink

The project README explicitly describes installation on an Android Automotive OS vehicle head unit and lists USB CarPlay as a supported receiver mode. Its CarPlay USB research notes describe a USB-host pipeline using USBMUX, Lockdown, CarKit/iAP2 authentication and USB NCM Ethernet frames. NcmUsbBridge claims USB NCM interfaces and bridges frames into the receiver's existing TCP/UDP networking through an app-only TUN.

This is useful receiver-side reference code, but its USB direction is the reverse of this project's target. Its CarPlay build also requires a compatible authentication pair that the developer is authorized to use; public source/build availability does not provide that identity.

References:
- https://github.com/mossyhub/openautolink
- https://github.com/mossyhub/openautolink/blob/main/docs/carplay-usb-gm-network-research.md
- https://github.com/mossyhub/openautolink/blob/main/carplay-lab/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt
- https://github.com/mossyhub/openautolink/blob/main/docs/carplay-private-mfi-assets.md

### DiPlay-5.1

IphoneUsbHost and NcmUsbBridge are Android USB-host components. The project is particularly useful for understanding configuration-scoped descriptors and why flattened interface lists can be ambiguous. Its published compatibility status explicitly separates the BYD receiver release from Geely E01 research and says a complete CarPlay session is not confirmed for that research target.

Do not copy its vendor-specific re-enumeration steps into this phone-side project. They are receiver-side operations against an attached iPhone and may change the attached device's USB configuration.

References:
- https://github.com/Jheenz/DiPlay-5.1
- https://github.com/Jheenz/DiPlay-5.1/blob/main/docs/COMPATIBILITY.md
- https://github.com/Jheenz/DiPlay-5.1/blob/main/shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt
- https://github.com/Jheenz/DiPlay-5.1/blob/main/shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt

### carplayd

The documented Pi implementation is a genuine wired CarPlay head unit. Its difficult step is a hardware/driver-specific USB host-to-device role transition on Raspberry Pi dwc2, followed by a configfs composite gadget with FunctionFS iAP2 and USB NCM. It uses a separate MFi signing oracle. The source documents Pi device-tree overlay, role-switch sysfs and gadget setup; these are not generic Android APIs.

This is the strongest reference for the shape of a CarPlay accessory gadget, but it cannot be transplanted into an APK. A compatible USB controller, kernel gadget functions, role-switch driver and authorized authentication path are prerequisites.

References:
- https://github.com/lvalen91/carplayd
- https://github.com/lvalen91/carplayd/blob/main/docs/02_USB_FLIP_SOLVED.md
- https://github.com/lvalen91/carplayd/blob/main/docs/PI_WIRED_CARPLAY_BREAKTHROUGH.md

## Consequences for AADisplay-Hybrid

1. Keep the existing Android Auto virtual-display feature separate. It does not implement CarPlay accessory behavior.
2. Do not port receiver-side UsbDeviceConnection code as if it were the phone-side USB gadget. USB Host and USB Device/Gadget are opposite roles.
3. The Android app can safely inspect public USB framework metadata and read-only system properties, but public UsbManager does not expose a general API to define arbitrary USB device descriptors or bind kernel gadget functions.
4. Shizuku or LSPosed would not, by themselves, supply missing kernel gadget functions, controller role switching, or MFi authorization. This project remains no-root and does not add Shizuku.
5. Do not extract, reuse, generate, or bypass another accessory's private MFi credentials. Only use an identity and signing provider the developer is authorized to use.
6. The Honda's CarPlay menu alone does not prove that its USB port is configured for a compatible wired CarPlay session.

## Current device evidence and next test

The PLC110 diagnostic report showed Android 16/API 36, USB Host and Accessory feature declarations, no currently enumerated USB Host peripheral, and no ordinary-app visibility of /config/usb_gadget or /sys/class/udc contents. The report was captured without proof that the phone was connected to the Honda USB port. These observations do not prove that the phone's controller lacks gadget capability; they only show that the app cannot currently inspect the relevant nodes.

Next, extend the diagnostic APK with read-only observations:
- getprop allowlist: sys.usb.config, sys.usb.state, persist.sys.usb.config, sys.usb.controller, ro.bootmode, ro.hardware, vendor.usb.config, vendor.usb.state.
- Read-only existence/access checks for /sys/class/usb_role, /sys/class/typec, and /sys/bus/usb/devices.
- Where readable, report only safe role fields such as data_role, power_role, and port_type.
- Never write properties, sysfs/configfs nodes, switch USB roles, invoke su, or request privileged services.

Then collect one report with the phone connected to the Honda's data USB port and a second baseline report disconnected. Separately confirm that a genuine iPhone can start wired CarPlay on that exact Honda port. If the phone has no accessible gadget/role-switch path, stop APK-only gadget implementation and investigate an OEM-supported system/kernel route instead.

## Go / no-go gates

- **Gate A — vehicle:** genuine iPhone launches wired CarPlay on the Honda.
- **Gate B — phone hardware/OS:** PLC110 exposes a supported USB device/gadget role and the required gadget functions through an OEM-supported route.
- **Gate C — authorization:** a lawful, compatible CarPlay accessory authentication route is available.
- **Gate D — protocol:** a phone-side accessory/source implementation interoperates with the Honda head unit.

Passing a build or a read-only diagnostic is not a CarPlay pass. Do not claim a working sender APK until all four gates have been demonstrated on real hardware.
