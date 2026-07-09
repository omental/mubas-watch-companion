package com.muba.watchlab.ble

import java.util.UUID

/** Serial Port Profile - the classic Bluetooth profile most "dumb" RFCOMM watches use. */
val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

data class ClassicUuidInfo(
    val uuid: UUID,
    val friendlyName: String?
)

private val CLASSIC_PROFILE_NAMES = mapOf(
    "00001101-0000-1000-8000-00805f9b34fb" to "Serial Port Profile (SPP)",
    "0000111e-0000-1000-8000-00805f9b34fb" to "Handsfree",
    "0000111f-0000-1000-8000-00805f9b34fb" to "Handsfree Audio Gateway",
    "00001108-0000-1000-8000-00805f9b34fb" to "Headset",
    "00001112-0000-1000-8000-00805f9b34fb" to "Headset Audio Gateway",
    "0000110a-0000-1000-8000-00805f9b34fb" to "Audio Source",
    "0000110b-0000-1000-8000-00805f9b34fb" to "Audio Sink",
    "0000110d-0000-1000-8000-00805f9b34fb" to "Advanced Audio Distribution (A2DP)",
    "0000110c-0000-1000-8000-00805f9b34fb" to "AVRCP Target",
    "0000110e-0000-1000-8000-00805f9b34fb" to "AVRCP",
    "0000110f-0000-1000-8000-00805f9b34fb" to "AVRCP Controller",
    "00001124-0000-1000-8000-00805f9b34fb" to "Human Interface Device (HID)",
    "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access"
)

/** Friendly name for a handful of common classic Bluetooth profile UUIDs, or null if unrecognized. */
fun classicProfileName(uuid: UUID): String? = CLASSIC_PROFILE_NAMES[uuid.toString().lowercase()]
