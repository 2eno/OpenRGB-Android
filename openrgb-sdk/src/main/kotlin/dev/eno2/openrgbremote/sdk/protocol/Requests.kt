package dev.eno2.openrgbremote.sdk.protocol

import dev.eno2.openrgbremote.sdk.model.DeviceMode

/**
 * Builders for every packet the client sends. Each function returns a ready-to-send [Packet];
 * none of them performs I/O, which keeps the byte layout unit-testable.
 *
 * Layouts follow `RGBController_Network.cpp` / `NetworkClient.cpp` of OpenRGB 1.1. Where a
 * payload starts with `data_size`, that value counts the complete payload *including* the
 * 4 bytes of the field itself (servers >= 0.9 reject packets where it differs from `pkt_size`).
 */
public object Requests {

    /** `REQUEST_PROTOCOL_VERSION`: payload is the client's highest supported version. */
    public fun requestProtocolVersion(clientMaxVersion: Int = ProtocolConstants.CLIENT_PROTOCOL_VERSION): Packet =
        Packet(0, PacketId.REQUEST_PROTOCOL_VERSION, LittleEndianWriter(4).i32(clientMaxVersion).toByteArray())

    /** `SET_CLIENT_NAME`: NUL-terminated name, no reply. */
    public fun setClientName(name: String): Packet =
        Packet(0, PacketId.SET_CLIENT_NAME, LittleEndianWriter(name.length + 1).cString(name).toByteArray())

    /** `SET_CLIENT_HOSTNAME` (v6): NUL-terminated host name, server answers with `SET_SERVER_HOSTNAME`. */
    public fun setClientHostname(hostname: String): Packet =
        Packet(0, PacketId.SET_CLIENT_HOSTNAME, LittleEndianWriter(hostname.length + 1).cString(hostname).toByteArray())

    /** `SET_CLIENT_FLAGS` (v6): server answers with `SET_SERVER_FLAGS`. */
    public fun setClientFlags(flags: Int = ClientFlags.SUPPORTS_RGBCONTROLLER): Packet =
        Packet(0, PacketId.SET_CLIENT_FLAGS, LittleEndianWriter(4).i32(flags).toByteArray())

    /** `REQUEST_CONTROLLER_COUNT`: empty request. */
    public fun requestControllerCount(): Packet = Packet(0, PacketId.REQUEST_CONTROLLER_COUNT)

    /**
     * `REQUEST_CONTROLLER_DATA`: empty for protocol 0, otherwise the negotiated protocol version,
     * which also selects the layout of the reply.
     */
    public fun requestControllerData(deviceId: Int, protocolVersion: Int): Packet =
        if (protocolVersion == 0) {
            Packet(deviceId, PacketId.REQUEST_CONTROLLER_DATA)
        } else {
            Packet(deviceId, PacketId.REQUEST_CONTROLLER_DATA, LittleEndianWriter(4).i32(protocolVersion).toByteArray())
        }

    /** `REQUEST_RESCAN_DEVICES` (v5+): empty. */
    public fun requestRescanDevices(): Packet = Packet(0, PacketId.REQUEST_RESCAN_DEVICES)

    /**
     * `RGBCONTROLLER_UPDATELEDS`: `data_size(u32) num_colors(u16) RGBColor[num_colors]`.
     * [colors] are `0xRRGGBB`; the number must not exceed the device's LED count.
     */
    public fun updateLeds(deviceId: Int, colors: IntArray): Packet {
        val w = LittleEndianWriter(4 + 2 + colors.size * 4)
        w.i32(4 + 2 + colors.size * 4)
        w.u16(colors.size)
        colors.forEach { w.rgbColor(it) }
        return Packet(deviceId, PacketId.RGBCONTROLLER_UPDATELEDS, w.toByteArray())
    }

    /** `RGBCONTROLLER_UPDATEZONELEDS`: `data_size(u32) zone_idx(u32) num_colors(u16) RGBColor[]`. */
    public fun updateZoneLeds(deviceId: Int, zoneIndex: Int, colors: IntArray): Packet {
        val size = 4 + 4 + 2 + colors.size * 4
        val w = LittleEndianWriter(size)
        w.i32(size)
        w.i32(zoneIndex)
        w.u16(colors.size)
        colors.forEach { w.rgbColor(it) }
        return Packet(deviceId, PacketId.RGBCONTROLLER_UPDATEZONELEDS, w.toByteArray())
    }

    /** `RGBCONTROLLER_UPDATESINGLELED`: `led_idx(i32) RGBColor` (no data_size field). */
    public fun updateSingleLed(deviceId: Int, ledIndex: Int, color: Int): Packet =
        Packet(deviceId, PacketId.RGBCONTROLLER_UPDATESINGLELED, LittleEndianWriter(8).i32(ledIndex).rgbColor(color).toByteArray())

    /** `RGBCONTROLLER_SETCUSTOMMODE`: empty; the server switches to its direct/custom mode. */
    public fun setCustomMode(deviceId: Int): Packet = Packet(deviceId, PacketId.RGBCONTROLLER_SETCUSTOMMODE)

    /** `RGBCONTROLLER_UPDATEMODE`: `data_size(u32) mode_idx(i32) ModeData`. */
    public fun updateMode(deviceId: Int, modeIndex: Int, mode: DeviceMode, protocolVersion: Int): Packet =
        Packet(deviceId, PacketId.RGBCONTROLLER_UPDATEMODE, modePayload(modeIndex, mode, protocolVersion))

    /** `RGBCONTROLLER_SAVEMODE` (v3+): same layout as UpdateMode, persists the mode in device flash. */
    public fun saveMode(deviceId: Int, modeIndex: Int, mode: DeviceMode, protocolVersion: Int): Packet {
        require(ProtocolFeatures.hasSaveMode(protocolVersion)) { "SaveMode needs protocol 3+, negotiated $protocolVersion" }
        return Packet(deviceId, PacketId.RGBCONTROLLER_SAVEMODE, modePayload(modeIndex, mode, protocolVersion))
    }

    /**
     * `RGBCONTROLLER_UPDATEZONEMODE` (v6+): `data_size zone_idx(i32) mode_idx(i32) [ModeData]`.
     * A [modeIndex] of -1 (with `mode == null`) returns the zone to the device-wide mode.
     */
    public fun updateZoneMode(deviceId: Int, zoneIndex: Int, modeIndex: Int, mode: DeviceMode?, protocolVersion: Int): Packet {
        require(ProtocolFeatures.hasZoneModes(protocolVersion)) { "UpdateZoneMode needs protocol 6+, negotiated $protocolVersion" }
        val w = LittleEndianWriter(64)
        w.i32(0)
        w.i32(zoneIndex)
        w.i32(modeIndex)
        if (modeIndex >= 0) {
            requireNotNull(mode) { "mode required for modeIndex >= 0" }
            DeviceCodec.encodeMode(w, mode, protocolVersion)
        }
        w.patchI32(0, w.size)
        return Packet(deviceId, PacketId.RGBCONTROLLER_UPDATEZONEMODE, w.toByteArray())
    }

    /** `RGBCONTROLLER_RESIZEZONE`: `zone_idx(i32) new_size(i32)`. */
    public fun resizeZone(deviceId: Int, zoneIndex: Int, newSize: Int): Packet =
        Packet(deviceId, PacketId.RGBCONTROLLER_RESIZEZONE, LittleEndianWriter(8).i32(zoneIndex).i32(newSize).toByteArray())

    private fun modePayload(modeIndex: Int, mode: DeviceMode, protocolVersion: Int): ByteArray {
        val w = LittleEndianWriter(64)
        w.i32(0)
        w.i32(modeIndex)
        DeviceCodec.encodeMode(w, mode, protocolVersion)
        w.patchI32(0, w.size)
        return w.toByteArray()
    }
}
