package com.muba.watchlab.ble

import java.util.UUID

val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

private const val STANDARD_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** True for 16/32-bit UUIDs assigned from the official Bluetooth SIG base UUID. */
fun isStandardUuid(uuid: UUID): Boolean = uuid.toString().endsWith(STANDARD_UUID_SUFFIX)

private val WELL_KNOWN_SERVICE_NAMES = mapOf(
    "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access",
    "00001801-0000-1000-8000-00805f9b34fb" to "Generic Attribute",
    "00001802-0000-1000-8000-00805f9b34fb" to "Immediate Alert",
    "00001803-0000-1000-8000-00805f9b34fb" to "Link Loss",
    "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information",
    "0000180d-0000-1000-8000-00805f9b34fb" to "Heart Rate",
    "0000180f-0000-1000-8000-00805f9b34fb" to "Battery Service"
)

/** Friendly name for a handful of common standard services, or null if not recognized. */
fun standardServiceName(uuid: UUID): String? = WELL_KNOWN_SERVICE_NAMES[uuid.toString().lowercase()]
