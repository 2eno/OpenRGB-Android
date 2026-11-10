package dev.eno2.openrgbremote.sdk.protocol

import dev.eno2.openrgbremote.sdk.Fixtures
import dev.eno2.openrgbremote.sdk.Fixtures.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ResponsesTest {

    @Test
    fun `ack carries acked packet id and status`() {
        // Captured from OpenRGB 1.0: ACK for REQUEST_PROTOCOL_VERSION (40) with status OK.
        val ack = Responses.ack(Packet(0, PacketId.ACK, hex("28000000 00000000")))
        assertEquals(Ack(0, PacketId.REQUEST_PROTOCOL_VERSION, AckStatus.OK), ack)
        val rejected = Responses.ack(Packet(9, PacketId.ACK, hex("1a040000 04000000")))
        assertEquals(AckStatus.ERROR_INVALID_ID, rejected.status)
        assertEquals(9, rejected.deviceId)
        assertEquals(AckStatus.UNKNOWN, Responses.ack(Packet(0, PacketId.ACK, hex("00000000 63000000"))).status)
    }

    @Test
    fun `server name string as sent by OpenRGB 1_0`() {
        val payload = "OpenRGB 1.0".toByteArray() + 0
        assertEquals("OpenRGB 1.0", Responses.string(Packet(0, PacketId.SET_SERVER_NAME, payload)))
    }

    @Test
    fun `controller id list size must match the count for protocol 6`() {
        assertFailsWith<ProtocolException> {
            Responses.controllerIds(Packet(0, PacketId.REQUEST_CONTROLLER_COUNT, hex("02000000 05000000")), 6)
        }
        assertEquals(listOf(5, 9), Responses.controllerIds(Packet(0, PacketId.REQUEST_CONTROLLER_COUNT, hex("02000000 05000000 09000000")), 6))
    }

    @Test
    fun `signal update with device description`() {
        val deviceData = Fixtures.bytes("v6_device2.bin")
        // SIGNALUPDATE = data_size + reason + device data without its own data_size field.
        val body = deviceData.copyOfRange(4, deviceData.size)
        val w = LittleEndianWriter().i32(8 + body.size).i32(UpdateReason.UPDATE_MODE).bytes(body)
        val update = Responses.signalUpdate(Packet(2, PacketId.RGBCONTROLLER_SIGNALUPDATE, w.toByteArray()), 6, 4)
        assertIs<SignalUpdate.Description>(update)
        assertEquals("Debug ARGB Controller", update.device.name)
        assertEquals(2, update.device.id)
        assertEquals(4, update.device.index)
    }

    @Test
    fun `signal update with wrong data_size is rejected`() {
        assertFailsWith<ProtocolException> {
            Responses.signalUpdate(Packet(0, PacketId.RGBCONTROLLER_SIGNALUPDATE, hex("ff000000 00000000 0000")), 6, 0)
        }
    }

    @Test
    fun `wrong packet type is rejected`() {
        assertFailsWith<ProtocolException> { Responses.protocolVersion(Packet(0, PacketId.ACK, hex("06000000"))) }
    }
}
