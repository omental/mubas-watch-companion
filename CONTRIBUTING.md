# Contributing

Thanks for helping improve Muba’s Watch Companion. This project is about safe, transparent interoperability with watches that use the KC10 / C7S / WiiWatch2-style protocol.

## Report Device Compatibility

When reporting a compatible or incompatible device, include:

- Public model name
- Model/platform code if visible
- Firmware version
- Android version reported by the watch, if available
- Which features work
- Which features fail
- Whether the app connects over Classic Bluetooth SPP

Do not include private identifiers such as IMEI-like IDs, Bluetooth MAC addresses, serial numbers, account data, or personal logs unless they are redacted.

## Share Protocol Logs Safely

Useful logs are welcome, but please sanitize them first:

- Redact Bluetooth addresses.
- Redact device IDs and serial-like values.
- Remove personal names, phone numbers, notifications, messages, and account identifiers.
- Share the smallest log needed to explain the behavior.

Do not upload proprietary APKs, decompiled source code, vendor firmware images, or copyrighted vendor assets.

## Test Commands Safely

- Test only with devices you own or have permission to test.
- Start with read-only or simple toggle commands.
- Avoid file transfer, firmware, theme, or flashing-related commands.
- Document the exact command, observed response, and device model.
- Stop testing if the watch becomes unstable, overheats, reboots repeatedly, or loses pairing.

## Coding Style

- Keep Bluetooth/SPP/ObjectStream protocol changes small and well logged.
- Prefer clear Kotlin and Jetpack Compose code over clever abstractions.
- Do not add third-party libraries unless there is a strong reason and maintainers agree.
- Keep UI changes separate from protocol changes when practical.
- Run before submitting:

```bash
./gradlew assembleDebug lintDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug lintDebug
```
