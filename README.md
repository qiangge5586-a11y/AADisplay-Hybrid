# AADisplay Hybrid

Android display experimentation workspace. The repository originally integrated custom virtual-display resolution controls into an AADisplay Android Auto source tree. The active requested direction has since changed to investigating a **direct USB Android-to-wired-CarPlay sender** for a OnePlus Ace 5 / ColorOS 16 and a 2019 Honda Civic factory head unit.

## Project status

**Wired CarPlay sender: not implemented. USB feasibility: investigation phase.**

The existing Android Auto virtual-display code is not a CarPlay sender and cannot be relabeled or represented as one. The first milestone is a non-invasive USB capability probe; only proceed to protocol work if the phone and a lawful implementation path support it.

See [Wired CarPlay USB Feasibility — Phase 0](docs/USB-CARPLAY-USB-FEASIBILITY.md) for the scope, probe requirements, safety boundaries, and go/no-go criteria.

## Legacy Android Auto integration

The existing integration includes:

- Custom Android Auto virtual render resolution (width × height), with 0 × 0 meaning automatic head-unit-negotiated size.
- Resolution settings UI, Binder API, persistence, live apply, and reconnect re-apply.
- Head-unit negotiated profile kept separate from the render-buffer override.
- Split virtual-display resize rollback protection.
- LSPosed hooks for upstream system_server / Android Auto integration.
- No Shizuku dependency.

These capabilities apply to the legacy Android Auto path only. They do not provide USB gadget control, iAP2, CarPlay authentication, or a wired CarPlay session.

## USB / CarPlay safety boundaries

- No root or Shizuku requirement is assumed for the diagnostic phase.
- The diagnostic phase must be read-only and must not modify sysfs/configfs, USB descriptors, or USB role.
- Do not forge, extract, or bypass Apple MFi authentication credentials.
- USB enumeration alone is not proof that CarPlay has started.
- Do not use `wm size` or `wm density` as a substitute for virtual-display configuration; those commands alter the phone's physical display configuration.
- Preserve upstream GPL-3.0 license and notices in redistributed legacy builds.

## Target validation

- Phone: OnePlus Ace 5 / ColorOS 16 (user-reported; not yet probed)
- Vehicle: 2019 Honda Civic factory head unit
- Desired link: direct factory USB wired CarPlay, no wireless adapter
- Vehicle wired-CarPlay operation with a known-good iPhone: not yet verified
- Phone USB Gadget configurability: unknown until device probe

## Build verification

GitHub Actions currently verifies the legacy Android Auto integration build chain. A successful build does not establish wired CarPlay capability or physical-device compatibility. No wired-CarPlay APK is available at this stage.

## License

See the upstream GPL-3.0 license and preserve all source notices.

## Standalone USB diagnostic APK

A separate read-only Android diagnostic app is available under [usb-probe](usb-probe/). It reports device/build information, USB Host/Accessory enumeration, and read-only visibility of common USB Gadget paths. It does not change USB role, write sysfs/configfs, or request privileged access.

The **Build USB Probe APK** GitHub Actions workflow produces an installable debug APK artifact. Open the workflow run and download the `aadisplay-usb-probe-debug` artifact. Install it on the OnePlus Ace 5, run the scan, and share the report for analysis.

This probe is diagnostic only. It is not a CarPlay sender and does not establish wired CarPlay compatibility.
