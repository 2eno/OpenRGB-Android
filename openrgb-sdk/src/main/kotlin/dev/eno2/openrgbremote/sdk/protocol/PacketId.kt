package dev.eno2.openrgbremote.sdk.protocol

/**
 * Packet IDs of the OpenRGB SDK protocol.
 *
 * Values are taken verbatim from `NetworkProtocol.h` / `Documentation/OpenRGBSDK.md`
 * of OpenRGB release 1.1 (protocol version 6). The comment after each ID names the
 * minimum protocol version that knows the packet.
 */
public object PacketId {
    public const val REQUEST_CONTROLLER_COUNT: Int = 0 // v0
    public const val REQUEST_CONTROLLER_DATA: Int = 1 // v0
    public const val ACK: Int = 10 // v6
    public const val REQUEST_PROTOCOL_VERSION: Int = 40 // v1
    public const val SET_CLIENT_NAME: Int = 50 // v0
    public const val SET_SERVER_NAME: Int = 51 // v6
    public const val SET_CLIENT_FLAGS: Int = 52 // v6
    public const val SET_SERVER_FLAGS: Int = 53 // v6
    public const val SET_CLIENT_HOSTNAME: Int = 54 // v6
    public const val SET_SERVER_HOSTNAME: Int = 55 // v6
    public const val DEVICE_LIST_UPDATED: Int = 100 // v1
    public const val DETECTION_STARTED: Int = 101 // v6
    public const val DETECTION_PROGRESS_CHANGED: Int = 102 // v6
    public const val DETECTION_COMPLETE: Int = 103 // v6
    public const val REQUEST_RESCAN_DEVICES: Int = 140 // v5
    public const val PROFILEMANAGER_GET_PROFILE_LIST: Int = 150 // v2
    public const val PROFILEMANAGER_LOAD_PROFILE: Int = 152 // v2
    public const val RGBCONTROLLER_RESIZEZONE: Int = 1000 // v0
    public const val RGBCONTROLLER_UPDATELEDS: Int = 1050 // v0
    public const val RGBCONTROLLER_UPDATEZONELEDS: Int = 1051 // v0
    public const val RGBCONTROLLER_UPDATESINGLELED: Int = 1052 // v0
    public const val RGBCONTROLLER_SETCUSTOMMODE: Int = 1100 // v0
    public const val RGBCONTROLLER_UPDATEMODE: Int = 1101 // v0
    public const val RGBCONTROLLER_SAVEMODE: Int = 1102 // v3
    public const val RGBCONTROLLER_UPDATEZONEMODE: Int = 1103 // v6
    public const val RGBCONTROLLER_SIGNALUPDATE: Int = 1150 // v6

    /** Human-readable name for logs and error messages. */
    public fun nameOf(id: Int): String = when (id) {
        REQUEST_CONTROLLER_COUNT -> "REQUEST_CONTROLLER_COUNT"
        REQUEST_CONTROLLER_DATA -> "REQUEST_CONTROLLER_DATA"
        ACK -> "ACK"
        REQUEST_PROTOCOL_VERSION -> "REQUEST_PROTOCOL_VERSION"
        SET_CLIENT_NAME -> "SET_CLIENT_NAME"
        SET_SERVER_NAME -> "SET_SERVER_NAME"
        SET_CLIENT_FLAGS -> "SET_CLIENT_FLAGS"
        SET_SERVER_FLAGS -> "SET_SERVER_FLAGS"
        SET_CLIENT_HOSTNAME -> "SET_CLIENT_HOSTNAME"
        SET_SERVER_HOSTNAME -> "SET_SERVER_HOSTNAME"
        DEVICE_LIST_UPDATED -> "DEVICE_LIST_UPDATED"
        DETECTION_STARTED -> "DETECTION_STARTED"
        DETECTION_PROGRESS_CHANGED -> "DETECTION_PROGRESS_CHANGED"
        DETECTION_COMPLETE -> "DETECTION_COMPLETE"
        REQUEST_RESCAN_DEVICES -> "REQUEST_RESCAN_DEVICES"
        PROFILEMANAGER_GET_PROFILE_LIST -> "PROFILEMANAGER_GET_PROFILE_LIST"
        PROFILEMANAGER_LOAD_PROFILE -> "PROFILEMANAGER_LOAD_PROFILE"
        RGBCONTROLLER_RESIZEZONE -> "RGBCONTROLLER_RESIZEZONE"
        RGBCONTROLLER_UPDATELEDS -> "RGBCONTROLLER_UPDATELEDS"
        RGBCONTROLLER_UPDATEZONELEDS -> "RGBCONTROLLER_UPDATEZONELEDS"
        RGBCONTROLLER_UPDATESINGLELED -> "RGBCONTROLLER_UPDATESINGLELED"
        RGBCONTROLLER_SETCUSTOMMODE -> "RGBCONTROLLER_SETCUSTOMMODE"
        RGBCONTROLLER_UPDATEMODE -> "RGBCONTROLLER_UPDATEMODE"
        RGBCONTROLLER_SAVEMODE -> "RGBCONTROLLER_SAVEMODE"
        RGBCONTROLLER_UPDATEZONEMODE -> "RGBCONTROLLER_UPDATEZONEMODE"
        RGBCONTROLLER_SIGNALUPDATE -> "RGBCONTROLLER_SIGNALUPDATE"
        else -> "UNKNOWN($id)"
    }
}
