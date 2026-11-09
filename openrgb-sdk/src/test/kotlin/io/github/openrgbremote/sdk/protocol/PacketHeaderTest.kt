package io.github.openrgbremote.sdk.protocol

import io.github.openrgbremote.sdk.Fixtures.hex
import io.github.openrgbremote.sdk.Fixtures.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketHeaderTest {

    @Test
    fun `header is magic followed by three little-endian uint32`() {
        val header = PacketHeader(deviceId = 3, packetId = PacketId.RGBCONTROLLER_UPDATELEDS, payloadSize = 0x0102)
        assertEquals(
            hex(
                """
                4f524742 // "ORGB"
                03000000 // pkt_dev_id = 3
                1a040000 // pkt_id = 1050
                02010000 // pkt_size = 258
                """,
            ).toHex(),
            header.encode().toHex(),
        )
    }

    @Test
    fun `decodes the protocol version reply captured from OpenRGB 1_0`() {
        // First 20 bytes the real server sent after REQUEST_PROTOCOL_VERSION(6).
        val bytes = hex("4f524742 00000000 28000000 04000000 06000000")
        val header = PacketHeader.decode(bytes)
        assertEquals(PacketHeader(0, PacketId.REQUEST_PROTOCOL_VERSION, 4), header)
        val packet = Packet(header.deviceId, header.packetId, bytes.copyOfRange(16, 20))
        assertEquals(6, Responses.protocolVersion(packet))
    }

    @Test
    fun `unique ids above Int_MAX_VALUE survive as bit pattern`() {
        val header = PacketHeader(deviceId = -2, packetId = 1, payloadSize = 4)
        val decoded = PacketHeader.decode(header.encode())
        assertEquals(0xFFFFFFFEL, decoded.deviceId.toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `wrong magic is rejected with a recognizable message`() {
        val e = assertFailsWith<ProtocolException> { PacketHeader.decode(hex("48545450 00000000 00000000 00000000")) }
        assertTrue("magic" in e.message!!.lowercase())
    }

    @Test
    fun `payload sizes above the server limit are rejected`() {
        assertFailsWith<ProtocolException> { PacketHeader.decode(hex("4f524742 00000000 01000000 01008000")) }
    }

    @Test
    fun `short header is rejected`() {
        assertFailsWith<ProtocolException> { PacketHeader.decode(hex("4f524742 00000000")) }
    }

    @Test
    fun `packet encode concatenates header and payload`() {
        val packet = Packet(1, PacketId.SET_CLIENT_NAME, byteArrayOf(0x41, 0))
        assertEquals("4f524742" + "01000000" + "32000000" + "02000000" + "4100", packet.encode().toHex())
    }
}
