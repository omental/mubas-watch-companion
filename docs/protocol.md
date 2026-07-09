# Protocol Notes

These notes document the currently confirmed KC10 / C7S / WiiWatch2-compatible behavior observed by this project.

## Transport

- Transport: Classic Bluetooth SPP
- UUID: `00001101-0000-1000-8000-00805f9b34fb`
- Connection type: RFCOMM socket created with the normal SPP UUID

The app does not require BLE, GATT, SDP UUID fetching, or Channel 41 for the confirmed companion flow.

## Handshake

The watch and app communicate with Java serialization streams.

Confirmed flow:

1. Connect over Classic Bluetooth SPP.
2. Read the Java stream header from the watch: `AC ED 00 05`.
3. Preserve/reuse the header for `ObjectInputStream`.
4. Create and flush `ObjectOutputStream`.
5. Start a single `ObjectInputStream.readObject()` loop.

Raw reads and `ObjectInputStream.readObject()` must not consume the same input stream at the same time.

## MessageBean Format

Serializable class:

```text
com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean
```

`serialVersionUID`:

```text
12141117
```

Known fields:

- `cmd`
- `true_false`
- `order`
- `maxValue`
- `currentValue`
- `str`
- `identifier`
- `bytes`

## Confirmed RX Commands

Observed objects received from the watch include:

- `Battery`
- `Health data`
- `Raise to wake`
- `Ambient clock`
- `Watch remind way`
- `Moving target`
- `Sports mode`
- `Bluetooth Hands-free`
- `Device statistics`

Example health data format:

```text
2924|2.1|125.5|96|17.5完
```

Parsed as:

- Steps
- Distance
- Calories
- Heart Rate / Health Value
- Extra Health Value

Example device statistics format:

```text
<redacted-device-id>|C7S|KC10|KW|KC10_V1.5_B_20191111|7.1.1|UNKNOWN|4.4.22 Mon Nov 11 09:59:41 CST 2019完|
```

Parsed as:

- Device ID
- Model code
- Device name
- Brand/code
- Firmware
- Android version
- Unknown field
- Build info

## Confirmed TX Commands

Confirmed simple MessageBean commands:

- `cmd="Battery"`
- `cmd="Find the target"`, `true_false=true`
- `cmd="Find the target"`, `true_false=false`
- `cmd="Heart-rate"`, `true_false=true`
- `cmd="Heart-rate"`, `true_false=false`
- `cmd="Raise to wake"`, `true_false=true`
- `cmd="Raise to wake"`, `true_false=false`

Object writes flush before and after `writeObject(bean)`.

## Unknown / Experimental Commands

Known but still treated as experimental:

- `Ambient clock`
- `Watch remind way`
- `Sports mode`
- `Bluetooth Hands-free`

File transfer, firmware flashing, theme installation, and other high-risk commands are intentionally not implemented.

## Safe Testing Notes

- Test only on devices you own or have permission to test.
- Prefer read-only commands first.
- Toggle one setting at a time.
- Keep logs short and redact private identifiers before sharing.
- If a command causes instability, stop testing and document the last safe step.
