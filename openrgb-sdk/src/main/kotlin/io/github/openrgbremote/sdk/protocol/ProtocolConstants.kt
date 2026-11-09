package io.github.openrgbremote.sdk.protocol

/** Constants of the OpenRGB SDK protocol (see `NetworkProtocol.h`). */
public object ProtocolConstants {
    /** Default SDK server port, "ORGB" on a phone keypad. */
    public const val DEFAULT_PORT: Int = 6742

    /** Highest protocol version this client implements (OpenRGB 1.0 / 1.1). */
    public const val CLIENT_PROTOCOL_VERSION: Int = 6

    /** Size of [PacketHeader] on the wire: magic(4) + dev_id(4) + pkt_id(4) + pkt_size(4). */
    public const val HEADER_SIZE: Int = 16

    /** `OPENRGB_SDK_MAX_PACKET_SIZE`: the server never sends more than 8 MiB of payload. */
    public const val MAX_PACKET_SIZE: Int = 8 * 1024 * 1024

    /** Magic value "ORGB" as raw ASCII bytes. */
    public val MAGIC: ByteArray = byteArrayOf('O'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte())
}

/** Status codes carried by [PacketId.ACK] (protocol 6+). */
public enum class AckStatus(public val code: Int) {
    OK(0),
    ERROR_GENERIC(1),
    ERROR_UNSUPPORTED(2),
    ERROR_NOT_ALLOWED(3),
    ERROR_INVALID_ID(4),
    ERROR_INVALID_DATA(5),
    UNKNOWN(-1),
    ;

    public companion object {
        public fun fromCode(code: Int): AckStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Client capability flags sent with [PacketId.SET_CLIENT_FLAGS] (protocol 6+). */
public object ClientFlags {
    public const val SUPPORTS_RGBCONTROLLER: Int = 1 shl 0
    public const val SUPPORTS_LOGMANAGER: Int = 1 shl 1
    public const val SUPPORTS_PROFILEMANAGER: Int = 1 shl 2
    public const val SUPPORTS_PLUGINMANAGER: Int = 1 shl 3
    public const val SUPPORTS_SETTINGSMANAGER: Int = 1 shl 4
    public const val REQUEST_LOCAL_CLIENT: Int = 1 shl 16
}

/** Server capability flags received with [PacketId.SET_SERVER_FLAGS] (protocol 6+). */
public object ServerFlags {
    public const val SUPPORTS_RGBCONTROLLER: Int = 1 shl 0
    public const val SUPPORTS_LOGMANAGER: Int = 1 shl 1
    public const val SUPPORTS_PROFILEMANAGER: Int = 1 shl 2
    public const val SUPPORTS_PLUGINMANAGER: Int = 1 shl 3
    public const val SUPPORTS_SETTINGSMANAGER: Int = 1 shl 4
    public const val SUPPORTS_DETECTION: Int = 1 shl 5
    public const val SUPPORTS_DEVICE_INFO: Int = 1 shl 6
    public const val LOCAL_CLIENT: Int = 1 shl 16
}

/** Reason codes of [PacketId.RGBCONTROLLER_SIGNALUPDATE] (`RGBControllerInterface.h`, enum starting at 0). */
public object UpdateReason {
    public const val UPDATE_LEDS: Int = 0
    public const val UPDATE_MODE: Int = 1
    public const val SAVE_MODE: Int = 2
    public const val CONFIGURE_ZONE: Int = 3
    public const val CLEAR_SEGMENTS: Int = 4
    public const val ADD_SEGMENT: Int = 5
    public const val HIDDEN: Int = 6
    public const val UNHIDDEN: Int = 7
    public const val SET_DEVICE_SPECIFIC_CONFIGURATION: Int = 8
    public const val SET_DEVICE_SPECIFIC_ZONE_CONFIGURATION: Int = 9
    public const val CONFIGURE_DEVICE: Int = 10
    public const val DEVICE_CHANGED: Int = 11
}

/** Feature checks by negotiated protocol version, so call sites never compare magic numbers. */
public object ProtocolFeatures {
    public fun hasVersionRequest(version: Int): Boolean = version >= 1
    public fun hasVendorString(version: Int): Boolean = version >= 1
    public fun hasModeBrightness(version: Int): Boolean = version >= 3
    public fun hasSaveMode(version: Int): Boolean = version >= 3
    public fun hasSegments(version: Int): Boolean = version >= 4
    public fun hasZoneFlags(version: Int): Boolean = version >= 5
    public fun hasControllerFlags(version: Int): Boolean = version >= 5
    public fun hasLedDisplayNames(version: Int): Boolean = version >= 5
    public fun hasRescan(version: Int): Boolean = version >= 5
    public fun hasUniqueIds(version: Int): Boolean = version >= 6
    public fun hasAcks(version: Int): Boolean = version >= 6
    public fun hasZoneModes(version: Int): Boolean = version >= 6
    public fun hasSignalUpdate(version: Int): Boolean = version >= 6
    public fun hasModeValue(version: Int): Boolean = version < 6
    public fun hasLedValue(version: Int): Boolean = version < 6
    public fun hasSegmentMatrixMap(version: Int): Boolean = version >= 6
}
