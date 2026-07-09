# Muba’s Watch Companion v0.1.0

First public test release.

## Confirmed working
- KC10 connection over Classic Bluetooth SPP
- Java ObjectStream / MessageBean protocol
- Live dashboard sync
- Battery request
- Find Watch ON/OFF; watch rings
- Heart Rate start/stop
- Raise to Wake ON/OFF
- Device info parsing
- Local battery and heart-rate history
- Reconnect cooldown
- Premium dark Framer-style UI
- Custom app icon

## Tested device
- Device: KC10
- Model/platform: C7S
- Firmware: KC10_V1.5_B_20191111
- Android: 7.1.1
- Build info: 4.4.22 Mon Nov 11 09:59:41 CST 2019

## Protocol summary
- Transport: Classic Bluetooth SPP
- UUID: 00001101-0000-1000-8000-00805f9b34fb
- Stream: Java ObjectStream
- Object class: com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean
- serialVersionUID: 12141117

## Installation
Download the APK from GitHub Releases and install it manually on Android.
You may need to allow installation from unknown sources.

## Disclaimer
This is an independent open-source interoperability/research project.
It is not affiliated with WiiWatch2, WiiWatchPro, KC10, or any manufacturer.
Test only with devices you own.
