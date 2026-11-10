package dev.eno2.openrgbremote.sdk.protocol

import dev.eno2.openrgbremote.sdk.Fixtures.hex
import dev.eno2.openrgbremote.sdk.Fixtures.toHex
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.ColorMode
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.ModeDirection
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Byte-exact sample packets for every request the client sends. */
class RequestsTest {

    private fun Packet.wire(): String = encode().toHex()

    @Test
    fun `protocol version request`() {
        assertEquals(
            hex("4f524742 00000000 28000000 04000000  06000000").toHex(),
            Requests.requestProtocolVersion(6).wire(),
        )
    }

    @Test
    fun `client name is NUL terminated without length prefix`() {
        assertEquals(
            hex("4f524742 00000000 32000000 05000000  41424344 00").toHex(),
            Requests.setClientName("ABCD").wire(),
        )
    }

    @Test
    fun `client flags`() {
        assertEquals(
            hex("4f524742 00000000 34000000 04000000  01000000").toHex(),
            Requests.setClientFlags(ClientFlags.SUPPORTS_RGBCONTROLLER).wire(),
        )
    }

    @Test
    fun `controller data request is empty for protocol 0 and carries the version otherwise`() {
        assertEquals(hex("4f524742 02000000 01000000 00000000").toHex(), Requests.requestControllerData(2, 0).wire())
        assertEquals(
            hex("4f524742 02000000 01000000 04000000  04000000").toHex(),
            Requests.requestControllerData(2, 4).wire(),
        )
    }

    @Test
    fun `UpdateLEDs data_size counts the whole payload including itself`() {
        assertEquals(
            hex(
                """
                4f524742 05000000 1a040000 0e000000 // header, dev 5, id 1050, 14 bytes
                0e000000                            // data_size = 4 + 2 + 2*4 = 14
                0200                                // num_colors = 2
                ff000000                            // red   (R G B 0)
                00ff0000                            // green
                """,
            ).toHex(),
            Requests.updateLeds(5, intArrayOf(0xFF0000, 0x00FF00)).wire(),
        )
    }

    @Test
    fun `UpdateZoneLEDs`() {
        assertEquals(
            hex(
                """
                4f524742 01000000 1b040000 0e000000
                0e000000 // data_size = 4 + 4 + 2 + 4
                03000000 // zone 3
                0100     // one color
                0000ff00 // blue
                """,
            ).toHex(),
            Requests.updateZoneLeds(1, 3, intArrayOf(0x0000FF)).wire(),
        )
    }

    @Test
    fun `UpdateSingleLED has no data_size field`() {
        assertEquals(
            hex("4f524742 01000000 1c040000 08000000  07000000 102030 00").toHex(),
            Requests.updateSingleLed(1, 7, 0x102030).wire(),
        )
    }

    @Test
    fun `SetCustomMode and RescanDevices are empty`() {
        assertEquals(hex("4f524742 04000000 4c040000 00000000").toHex(), Requests.setCustomMode(4).wire())
        assertEquals(hex("4f524742 00000000 8c000000 00000000").toHex(), Requests.requestRescanDevices().wire())
    }

    @Test
    fun `ResizeZone`() {
        assertEquals(
            hex("4f524742 00000000 e8030000 08000000  02000000 1e000000").toHex(),
            Requests.resizeZone(0, 2, 30).wire(),
        )
    }

    private val breathing = DeviceMode(
        name = "Breathing",
        value = 0x13,
        flags = ModeFlags.HAS_SPEED or ModeFlags.HAS_BRIGHTNESS or ModeFlags.HAS_MODE_SPECIFIC_COLOR,
        speedMin = 1,
        speedMax = 5,
        brightnessMin = 0,
        brightnessMax = 100,
        colorsMin = 1,
        colorsMax = 1,
        speed = 3,
        brightness = 80,
        direction = ModeDirection.LEFT,
        colorMode = ColorMode.MODE_SPECIFIC,
        colors = ColorArray.of(intArrayOf(0xFF8000)),
    )

    @Test
    fun `UpdateMode with protocol 3 includes value and brightness`() {
        assertEquals(
            hex(
                """
                4f524742 00000000 4d040000 4a000000 // UPDATEMODE (1101), 74 bytes
                4a000000                 // data_size = 74
                02000000                 // mode_idx = 2
                0a00 427265617468696e6700 // "Breathing"
                13000000                 // value (protocol < 6 only)
                51000000                 // flags = speed | brightness | mode specific color
                01000000 05000000        // speed min/max
                00000000 64000000        // brightness min/max (protocol 3+)
                01000000 01000000        // colors min/max
                03000000                 // speed
                50000000                 // brightness (protocol 3+)
                00000000                 // direction
                02000000                 // color_mode = MODE_SPECIFIC
                0100 ff800000            // one color: orange
                """,
            ).toHex(),
            Requests.updateMode(0, 2, breathing, 3).wire(),
        )
    }

    @Test
    fun `UpdateMode with protocol 2 has no brightness and protocol 6 has no value`() {
        val v2 = Requests.updateMode(0, 2, breathing, 2).payload
        val v3 = Requests.updateMode(0, 2, breathing, 3).payload
        val v6 = Requests.updateMode(0, 2, breathing, 6).payload
        assertEquals(v3.size - 12, v2.size)
        assertEquals(v3.size - 4, v6.size)
        // data_size always equals the payload size.
        listOf(v2, v3, v6).forEach { assertEquals(it.size, LittleEndianReader(it).i32()) }
        // Decoding with the same version yields the mode again (value is dropped for v6).
        assertEquals(breathing, DeviceCodec.decodeMode(LittleEndianReader(v3, 8), 3))
        assertEquals(breathing.copy(value = 0), DeviceCodec.decodeMode(LittleEndianReader(v6, 8), 6))
    }

    @Test
    fun `SaveMode needs protocol 3`() {
        assertFailsWith<IllegalArgumentException> { Requests.saveMode(0, 0, breathing, 2) }
        assertEquals(PacketId.RGBCONTROLLER_SAVEMODE, Requests.saveMode(0, 0, breathing, 3).packetId)
    }

    @Test
    fun `UpdateZoneMode with -1 resets the zone to the device mode`() {
        assertEquals(
            hex("4f524742 00000000 4f040000 0c000000  0c000000 01000000 ffffffff").toHex(),
            Requests.updateZoneMode(0, 1, -1, null, 6).wire(),
        )
        assertFailsWith<IllegalArgumentException> { Requests.updateZoneMode(0, 1, -1, null, 5) }
    }
}
