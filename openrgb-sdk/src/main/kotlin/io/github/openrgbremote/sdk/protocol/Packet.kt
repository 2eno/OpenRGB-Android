package io.github.openrgbremote.sdk.protocol

/**
 * `NetPacketHeader`: magic "ORGB", device id, packet id and payload size, all little endian.
 *
 * [deviceId] is the controller *index* for protocol 0-5 and the controller *unique id*
 * for protocol 6+. Both are `unsigned int`; they are kept as [Int] bit patterns.
 */
public data class PacketHeader(
    val deviceId: Int,
    val packetId: Int,
    val payloadSize: Int,
) {
    init {
        require(payloadSize >= 0) { "Negative payload size" }
    }

    public fun encode(): ByteArray = LittleEndianWriter(ProtocolConstants.HEADER_SIZE)
        .bytes(ProtocolConstants.MAGIC)
        .i32(deviceId)
        .i32(packetId)
        .i32(payloadSize)
        .toByteArray()

    public companion object {
        /**
         * Decodes a header from exactly [ProtocolConstants.HEADER_SIZE] bytes.
         * @throws ProtocolException on wrong magic or an implausible size.
         */
        public fun decode(bytes: ByteArray, offset: Int = 0): PacketHeader {
            if (bytes.size - offset < ProtocolConstants.HEADER_SIZE) {
                throw ProtocolException("Header needs ${ProtocolConstants.HEADER_SIZE} bytes, got ${bytes.size - offset}")
            }
            for (i in 0 until 4) {
                if (bytes[offset + i] != ProtocolConstants.MAGIC[i]) {
                    throw ProtocolException("Invalid magic, this is not an OpenRGB SDK server")
                }
            }
            val reader = LittleEndianReader(bytes, offset + 4, offset + ProtocolConstants.HEADER_SIZE)
            val deviceId = reader.i32("pkt_dev_id")
            val packetId = reader.i32("pkt_id")
            val size = reader.u32("pkt_size")
            if (size > ProtocolConstants.MAX_PACKET_SIZE) {
                throw ProtocolException("Packet ${PacketId.nameOf(packetId)} announces $size bytes, limit is ${ProtocolConstants.MAX_PACKET_SIZE}")
            }
            return PacketHeader(deviceId, packetId, size.toInt())
        }
    }
}

/** A complete packet: header plus payload. */
public class Packet(
    public val deviceId: Int,
    public val packetId: Int,
    public val payload: ByteArray = EMPTY,
) {
    public val header: PacketHeader get() = PacketHeader(deviceId, packetId, payload.size)

    /** Header and payload as one contiguous buffer, so it can be written with a single syscall. */
    public fun encode(): ByteArray {
        val out = ByteArray(ProtocolConstants.HEADER_SIZE + payload.size)
        header.encode().copyInto(out)
        payload.copyInto(out, ProtocolConstants.HEADER_SIZE)
        return out
    }

    public fun reader(): LittleEndianReader = LittleEndianReader(payload)

    override fun toString(): String = "Packet(${PacketId.nameOf(packetId)}, dev=$deviceId, ${payload.size} bytes)"

    override fun equals(other: Any?): Boolean =
        other is Packet && other.deviceId == deviceId && other.packetId == packetId && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (deviceId * 31 + packetId) * 31 + payload.contentHashCode()

    public companion object {
        public val EMPTY: ByteArray = ByteArray(0)
    }
}
