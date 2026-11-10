package dev.eno2.openrgbremote.sdk.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounds-checked little-endian reader over a byte array.
 *
 * Every read validates the remaining length and throws [ProtocolException] instead of
 * [IndexOutOfBoundsException], so malformed server data never crashes the app.
 * OpenRGB encodes `unsigned int` values as 32 bit; they are returned as [Int] (or [Long]
 * where the full unsigned range matters) because Kotlin has no cheap unsigned arithmetic
 * on the JVM.
 */
public class LittleEndianReader(
    private val data: ByteArray,
    offset: Int = 0,
    private val limit: Int = data.size,
) {
    init {
        require(offset in 0..limit && limit <= data.size) { "Invalid reader window $offset..$limit for ${data.size} bytes" }
    }

    /** Current read position (absolute index into the backing array). */
    public var position: Int = offset
        private set

    public val remaining: Int get() = limit - position

    private fun require(bytes: Int, what: String) {
        if (bytes < 0 || remaining < bytes) {
            throw ProtocolException("Truncated packet while reading $what: need $bytes byte(s), $remaining left")
        }
    }

    public fun u8(what: String = "u8"): Int {
        require(1, what)
        return data[position++].toInt() and 0xFF
    }

    public fun u16(what: String = "u16"): Int {
        require(2, what)
        val v = (data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    public fun i32(what: String = "i32"): Int {
        require(4, what)
        val v = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8) or
            ((data[position + 2].toInt() and 0xFF) shl 16) or
            ((data[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return v
    }

    /** Unsigned 32-bit value widened to [Long]. */
    public fun u32(what: String = "u32"): Long = i32(what).toLong() and 0xFFFF_FFFFL

    /**
     * Unsigned 32-bit count/length that must fit into a non-negative [Int].
     * Used for sizes and counts where values above [Int.MAX_VALUE] are certainly corrupt.
     */
    public fun u32AsInt(what: String = "u32"): Int {
        val v = u32(what)
        if (v > Int.MAX_VALUE) throw ProtocolException("Value $v for $what is out of range")
        return v.toInt()
    }

    public fun bytes(count: Int, what: String = "bytes"): ByteArray {
        require(count, what)
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    public fun skip(count: Int, what: String = "padding") {
        require(count, what)
        position += count
    }

    /**
     * Reads an OpenRGB string: `unsigned short` length (including the NUL terminator)
     * followed by that many bytes.
     */
    public fun string16(what: String = "string"): String {
        val len = u16("$what length")
        return rawString(len, what)
    }

    /** Reads an OpenRGB string with a 32-bit length prefix (used for `configuration`). */
    public fun string32(what: String = "string"): String {
        val len = u32AsInt("$what length")
        return rawString(len, what)
    }

    /** Reads [length] bytes as a NUL-terminated string. */
    public fun rawString(length: Int, what: String = "string"): String {
        require(length, what)
        val start = position
        position += length
        var end = start
        // Stop at the first NUL: the server copies C strings with strcpy, so anything
        // after the terminator is not part of the value.
        while (end < start + length && data[end] != 0.toByte()) end++
        return decodeUtf8(data, start, end - start)
    }

    public fun rgbColor(what: String = "color"): Int {
        require(4, what)
        // RGBColor is 0x00BBGGRR in host order; on the wire (little endian) that is R, G, B, 0.
        val r = data[position].toInt() and 0xFF
        val g = data[position + 1].toInt() and 0xFF
        val b = data[position + 2].toInt() and 0xFF
        position += 4
        return (r shl 16) or (g shl 8) or b
    }

    public companion object {
        internal fun decodeUtf8(data: ByteArray, offset: Int, length: Int): String {
            // Windows builds of OpenRGB may send ANSI code page bytes; decode leniently instead of failing.
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString()
        }
    }
}

/**
 * Growable little-endian writer used to build request payloads.
 */
public class LittleEndianWriter(initialCapacity: Int = 64) {
    private var buffer: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))

    public var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > buffer.size) {
            var newSize = buffer.size * 2
            while (newSize < needed) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }

    public fun u8(value: Int): LittleEndianWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    public fun u16(value: Int): LittleEndianWriter {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        ensure(2)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
        return this
    }

    public fun i32(value: Int): LittleEndianWriter {
        ensure(4)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 24).toByte()
        return this
    }

    public fun u32(value: Long): LittleEndianWriter {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range: $value" }
        return i32(value.toInt())
    }

    public fun bytes(value: ByteArray): LittleEndianWriter {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
        return this
    }

    /** Writes an OpenRGB string with 16-bit length prefix; the length includes the NUL terminator. */
    public fun string16(value: String): LittleEndianWriter {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        u16(encoded.size + 1)
        bytes(encoded)
        return u8(0)
    }

    /** Writes an OpenRGB string with 32-bit length prefix; the length includes the NUL terminator. */
    public fun string32(value: String): LittleEndianWriter {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        i32(encoded.size + 1)
        bytes(encoded)
        return u8(0)
    }

    /** Writes a NUL-terminated string without a length prefix (e.g. client name packet). */
    public fun cString(value: String): LittleEndianWriter {
        bytes(value.toByteArray(StandardCharsets.UTF_8))
        return u8(0)
    }

    /** Writes a 0xRRGGBB color as OpenRGB `RGBColor` (bytes R, G, B, 0). */
    public fun rgbColor(rgb: Int): LittleEndianWriter {
        ensure(4)
        buffer[size++] = (rgb ushr 16).toByte()
        buffer[size++] = (rgb ushr 8).toByte()
        buffer[size++] = rgb.toByte()
        buffer[size++] = 0
        return this
    }

    /** Overwrites a 32-bit value at [offset], e.g. to patch a `data_size` field after writing. */
    public fun patchI32(offset: Int, value: Int) {
        require(offset in 0..(size - 4)) { "patch offset $offset out of range" }
        ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(size)
}
