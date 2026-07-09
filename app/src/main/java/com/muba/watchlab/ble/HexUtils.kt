package com.muba.watchlab.ble

/** Formats bytes as space-separated uppercase hex, e.g. "41 54 0D 0A". */
fun bytesToHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

/** Renders bytes as ASCII, replacing anything outside the printable range with '.'. */
fun bytesToPrintableAscii(bytes: ByteArray): String = bytes.joinToString("") { byte ->
    val value = byte.toInt() and 0xFF
    if (value in 32..126) value.toChar().toString() else "."
}

/** True only if every byte is in the printable ASCII range (32..126). */
fun isAllPrintable(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.all { (it.toInt() and 0xFF) in 32..126 }

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this.lowercaseChar() in 'a'..'f'

/**
 * Parses hex-byte text like "41 54 0D 0A" into bytes. Returns null if the input is
 * empty or contains anything that isn't a two-digit hex byte.
 */
fun parseHexBytes(input: String): ByteArray? {
    val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val bytes = ByteArray(tokens.size)
    for (i in tokens.indices) {
        val token = tokens[i]
        if (token.length != 2 || token.any { !it.isHexDigit() }) return null
        bytes[i] = token.toInt(16).toByte()
    }
    return bytes
}
