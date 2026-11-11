package dev.eno2.openrgbremote.sdk.protocol

import dev.eno2.openrgbremote.sdk.Fixtures
import dev.eno2.openrgbremote.sdk.Fixtures.hex
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.ColorMode
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.DeviceType
import dev.eno2.openrgbremote.sdk.model.Led
import dev.eno2.openrgbremote.sdk.model.MatrixMap
import dev.eno2.openrgbremote.sdk.model.ModeDirection
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Segment
import dev.eno2.openrgbremote.sdk.model.Zone
import dev.eno2.openrgbremote.sdk.model.ZoneType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

class DeviceCodecTest {

    /** Device using every field any protocol version knows. */
    private val full = OpenRgbDevice(
        id = 7,
        index = 1,
        type = DeviceType.MOUSE,
        rawType = DeviceType.MOUSE.raw,
        name = "Gaming Mouse",
        vendor = "ACME",
        description = "ACME RGB Mouse",
        version = "1.2.3",
        serial = "SN-42",
        location = "HID: /dev/hidraw3",
        modes = listOf(
            DeviceMode(name = "Direct", value = 0xFFFF, flags = ModeFlags.HAS_PER_LED_COLOR, colorMode = ColorMode.PER_LED),
            DeviceMode(
                name = "Wave",
                value = 4,
                flags = ModeFlags.HAS_SPEED or ModeFlags.HAS_DIRECTION_LR or ModeFlags.HAS_BRIGHTNESS or ModeFlags.HAS_MODE_SPECIFIC_COLOR,
                speedMin = 10, speedMax = 1, // OpenRGB allows inverted ranges
                brightnessMin = 0, brightnessMax = 255,
                colorsMin = 1, colorsMax = 2,
                speed = 5, brightness = 200,
                direction = ModeDirection.RIGHT,
                colorMode = ColorMode.MODE_SPECIFIC,
                colors = ColorArray.of(intArrayOf(0x123456, 0xABCDEF)),
            ),
        ),
        activeModeIndex = 1,
        zones = listOf(
            Zone(
                name = "Logo", type = ZoneType.SINGLE, rawType = 0, ledsMin = 1, ledsMax = 1, ledsCount = 1,
                flags = 0, activeMode = -1, displayName = "Logo LED", startIndex = 0,
            ),
            Zone(
                name = "Grid", type = ZoneType.MATRIX, rawType = 2, ledsMin = 4, ledsMax = 4, ledsCount = 4,
                matrixMap = MatrixMap(2, 3, intArrayOf(0, 1, MatrixMap.NO_LED, 2, MatrixMap.NO_LED, 3)),
                segments = listOf(
                    Segment("Left", 1, 0, 2, MatrixMap(1, 2, intArrayOf(0, 1)), flags = 1),
                    Segment("Right", 1, 2, 2, null, flags = 2),
                ),
                flags = 2,
                activeMode = 0,
                modes = listOf(DeviceMode(name = "Zone Static", flags = ModeFlags.HAS_MODE_SPECIFIC_COLOR, colorMode = ColorMode.MODE_SPECIFIC)),
                displayName = "Grid Zone",
                startIndex = 1,
            ),
        ),
        leds = (0 until 5).map { Led("LED $it", value = it.toLong() + 100) },
        colors = ColorArray.of(intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000)),
        ledDisplayNames = (0 until 5).map { "Key $it" },
        flags = 0x2,
        displayName = "My Mouse",
        configuration = "{\"a\":1}",
    )

    /** What a server speaking [version] can transmit of [full]. */
    private fun projected(version: Int): OpenRgbDevice {
        val modeValue = { m: DeviceMode -> if (version < 6) m.value else 0 }
        val project = { m: DeviceMode ->
            m.copy(
                value = modeValue(m),
                brightnessMin = if (version >= 3) m.brightnessMin else 0,
                brightnessMax = if (version >= 3) m.brightnessMax else 0,
                brightness = if (version >= 3) m.brightness else 0,
            )
        }
        return full.copy(
            type = DeviceType.fromRaw(full.rawType, version),
            vendor = if (version >= 1) full.vendor else "",
            modes = full.modes.map(project),
            zones = full.zones.map { z ->
                z.copy(
                    segments = if (version >= 4) z.segments.map { s -> if (version >= 6) s else s.copy(matrixMap = null, flags = 0) } else emptyList(),
                    flags = if (version >= 5) z.flags else 0,
                    activeMode = if (version >= 6) z.activeMode else -1,
                    modes = if (version >= 6) z.modes.map(project) else emptyList(),
                    displayName = if (version >= 6) z.displayName else "",
                )
            },
            leds = full.leds.map { if (version < 6) it else it.copy(value = 0) },
            ledDisplayNames = if (version >= 5) full.ledDisplayNames else emptyList(),
            flags = if (version >= 5) full.flags else 0,
            displayName = if (version >= 6) full.displayName else "",
            configuration = if (version >= 6) full.configuration else "",
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `round trip keeps every field the protocol version supports`(version: Int) {
        val bytes = DeviceCodec.encodeControllerData(full, version)
        val decoded = DeviceCodec.decodeControllerData(bytes, version, full.id, full.index)
        assertEquals(projected(version), decoded)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3, 4, 6])
    fun `every truncation is reported as ProtocolException`(version: Int) {
        val bytes = DeviceCodec.encodeControllerData(full, version)
        for (length in 0 until bytes.size) {
            val truncated = bytes.copyOf(length)
            // Keep data_size consistent with the truncated buffer so the parser walks into the cut.
            if (length >= 4) LittleEndianWriter().i32(length).toByteArray().copyInto(truncated)
            try {
                DeviceCodec.decodeControllerData(truncated, version, 0, 0)
                fail("Truncation to $length bytes was accepted")
            } catch (expected: ProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun `random garbage never escapes as anything but ProtocolException`() {
        val random = Random(1234)
        val real = Fixtures.bytes("v6_device0.bin")
        repeat(2_000) {
            val mutated = real.copyOf()
            repeat(1 + random.nextInt(8)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            try {
                DeviceCodec.decodeControllerData(mutated, 6, 0, 0)
            } catch (e: ProtocolException) {
                // fine
            } catch (e: Throwable) {
                fail("Mutated packet raised ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Every real packet of every protocol version, truncated at every byte and with random bytes
     * changed: decoding either succeeds or throws ProtocolException, never anything else
     * (IndexOutOfBounds, NegativeArraySize, OutOfMemory from a huge count, ...).
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `every real packet survives truncation and mutation`(version: Int) {
        val random = Random(version)
        for (device in 0..5) {
            val real = Fixtures.bytes("v${version}_device$device.bin")
            fun decode(bytes: ByteArray) {
                try {
                    DeviceCodec.decodeControllerData(bytes, version, 0, 0)
                } catch (_: ProtocolException) {
                    // expected for corrupt input
                } catch (e: Throwable) {
                    fail("v$version device $device: ${e::class.simpleName}: ${e.message}")
                }
            }
            for (length in 0 until real.size) decode(real.copyOf(length))
            repeat(300) {
                val mutated = real.copyOf()
                repeat(1 + random.nextInt(6)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
                // Length and count fields are the dangerous ones: set some to their maximum.
                if (random.nextBoolean()) {
                    val at = random.nextInt(mutated.size - 1)
                    mutated[at] = 0xFF.toByte()
                    mutated[at + 1] = 0xFF.toByte()
                }
                decode(mutated)
            }
        }
    }

    @Test
    fun `data_size larger than the packet is rejected`() {
        assertFailsWith<ProtocolException> { DeviceCodec.decodeControllerData(hex("ff000000 00000000"), 0, 0, 0) }
    }

    @Test
    fun `hand written protocol 0 packet`() {
        val payload = hex(
            """
            59000000             // data_size = 89
            02000000             // type = GPU
            0400 47505500        // name "GPU"
            0100 00              // description ""
            0100 00              // version ""
            0100 00              // serial ""
            0100 00              // location ""
            0100                 // num_modes = 1
            00000000             // active_mode = 0
              0700 53746174696300 // "Static"
              01000000           // value = 1
              40000000           // flags = HAS_MODE_SPECIFIC_COLOR
              00000000 00000000  // speed min/max
              01000000 01000000  // colors min/max
              00000000           // speed
              00000000           // direction
              02000000           // color_mode = MODE_SPECIFIC
              0100 ff000000      // mode colors: red
            0000                 // num_zones = 0
            0000                 // num_leds = 0
            0000                 // num_colors = 0
            """,
        )
        assertEquals(payload.size, LittleEndianReader(payload).i32(), "data_size covers the whole payload")
        val device = DeviceCodec.decodeControllerData(payload, 0, 0, 0)
        assertEquals(DeviceType.GPU, device.type)
        assertEquals("GPU", device.name)
        assertEquals("Static", device.modes.single().name)
        assertEquals(1, device.modes.single().value)
        assertEquals(0xFF0000, device.modes.single().colors.rgbAt(0))
        assertNull(device.zones.firstOrNull())
    }

    @Test
    fun `inconsistent matrix map is skipped without losing sync`() {
        val w = LittleEndianWriter()
        w.u16(8 + 4) // claims one cell
        w.i32(2).i32(2) // but 2x2 = 4 cells
        w.i32(0)
        w.u16(0xBEEF) // following field
        val r = LittleEndianReader(w.toByteArray())
        assertNull(DeviceCodec.decodeMatrixMap(r))
        assertEquals(0xBEEF, r.u16())
    }
}
