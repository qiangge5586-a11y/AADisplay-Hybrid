# CarPToCarL 1.2.18 — Honda Civic adaptation workspace

This isolated branch is reserved for the CarPToCarL Honda Civic compatibility work.

## Upstream
- Repository: https://github.com/jjuugk/CarPToCarL
- Target version: v1.2.18
- Upstream remains read-only from this linked account. Do not push changes to upstream.

## Target vehicle baseline
- 2019 Honda Civic 劲动版, factory center display
- Head unit platform: MY16ADA / Andromeda
- Android 4.2.2, API 17
- Display: 800x480
- Root access was confirmed in the supplied vehicle report.

## Adaptation strategy
- Match only explicit receiver identity markers: Civic, 思域, or MY16ADA.
- Use 800x480 only when AirPlay SETUP does not provide valid display dimensions.
- Use 30 fps only when the receiver does not advertise a valid maximum frame rate.
- Preserve negotiated SETUP values and existing non-Honda receiver behavior.
- Do not alter MFi authentication, iAP2 handshake selection, Wi-Fi provisioning, or audio behavior without protocol evidence.

## Current status
- A first-pass patch exists in the working session, but it has not been applied to a complete local checkout or compiled.
- The original APK is a binary and cannot substitute for the full Gradle source project.
- No APK build is claimed at this stage.
- Next required engineering step: bring the complete upstream v1.2.18 source tree into this workspace, apply and review the patch against the actual source, then run Gradle tests and assemble a debug APK.

## Important compatibility caveat
The vehicle report confirms the head unit's Android platform and display geometry, but does not by itself prove that the receiver implements the iAP2/AirPlay receiver protocol expected by CarPToCarL. Hardware/protocol compatibility must be verified from connection logs before claiming the adaptation works on the vehicle.
