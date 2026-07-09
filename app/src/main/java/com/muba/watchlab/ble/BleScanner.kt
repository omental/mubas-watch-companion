package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build

/**
 * Thin wrapper around [BluetoothAdapter.getBluetoothLeScanner].
 * Callers must check permissions before calling [startScan].
 */
class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val onDeviceFound: (BleDevice) -> Unit,
    private val onScanFailed: (String) -> Unit
) {
    private var isScanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDeviceFound(buildBleDevice(context, bluetoothAdapter, callbackType, result))
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onScanFailed("BLE scan failed (error code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onScanFailed("Bluetooth is off or not available")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            isScanning = true
        } catch (e: SecurityException) {
            onScanFailed("Missing Bluetooth permission")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            // Permission was revoked while scanning; nothing to clean up.
        }
    }
}

private fun buildBleDevice(
    context: Context,
    bluetoothAdapter: BluetoothAdapter,
    callbackType: Int,
    result: ScanResult
): BleDevice {
    val device = result.device
    val scanRecord = result.scanRecord
    val canReadDeviceInfo = hasConnectPermission(context)

    val advertisedName = scanRecord?.deviceName?.takeIf { it.isNotBlank() }
    val bluetoothName = if (canReadDeviceInfo) safeDeviceName(device) else null
    val bondedName = if (canReadDeviceInfo) safeBondedDeviceName(bluetoothAdapter, device.address) else null
    val displayName = advertisedName ?: bluetoothName ?: bondedName ?: "Unknown device"

    val bondState = if (canReadDeviceInfo) safeBondStateLabel(device) else "Unknown"

    return BleDevice(
        displayName = displayName,
        advertisedName = advertisedName,
        bluetoothName = bluetoothName,
        address = device.address,
        rssi = result.rssi,
        serviceUuids = scanRecord?.serviceUuids?.map { it.toString() } ?: emptyList(),
        manufacturerDataHex = manufacturerDataHex(scanRecord),
        bondStateLabel = bondState,
        isConnectable = isConnectable(result),
        txPower = txPower(result),
        rawScanInfo = rawScanInfo(callbackType, result)
    )
}

// ScanResult.isConnectable() and getTxPower() only exist from API 26 (Android O) onward.
private fun isConnectable(result: ScanResult): Boolean? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null

private fun txPower(result: ScanResult): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
}

private fun rawScanInfo(callbackType: Int, result: ScanResult): String {
    val callbackLabel = when (callbackType) {
        ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> "ALL_MATCHES"
        ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "FIRST_MATCH"
        ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "MATCH_LOST"
        else -> "type=$callbackType"
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return callbackLabel
    val dataStatus = if (result.dataStatus == ScanResult.DATA_COMPLETE) "complete" else "truncated"
    return "$callbackLabel, data=$dataStatus"
}

@SuppressLint("MissingPermission")
private fun safeDeviceName(device: BluetoothDevice): String? = try {
    device.name?.takeIf { it.isNotBlank() }
} catch (e: SecurityException) {
    null
}

@SuppressLint("MissingPermission")
private fun safeBondedDeviceName(bluetoothAdapter: BluetoothAdapter, address: String): String? = try {
    bluetoothAdapter.bondedDevices
        ?.firstOrNull { it.address == address }
        ?.name
        ?.takeIf { it.isNotBlank() }
} catch (e: SecurityException) {
    null
}

@SuppressLint("MissingPermission")
private fun safeBondStateLabel(device: BluetoothDevice): String = try {
    bondStateLabel(device.bondState)
} catch (e: SecurityException) {
    "Unknown"
}

private fun manufacturerDataHex(scanRecord: ScanRecord?): String? {
    val manufacturerData = scanRecord?.manufacturerSpecificData ?: return null
    if (manufacturerData.size() == 0) return null
    val entries = (0 until manufacturerData.size()).mapNotNull { index ->
        val id = manufacturerData.keyAt(index)
        val bytes = manufacturerData.valueAt(index) ?: return@mapNotNull null
        val hex = bytes.joinToString(separator = "") { byte -> "%02X".format(byte) }
        "$id:$hex"
    }
    return entries.joinToString(", ").takeIf { it.isNotEmpty() }
}
