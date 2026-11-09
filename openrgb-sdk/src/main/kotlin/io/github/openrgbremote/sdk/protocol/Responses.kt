package io.github.openrgbremote.sdk.protocol

import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.OpenRgbDevice

/** Parsed `ACK` packet (protocol 6+). */
public data class Ack(val deviceId: Int, val ackedPacketId: Int, val status: AckStatus)

/** Parsed `RGBCONTROLLER_SIGNALUPDATE` packet (protocol 6+). */
public sealed interface SignalUpdate {
    public val deviceId: Int
    public val reason: Int

    /** `UPDATELEDS`: only the color buffer changed. */
    public data class Colors(override val deviceId: Int, val colors: ColorArray) : SignalUpdate {
        override val reason: Int get() = UpdateReason.UPDATE_LEDS
    }

    /** Any other reason: the server sent a complete new device description. */
    public data class Description(override val deviceId: Int, override val reason: Int, val device: OpenRgbDevice) : SignalUpdate
}

/** Decoders for server-to-client packets other than controller data (see [DeviceCodec]). */
public object Responses {

    /** `REQUEST_PROTOCOL_VERSION` reply: one `unsigned int`. */
    public fun protocolVersion(packet: Packet): Int {
        expect(packet, PacketId.REQUEST_PROTOCOL_VERSION)
        if (packet.payload.size != 4) throw ProtocolException("Protocol version reply must be 4 bytes, got ${packet.payload.size}")
        return packet.reader().u32AsInt("protocol version")
    }

    /**
     * `REQUEST_CONTROLLER_COUNT` reply. Returns the device ids to use as `pkt_dev_id`:
     * indices `0..count-1` for protocol < 6, the transmitted unique ids for protocol 6+.
     */
    public fun controllerIds(packet: Packet, protocolVersion: Int): List<Int> {
        expect(packet, PacketId.REQUEST_CONTROLLER_COUNT)
        val reader = packet.reader()
        val count = reader.u32AsInt("controller count")
        if (!ProtocolFeatures.hasUniqueIds(protocolVersion)) {
            return List(count) { it }
        }
        val expectedSize = 4L + count.toLong() * 4L
        if (packet.payload.size.toLong() != expectedSize) {
            throw ProtocolException("Controller id list: expected $expectedSize bytes for $count ids, got ${packet.payload.size}")
        }
        return List(count) { reader.i32("controller id $it") }
    }

    /** `ACK`: `acked_pkt_id(u32) status(u32)`; the header's dev id is the acked packet's dev id. */
    public fun ack(packet: Packet): Ack {
        expect(packet, PacketId.ACK)
        val reader = packet.reader()
        val ackedId = reader.i32("acked packet id")
        val status = reader.i32("ack status")
        return Ack(packet.deviceId, ackedId, AckStatus.fromCode(status))
    }

    /** NUL-terminated string payload (server name / hostname). */
    public fun string(packet: Packet): String =
        LittleEndianReader(packet.payload).rawString(packet.payload.size, PacketId.nameOf(packet.packetId))

    /** `SET_SERVER_FLAGS`: one `unsigned int`. */
    public fun serverFlags(packet: Packet): Int {
        expect(packet, PacketId.SET_SERVER_FLAGS)
        return packet.reader().i32("server flags")
    }

    /**
     * `RGBCONTROLLER_SIGNALUPDATE`: `data_size(u32) update_reason(u32)` followed by either a color
     * description (reason `UPDATELEDS`) or a full device description (every other reason).
     * [index] is the position of the device in the client's list (kept for the decoded model).
     */
    public fun signalUpdate(packet: Packet, protocolVersion: Int, index: Int): SignalUpdate {
        expect(packet, PacketId.RGBCONTROLLER_SIGNALUPDATE)
        val reader = packet.reader()
        val dataSize = reader.u32("data_size")
        if (dataSize != packet.payload.size.toLong()) {
            throw ProtocolException("SignalUpdate data_size $dataSize does not match packet size ${packet.payload.size}")
        }
        val reason = reader.i32("update reason")
        return if (reason == UpdateReason.UPDATE_LEDS) {
            SignalUpdate.Colors(packet.deviceId, DeviceCodec.decodeColors(reader))
        } else {
            SignalUpdate.Description(packet.deviceId, reason, DeviceCodec.decodeDevice(reader, protocolVersion, packet.deviceId, index))
        }
    }

    private fun expect(packet: Packet, id: Int) {
        if (packet.packetId != id) {
            throw ProtocolException("Expected ${PacketId.nameOf(id)}, got ${PacketId.nameOf(packet.packetId)}")
        }
    }
}
