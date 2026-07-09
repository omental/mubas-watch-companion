package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/** A device the phone already knows about via classic Bluetooth pairing (bonding). */
data class PairedDevice(
    val name: String,
    val address: String,
    val bondStateLabel: String,
    val typeLabel: String
)

/**
 * Reads the phone's paired (bonded) devices. Returns an empty list if Bluetooth isn't
 * ready or (on Android 12+) BLUETOOTH_CONNECT hasn't been granted yet.
 */
fun readPairedDevices(context: Context, bluetoothAdapter: BluetoothAdapter?): List<PairedDevice> {
    if (bluetoothAdapter == null || !hasConnectPermission(context)) return emptyList()
    return try {
        bluetoothAdapter.bondedDevices.orEmpty().map { it.toPairedDevice() }
    } catch (e: SecurityException) {
        emptyList()
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.toPairedDevice(): PairedDevice = PairedDevice(
    name = name?.takeIf { it.isNotBlank() } ?: "Unknown device",
    address = address,
    bondStateLabel = bondStateLabel(bondState),
    typeLabel = deviceTypeLabel(type)
)

fun deviceTypeLabel(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "LE"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
    else -> "Unknown"
}

/** True if this paired device's transport supports a direct BLE (GATT) connection. */
val PairedDevice.isBleConnectable: Boolean
    get() = typeLabel == "LE" || typeLabel == "Dual"

/** A placeholder so a paired device can be shown in the same detail card used for scan results. */
fun PairedDevice.toBleDevicePlaceholder(): BleDevice = BleDevice(
    displayName = name,
    advertisedName = null,
    bluetoothName = name,
    address = address,
    rssi = 0,
    serviceUuids = emptyList(),
    manufacturerDataHex = null,
    bondStateLabel = bondStateLabel,
    isConnectable = null,
    txPower = null,
    rawScanInfo = "Paired device (type=$typeLabel)"
)

private val WATCH_NAME_KEYWORDS = listOf("watch", "band", "fit", "wristband")

// Cheap smartwatches are often paired under a terse model code like "KC 10" or "DT96" -
// a couple of letters followed by digits - rather than a descriptive product name.
private val MODEL_CODE_LIKE_NAME = Regex("^[A-Za-z]{1,4}\\s?\\d{1,4}.*")

/** True if a paired device's name looks like it could be a fitness watch/tracker. */
fun looksLikeWatchName(name: String): Boolean {
    val lower = name.lowercase()
    if (WATCH_NAME_KEYWORDS.any { lower.contains(it) }) return true
    return MODEL_CODE_LIKE_NAME.matches(name.trim())
}
