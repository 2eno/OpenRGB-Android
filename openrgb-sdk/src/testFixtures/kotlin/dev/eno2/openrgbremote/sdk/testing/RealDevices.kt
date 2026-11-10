package dev.eno2.openrgbremote.sdk.testing

import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.protocol.DeviceCodec

/**
 * Devices decoded from packets captured from a real OpenRGB 1.0 server with debug devices
 * (see tools/capture_fixtures.py). Useful for UI tests and previews with realistic structure.
 */
object RealDevices {
    private const val DIR = "/fixtures/openrgb-1.0"

    fun bytes(name: String): ByteArray =
        requireNotNull(RealDevices::class.java.getResourceAsStream("$DIR/$name")) { "Missing fixture $name" }.use { it.readBytes() }

    /** All six controllers in protocol [version], with ids/indices as the server reported them. */
    fun all(version: Int = 6): List<OpenRgbDevice> = (0..5).map { index ->
        DeviceCodec.decodeControllerData(bytes("v${version}_device$index.bin"), version, index, index)
    }
}
