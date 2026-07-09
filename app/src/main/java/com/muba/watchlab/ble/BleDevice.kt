package com.muba.watchlab.ble

/** A BLE device found during a scan, with everything we could safely identify about it. */
data class BleDevice(
    /** Best-guess name to show as the primary label; see [BleScanner] for the priority used. */
    val displayName: String,
    /** Name carried in the advertising packet itself (scanRecord), if any. */
    val advertisedName: String?,
    /** Cached name from BluetoothDevice.name (requires BLUETOOTH_CONNECT on Android 12+). */
    val bluetoothName: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerDataHex: String?,
    val bondStateLabel: String,
    /** Whether the advertisement says the device accepts connections. Null below API 26. */
    val isConnectable: Boolean?,
    /** Advertised transmit power in dBm, if the advertisement included it. Null below API 26. */
    val txPower: Int?,
    /** Short debug string describing how this scan result arrived, useful for troubleshooting. */
    val rawScanInfo: String
)
