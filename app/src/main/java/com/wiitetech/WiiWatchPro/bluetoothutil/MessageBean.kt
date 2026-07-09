package com.wiitetech.WiiWatchPro.bluetoothutil

import java.io.Serializable

/**
 * Structural mirror of the MessageBean class found in the WiiWatch2 APK: same package,
 * class name, serialVersionUID, and field set (name + type), so instances we serialize
 * are wire-compatible with the watch's own ObjectInputStream/ObjectOutputStream - the
 * watch resolves the incoming classdesc against its own copy of this exact class.
 */
class MessageBean : Serializable {
    var cmd: String = ""
    var true_false: Boolean = false
    var order: Int = -1
    var maxValue: Int = -1
    var currentValue: Int = -1
    var str: String = ""
    var identifier: String = ""
    var bytes: ByteArray? = null

    override fun toString(): String =
        "MessageBean(cmd=\"$cmd\", true_false=$true_false, order=$order, maxValue=$maxValue, " +
            "currentValue=$currentValue, str=\"$str\", identifier=\"$identifier\", " +
            "bytes=${bytes?.size ?: 0} byte(s))"

    companion object {
        private const val serialVersionUID = 12141117L
    }
}
