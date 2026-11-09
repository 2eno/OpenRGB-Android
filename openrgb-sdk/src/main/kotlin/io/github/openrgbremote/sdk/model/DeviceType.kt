package io.github.openrgbremote.sdk.model

/**
 * `device_type` of an RGBController.
 *
 * OpenRGB appends new types directly before `DEVICE_TYPE_UNKNOWN`, so the raw value of
 * "unknown" grew over time. The table below was derived from `RGBController.h` /
 * `RGBControllerInterface.h` of every release:
 *
 * | Release        | Protocol | Last known type | raw value of UNKNOWN |
 * |----------------|----------|-----------------|----------------------|
 * | 0.3, 0.4       | 0        | HEADSET_STAND   | 10                   |
 * | 0.5            | 1        | LIGHT           | 12                   |
 * | 0.6            | 2        | VIRTUAL         | 14                   |
 * | 0.7            | 3        | STORAGE         | 15                   |
 * | 0.8            | 3        | KEYPAD          | 19                   |
 * | 0.9, 1.0rc1    | 4, 5     | KEYPAD          | 19                   |
 * | 1.0rc2, 1.0+   | 5, 6     | MONITOR         | 21                   |
 *
 * Protocol 3 (0.7 vs. 0.8) and protocol 5 (1.0rc1 vs. rc2) are ambiguous for raw values
 * 15 and 19; [fromRaw] resolves them in favour of the newer release.
 */
public enum class DeviceType(public val raw: Int) {
    MOTHERBOARD(0),
    DRAM(1),
    GPU(2),
    COOLER(3),
    LEDSTRIP(4),
    KEYBOARD(5),
    MOUSE(6),
    MOUSEMAT(7),
    HEADSET(8),
    HEADSET_STAND(9),
    GAMEPAD(10),
    LIGHT(11),
    SPEAKER(12),
    VIRTUAL(13),
    STORAGE(14),
    CASE(15),
    MICROPHONE(16),
    ACCESSORY(17),
    KEYPAD(18),
    LAPTOP(19),
    MONITOR(20),
    UNKNOWN(21),
    ;

    public companion object {
        private val known = entries.filter { it != UNKNOWN }

        /** Raw value that means "unknown" for servers speaking [protocolVersion]. */
        internal fun unknownRawFor(protocolVersion: Int): Int = when (protocolVersion) {
            0 -> 10
            1 -> 12
            2 -> 14
            3, 4 -> 19
            else -> 21
        }

        /** Maps a raw `device_type` to the enum, taking the server's protocol version into account. */
        public fun fromRaw(raw: Int, protocolVersion: Int): DeviceType {
            if (raw < 0 || raw >= unknownRawFor(protocolVersion)) return UNKNOWN
            return known.getOrNull(raw) ?: UNKNOWN
        }
    }
}
