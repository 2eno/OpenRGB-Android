package io.github.openrgbremote.sdk.protocol

import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.sdk.model.DeviceType
import io.github.openrgbremote.sdk.model.Led
import io.github.openrgbremote.sdk.model.MatrixMap
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import io.github.openrgbremote.sdk.model.Segment
import io.github.openrgbremote.sdk.model.Zone
import io.github.openrgbremote.sdk.model.ZoneType

/**
 * Encoder/decoder for the version-dependent "Device Data" block and its sub-blocks.
 *
 * The field order mirrors `RGBController::GetDeviceDescriptionData()` and friends of
 * OpenRGB 1.1 exactly (verified against the source, not only the markdown docs):
 *
 * Device: type(i32) name vendor[v1+] description version serial location
 *         num_modes(u16) active_mode(i32) modes num_zones(u16) zones num_leds(u16) leds
 *         num_colors(u16) colors [v5+: num_led_display_names(u16) names, flags(u32)]
 *         [v6+: display_name, configuration(u32-length string)]
 * Mode:   name [v<6: value(i32)] flags speed_min speed_max [v3+: brightness_min brightness_max]
 *         colors_min colors_max speed [v3+: brightness] direction color_mode num_colors(u16) colors
 * Zone:   name type(i32) leds_min leds_max leds_count matrix_len(u16) matrix
 *         [v4+: num_segments(u16) segments] [v5+: flags] [v6+: active_mode(i32) num_modes(u16) modes display_name]
 * Segment (v4+): name type(i32) start_idx leds_count [v6+: matrix_len(u16) matrix flags]
 * LED:    name [v<6: value(u32)]
 * Matrix: height(u32) width(u32) height*width u32 cells; omitted when matrix_len == 0
 */
public object DeviceCodec {

    /**
     * Decodes the payload of a `REQUEST_CONTROLLER_DATA` reply: `data_size(u32)` followed by
     * the device data block.
     */
    public fun decodeControllerData(payload: ByteArray, protocolVersion: Int, deviceId: Int, index: Int): OpenRgbDevice {
        val reader = LittleEndianReader(payload)
        val dataSize = reader.u32("data_size")
        if (dataSize > payload.size) {
            throw ProtocolException("Controller data announces $dataSize bytes but packet has ${payload.size}")
        }
        // data_size includes its own 4 bytes; parse only inside that window.
        val window = LittleEndianReader(payload, reader.position, dataSize.toInt().coerceAtLeast(reader.position))
        return decodeDevice(window, protocolVersion, deviceId, index)
    }

    public fun decodeDevice(reader: LittleEndianReader, protocolVersion: Int, deviceId: Int, index: Int): OpenRgbDevice {
        val rawType = reader.i32("device type")
        val name = reader.string16("device name")
        val vendor = if (ProtocolFeatures.hasVendorString(protocolVersion)) reader.string16("vendor") else ""
        val description = reader.string16("description")
        val version = reader.string16("version")
        val serial = reader.string16("serial")
        val location = reader.string16("location")

        val numModes = reader.u16("num_modes")
        val activeMode = reader.i32("active_mode")
        val modes = List(numModes) { decodeMode(reader, protocolVersion) }

        val numZones = reader.u16("num_zones")
        var ledCursor = 0
        val zones = List(numZones) {
            val zone = decodeZone(reader, protocolVersion, ledCursor)
            ledCursor += zone.ledsCount
            zone
        }

        val numLeds = reader.u16("num_leds")
        val leds = List(numLeds) { decodeLed(reader, protocolVersion) }

        val colors = decodeColors(reader)

        var ledDisplayNames = emptyList<String>()
        var flags = 0
        if (ProtocolFeatures.hasLedDisplayNames(protocolVersion)) {
            val count = reader.u16("num_led_display_names")
            ledDisplayNames = List(count) { reader.string16("led display name") }
        }
        if (ProtocolFeatures.hasControllerFlags(protocolVersion)) {
            flags = reader.i32("controller flags")
        }
        var displayName = ""
        var configuration = ""
        if (protocolVersion >= 6) {
            displayName = reader.string16("display name")
            configuration = reader.string32("configuration")
        }

        return OpenRgbDevice(
            id = deviceId,
            index = index,
            type = DeviceType.fromRaw(rawType, protocolVersion),
            rawType = rawType,
            name = name,
            vendor = vendor,
            description = description,
            version = version,
            serial = serial,
            location = location,
            modes = modes,
            activeModeIndex = activeMode,
            zones = zones,
            leds = leds,
            colors = colors,
            ledDisplayNames = ledDisplayNames,
            flags = flags,
            displayName = displayName,
            configuration = configuration,
        )
    }

    /** Color description: `num_colors(u16)` followed by `RGBColor[num_colors]`. */
    public fun decodeColors(reader: LittleEndianReader): ColorArray {
        val count = reader.u16("num_colors")
        val out = IntArray(count)
        for (i in 0 until count) out[i] = reader.rgbColor("color $i")
        return ColorArray.wrap(out)
    }

    public fun decodeMode(reader: LittleEndianReader, protocolVersion: Int): DeviceMode {
        val name = reader.string16("mode name")
        val value = if (ProtocolFeatures.hasModeValue(protocolVersion)) reader.i32("mode value") else 0
        val flags = reader.i32("mode flags")
        val speedMin = reader.u32("speed_min")
        val speedMax = reader.u32("speed_max")
        var brightnessMin = 0L
        var brightnessMax = 0L
        if (ProtocolFeatures.hasModeBrightness(protocolVersion)) {
            brightnessMin = reader.u32("brightness_min")
            brightnessMax = reader.u32("brightness_max")
        }
        val colorsMin = reader.u32("colors_min")
        val colorsMax = reader.u32("colors_max")
        val speed = reader.u32("speed")
        val brightness = if (ProtocolFeatures.hasModeBrightness(protocolVersion)) reader.u32("brightness") else 0L
        val direction = reader.i32("direction")
        val colorMode = reader.i32("color_mode")
        val colors = decodeColors(reader)
        return DeviceMode(
            name = name,
            value = value,
            flags = flags,
            speedMin = speedMin,
            speedMax = speedMax,
            brightnessMin = brightnessMin,
            brightnessMax = brightnessMax,
            colorsMin = colorsMin,
            colorsMax = colorsMax,
            speed = speed,
            brightness = brightness,
            direction = direction,
            colorMode = colorMode,
            colors = colors,
        )
    }

    public fun decodeZone(reader: LittleEndianReader, protocolVersion: Int, startIndex: Int = 0): Zone {
        val name = reader.string16("zone name")
        val rawType = reader.i32("zone type")
        val ledsMin = reader.u32("leds_min")
        val ledsMax = reader.u32("leds_max")
        val ledsCount = reader.u32AsInt("leds_count")
        val matrixMap = decodeMatrixMap(reader)

        var segments = emptyList<Segment>()
        if (ProtocolFeatures.hasSegments(protocolVersion)) {
            val count = reader.u16("num_segments")
            segments = List(count) { decodeSegment(reader, protocolVersion) }
        }
        val flags = if (ProtocolFeatures.hasZoneFlags(protocolVersion)) reader.i32("zone flags") else 0
        var activeMode = -1
        var modes = emptyList<DeviceMode>()
        var displayName = ""
        if (ProtocolFeatures.hasZoneModes(protocolVersion)) {
            activeMode = reader.i32("zone active_mode")
            val count = reader.u16("zone num_modes")
            modes = List(count) { decodeMode(reader, protocolVersion) }
            displayName = reader.string16("zone display name")
        }
        return Zone(
            name = name,
            type = ZoneType.fromRaw(rawType),
            rawType = rawType,
            ledsMin = ledsMin,
            ledsMax = ledsMax,
            ledsCount = ledsCount,
            matrixMap = matrixMap,
            segments = segments,
            flags = flags,
            activeMode = activeMode,
            modes = modes,
            displayName = displayName,
            startIndex = startIndex,
        )
    }

    public fun decodeSegment(reader: LittleEndianReader, protocolVersion: Int): Segment {
        val name = reader.string16("segment name")
        val type = reader.i32("segment type")
        val start = reader.u32("segment start_idx")
        val count = reader.u32("segment leds_count")
        var matrixMap: MatrixMap? = null
        var flags = 0
        if (ProtocolFeatures.hasSegmentMatrixMap(protocolVersion)) {
            matrixMap = decodeMatrixMap(reader)
            flags = reader.i32("segment flags")
        }
        return Segment(name, type, start, count, matrixMap, flags)
    }

    public fun decodeLed(reader: LittleEndianReader, protocolVersion: Int): Led {
        val name = reader.string16("led name")
        val value = if (ProtocolFeatures.hasLedValue(protocolVersion)) reader.u32("led value") else 0L
        return Led(name, value)
    }

    /**
     * Matrix map with its `unsigned short` length prefix. The length is authoritative: we always
     * advance exactly `matrix_len` bytes, even if height*width disagrees, to stay in sync.
     */
    public fun decodeMatrixMap(reader: LittleEndianReader): MatrixMap? {
        val length = reader.u16("matrix_len")
        if (length == 0) return null
        if (length < 8) throw ProtocolException("Matrix map length $length is shorter than its header")
        val end = reader.position + length
        val height = reader.u32AsInt("matrix height")
        val width = reader.u32AsInt("matrix width")
        val available = (length - 8) / 4
        val expected = height.toLong() * width.toLong()
        val map = if (expected == available.toLong()) {
            MatrixMap(height, width, IntArray(available) { reader.i32("matrix cell") })
        } else {
            null // inconsistent map: skip it rather than guess the layout
        }
        reader.skip(end - reader.position, "matrix map remainder")
        return map
    }

    // ---------------------------------------------------------------------------------
    // Encoding (used for UpdateMode/SaveMode payloads and by the test server)
    // ---------------------------------------------------------------------------------

    /** Encodes a full `REQUEST_CONTROLLER_DATA` reply payload (`data_size` + device data). */
    public fun encodeControllerData(device: OpenRgbDevice, protocolVersion: Int): ByteArray {
        val w = LittleEndianWriter(256)
        w.i32(0)
        encodeDevice(w, device, protocolVersion)
        w.patchI32(0, w.size)
        return w.toByteArray()
    }

    public fun encodeDevice(w: LittleEndianWriter, device: OpenRgbDevice, protocolVersion: Int) {
        w.i32(device.rawType)
        w.string16(device.name)
        if (ProtocolFeatures.hasVendorString(protocolVersion)) w.string16(device.vendor)
        w.string16(device.description)
        w.string16(device.version)
        w.string16(device.serial)
        w.string16(device.location)
        w.u16(device.modes.size)
        w.i32(device.activeModeIndex)
        device.modes.forEach { encodeMode(w, it, protocolVersion) }
        w.u16(device.zones.size)
        device.zones.forEach { encodeZone(w, it, protocolVersion) }
        w.u16(device.leds.size)
        device.leds.forEach { encodeLed(w, it, protocolVersion) }
        encodeColors(w, device.colors)
        if (ProtocolFeatures.hasLedDisplayNames(protocolVersion)) {
            w.u16(device.ledDisplayNames.size)
            device.ledDisplayNames.forEach { w.string16(it) }
        }
        if (ProtocolFeatures.hasControllerFlags(protocolVersion)) w.i32(device.flags)
        if (protocolVersion >= 6) {
            w.string16(device.displayName)
            w.string32(device.configuration)
        }
    }

    public fun encodeColors(w: LittleEndianWriter, colors: ColorArray) {
        w.u16(colors.size)
        for (i in 0 until colors.size) w.rgbColor(colors.rgbAt(i))
    }

    public fun encodeMode(w: LittleEndianWriter, mode: DeviceMode, protocolVersion: Int) {
        w.string16(mode.name)
        if (ProtocolFeatures.hasModeValue(protocolVersion)) w.i32(mode.value)
        w.i32(mode.flags)
        w.u32(mode.speedMin)
        w.u32(mode.speedMax)
        if (ProtocolFeatures.hasModeBrightness(protocolVersion)) {
            w.u32(mode.brightnessMin)
            w.u32(mode.brightnessMax)
        }
        w.u32(mode.colorsMin)
        w.u32(mode.colorsMax)
        w.u32(mode.speed)
        if (ProtocolFeatures.hasModeBrightness(protocolVersion)) w.u32(mode.brightness)
        w.i32(mode.direction)
        w.i32(mode.colorMode)
        encodeColors(w, mode.colors)
    }

    public fun encodeZone(w: LittleEndianWriter, zone: Zone, protocolVersion: Int) {
        w.string16(zone.name)
        w.i32(zone.rawType)
        w.u32(zone.ledsMin)
        w.u32(zone.ledsMax)
        w.i32(zone.ledsCount)
        encodeMatrixMap(w, zone.matrixMap)
        if (ProtocolFeatures.hasSegments(protocolVersion)) {
            w.u16(zone.segments.size)
            zone.segments.forEach { encodeSegment(w, it, protocolVersion) }
        }
        if (ProtocolFeatures.hasZoneFlags(protocolVersion)) w.i32(zone.flags)
        if (ProtocolFeatures.hasZoneModes(protocolVersion)) {
            w.i32(zone.activeMode)
            w.u16(zone.modes.size)
            zone.modes.forEach { encodeMode(w, it, protocolVersion) }
            w.string16(zone.displayName)
        }
    }

    public fun encodeSegment(w: LittleEndianWriter, segment: Segment, protocolVersion: Int) {
        w.string16(segment.name)
        w.i32(segment.type)
        w.u32(segment.startIndex)
        w.u32(segment.ledsCount)
        if (ProtocolFeatures.hasSegmentMatrixMap(protocolVersion)) {
            encodeMatrixMap(w, segment.matrixMap)
            w.i32(segment.flags)
        }
    }

    public fun encodeLed(w: LittleEndianWriter, led: Led, protocolVersion: Int) {
        w.string16(led.name)
        if (ProtocolFeatures.hasLedValue(protocolVersion)) w.u32(led.value)
    }

    public fun encodeMatrixMap(w: LittleEndianWriter, map: MatrixMap?) {
        if (map == null) {
            w.u16(0)
            return
        }
        val cells = map.rawCells()
        w.u16(8 + cells.size * 4)
        w.i32(map.height)
        w.i32(map.width)
        cells.forEach { w.i32(it) }
    }
}
