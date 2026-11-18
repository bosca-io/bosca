package bosca.system.ui

fun systemPasskeyTransport(value: String): String = when (value) {
    "internal" -> "Platform"
    "usb" -> "USB"
    "ble" -> "Bluetooth"
    "nfc" -> "NFC"
    "hybrid" -> "Hybrid"
    else -> value
}
