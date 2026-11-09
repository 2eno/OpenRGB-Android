package io.github.openrgbremote.sdk.protocol

import io.github.openrgbremote.sdk.Fixtures.hex
import io.github.openrgbremote.sdk.Fixtures.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LittleEndianTest {

    @Test
    fun `strings carry a length including the NUL terminator`() {
        assertEquals("0700" + "44697265637400", LittleEndianWriter().string16("Direct").toByteArray().toHex())
        assertEquals("0100" + "00", LittleEndianWriter().string16("").toByteArray().toHex())
        assertEquals("Direct", LittleEndianReader(hex("0700 44697265637400")).string16())
    }

    @Test
    fun `utf8 strings round trip and length counts bytes not chars`() {
        val bytes = LittleEndianWriter().string16("Lüfter ✓").toByteArray()
        assertEquals(bytes.size - 2, LittleEndianReader(bytes).u16())
        assertEquals("Lüfter ✓", LittleEndianReader(bytes).string16())
    }

    @Test
    fun `reader stops at the first NUL and tolerates a missing terminator`() {
        assertEquals("AB", LittleEndianReader(hex("0400 41420043")).string16())
        assertEquals("AB", LittleEndianReader(hex("0200 4142")).string16())
        assertEquals("", LittleEndianReader(hex("0000")).string16())
    }

    @Test
    fun `invalid utf8 does not throw`() {
        assertEquals("�", LittleEndianReader(hex("0200 ff00")).string16())
    }

    @Test
    fun `RGBColor is serialized as R G B 0`() {
        assertEquals("11223300", LittleEndianWriter().rgbColor(0x112233).toByteArray().toHex())
        assertEquals(0x112233, LittleEndianReader(hex("11223300")).rgbColor())
        // The padding byte is ignored on read.
        assertEquals(0x112233, LittleEndianReader(hex("112233ff")).rgbColor())
    }

    @Test
    fun `integers are little endian`() {
        val w = LittleEndianWriter().u16(0xBEEF).i32(-1).u32(0x80000000L)
        assertEquals("efbe" + "ffffffff" + "00000080", w.toByteArray().toHex())
        val r = LittleEndianReader(w.toByteArray())
        assertEquals(0xBEEF, r.u16())
        assertEquals(-1, r.i32())
        assertEquals(0x80000000L, r.u32())
    }

    @Test
    fun `reading past the end throws ProtocolException`() {
        assertFailsWith<ProtocolException> { LittleEndianReader(hex("0100")).i32() }
        assertFailsWith<ProtocolException> { LittleEndianReader(hex("0500 4142")).string16() }
        assertFailsWith<ProtocolException> { LittleEndianReader(hex("ffffffff")).u32AsInt() }
    }

    @Test
    fun `patchI32 overwrites a placeholder`() {
        val w = LittleEndianWriter().i32(0).u16(7)
        w.patchI32(0, w.size)
        assertEquals("06000000" + "0700", w.toByteArray().toHex())
    }

    @Test
    fun `writer grows beyond its initial capacity`() {
        val w = LittleEndianWriter(1)
        repeat(1000) { w.i32(it) }
        assertEquals(4000, w.size)
        assertEquals(999, LittleEndianReader(w.toByteArray(), 3996).i32())
    }
}
