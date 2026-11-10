package dev.eno2.openrgbremote.sdk.model

/** Mode flag bits (`MODE_FLAG_*` in `RGBControllerInterface.h`). */
public object ModeFlags {
    public const val HAS_SPEED: Int = 1 shl 0
    public const val HAS_DIRECTION_LR: Int = 1 shl 1
    public const val HAS_DIRECTION_UD: Int = 1 shl 2
    public const val HAS_DIRECTION_HV: Int = 1 shl 3
    public const val HAS_BRIGHTNESS: Int = 1 shl 4
    public const val HAS_PER_LED_COLOR: Int = 1 shl 5
    public const val HAS_MODE_SPECIFIC_COLOR: Int = 1 shl 6
    public const val HAS_RANDOM_COLOR: Int = 1 shl 7
    public const val MANUAL_SAVE: Int = 1 shl 8
    public const val AUTOMATIC_SAVE: Int = 1 shl 9
    public const val REQUIRES_ENTIRE_DEVICE: Int = 1 shl 10
    public const val HAS_DIRECTION_DIAG: Int = 1 shl 11 // protocol 6+
}

/** `MODE_DIRECTION_*` values. Diagonal directions (6-9) exist only with protocol 6+. */
public object ModeDirection {
    public const val LEFT: Int = 0
    public const val RIGHT: Int = 1
    public const val UP: Int = 2
    public const val DOWN: Int = 3
    public const val HORIZONTAL: Int = 4
    public const val VERTICAL: Int = 5
    public const val UP_LEFT: Int = 6
    public const val UP_RIGHT: Int = 7
    public const val DOWN_LEFT: Int = 8
    public const val DOWN_RIGHT: Int = 9
}

/** `MODE_COLORS_*` values. */
public object ColorMode {
    public const val NONE: Int = 0
    public const val PER_LED: Int = 1
    public const val MODE_SPECIFIC: Int = 2
    public const val RANDOM: Int = 3
}

/** Controller flag bits (`CONTROLLER_FLAG_*`, protocol 5+). */
public object ControllerFlags {
    public const val LOCAL: Int = 1 shl 0
    public const val REMOTE: Int = 1 shl 1
    public const val VIRTUAL: Int = 1 shl 2
    public const val HIDDEN: Int = 1 shl 3
}

/** Zone flag bits (`ZONE_FLAG_*`, protocol 5+). */
public object ZoneFlags {
    public const val MANUALLY_CONFIGURABLE_SIZE_EFFECTS_ONLY: Int = 1 shl 0
    public const val MANUALLY_CONFIGURABLE_SIZE: Int = 1 shl 1
}

/** `zone_type`. Values 3-6 are only sent to protocol 6+ clients. */
public enum class ZoneType(public val raw: Int) {
    SINGLE(0),
    LINEAR(1),
    MATRIX(2),
    LINEAR_LOOP(3),
    MATRIX_LOOP_X(4),
    MATRIX_LOOP_Y(5),
    SEGMENTED(6),
    UNKNOWN(-1),
    ;

    public val isMatrix: Boolean get() = this == MATRIX || this == MATRIX_LOOP_X || this == MATRIX_LOOP_Y

    public companion object {
        public fun fromRaw(raw: Int): ZoneType = entries.firstOrNull { it.raw == raw && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * A matrix map: [height] x [width] cells, each holding an LED index relative to the zone
 * or [NO_LED] for an empty cell.
 */
public class MatrixMap(
    public val height: Int,
    public val width: Int,
    private val cells: IntArray,
) {
    init {
        require(cells.size == height * width) { "Matrix map needs ${height * width} cells, got ${cells.size}" }
    }

    /** LED index at [row]/[column] or `null` if the cell is empty. */
    public fun ledAt(row: Int, column: Int): Int? {
        val v = cells[row * width + column]
        return if (v == NO_LED) null else v
    }

    public fun rawCells(): IntArray = cells.copyOf()

    override fun equals(other: Any?): Boolean =
        other is MatrixMap && other.height == height && other.width == width && other.cells.contentEquals(cells)

    override fun hashCode(): Int = (height * 31 + width) * 31 + cells.contentHashCode()

    override fun toString(): String = "MatrixMap(${height}x$width)"

    public companion object {
        /** `0xFFFFFFFF` marks a matrix cell without LED. */
        public const val NO_LED: Int = -1
    }
}

/** One lighting mode of a controller (hardware effect or "Direct"/"Custom"). */
public data class DeviceMode(
    val name: String,
    /** Internal mode value; only transmitted for protocol < 6 and must be echoed back unchanged. */
    val value: Int = 0,
    val flags: Int = 0,
    val speedMin: Long = 0,
    val speedMax: Long = 0,
    val brightnessMin: Long = 0,
    val brightnessMax: Long = 0,
    val colorsMin: Long = 0,
    val colorsMax: Long = 0,
    val speed: Long = 0,
    val brightness: Long = 0,
    val direction: Int = ModeDirection.LEFT,
    val colorMode: Int = ColorMode.NONE,
    val colors: ColorArray = ColorArray.EMPTY,
) {
    public fun hasFlag(flag: Int): Boolean = flags and flag != 0
    public val hasSpeed: Boolean get() = hasFlag(ModeFlags.HAS_SPEED)
    public val hasBrightness: Boolean get() = hasFlag(ModeFlags.HAS_BRIGHTNESS)
    public val hasPerLedColor: Boolean get() = hasFlag(ModeFlags.HAS_PER_LED_COLOR)
    public val hasModeSpecificColor: Boolean get() = hasFlag(ModeFlags.HAS_MODE_SPECIFIC_COLOR)
    public val hasRandomColor: Boolean get() = hasFlag(ModeFlags.HAS_RANDOM_COLOR)
    public val hasDirection: Boolean
        get() = hasFlag(ModeFlags.HAS_DIRECTION_LR) || hasFlag(ModeFlags.HAS_DIRECTION_UD) ||
            hasFlag(ModeFlags.HAS_DIRECTION_HV) || hasFlag(ModeFlags.HAS_DIRECTION_DIAG)
    public val canSave: Boolean get() = hasFlag(ModeFlags.MANUAL_SAVE)
}

public data class Segment(
    val name: String,
    val type: Int,
    val startIndex: Long,
    val ledsCount: Long,
    val matrixMap: MatrixMap? = null,
    val flags: Int = 0,
)

public data class Led(
    val name: String,
    /** Internal value; only transmitted for protocol < 6. */
    val value: Long = 0,
)

public data class Zone(
    val name: String,
    val type: ZoneType,
    val rawType: Int,
    val ledsMin: Long,
    val ledsMax: Long,
    val ledsCount: Int,
    val matrixMap: MatrixMap? = null,
    val segments: List<Segment> = emptyList(),
    val flags: Int = 0,
    /** Active per-zone mode, -1 = zone follows the device mode (protocol 6+). */
    val activeMode: Int = -1,
    val modes: List<DeviceMode> = emptyList(),
    val displayName: String = "",
    /** Index of this zone's first LED in the device's LED/color arrays. */
    val startIndex: Int = 0,
) {
    val label: String get() = displayName.ifBlank { name }
    val isResizable: Boolean get() = ledsMin != ledsMax
}

/**
 * Snapshot of one RGBController on the server.
 *
 * [id] is the value to put into `pkt_dev_id` for commands: the list index for protocol 0-5,
 * the server-assigned unique id for protocol 6+.
 */
public data class OpenRgbDevice(
    val id: Int,
    val index: Int,
    val type: DeviceType,
    val rawType: Int,
    val name: String,
    val vendor: String = "",
    val description: String = "",
    val version: String = "",
    val serial: String = "",
    val location: String = "",
    val modes: List<DeviceMode> = emptyList(),
    val activeModeIndex: Int = 0,
    val zones: List<Zone> = emptyList(),
    val leds: List<Led> = emptyList(),
    val colors: ColorArray = ColorArray.EMPTY,
    val ledDisplayNames: List<String> = emptyList(),
    val flags: Int = 0,
    val displayName: String = "",
    val configuration: String = "",
) {
    val label: String get() = displayName.ifBlank { name }
    val activeMode: DeviceMode? get() = modes.getOrNull(activeModeIndex)
    val ledCount: Int get() = colors.size

    /** Index of the "Direct" mode (per-LED, software-controlled), falling back to "Custom"/"Static". */
    public fun directModeIndex(): Int? {
        val candidates = listOf("direct", "custom", "static")
        for (wanted in candidates) {
            val idx = modes.indexOfFirst { it.name.equals(wanted, ignoreCase = true) && it.hasPerLedColor }
            if (idx >= 0) return idx
        }
        val anyPerLed = modes.indexOfFirst { it.hasPerLedColor }
        return if (anyPerLed >= 0) anyPerLed else null
    }

    /** Human-readable LED name: display name (protocol 5+) if present, otherwise the LED name. */
    public fun ledLabel(index: Int): String =
        ledDisplayNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: leds.getOrNull(index)?.name ?: "LED ${index + 1}"
}
