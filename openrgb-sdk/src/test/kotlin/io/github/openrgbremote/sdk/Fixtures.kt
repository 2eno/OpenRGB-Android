package io.github.openrgbremote.sdk

/** Loads binary packets captured from a real OpenRGB 1.0 server (see tools/capture_fixtures.py). */
object Fixtures {
    const val OPENRGB_1_0 = "fixtures/openrgb-1.0"

    fun bytes(name: String, dir: String = OPENRGB_1_0): ByteArray {
        val path = "/$dir/$name"
        return requireNotNull(Fixtures::class.java.getResourceAsStream(path)) { "Missing fixture $path" }.use { it.readBytes() }
    }

    /** Parses a hex string with optional whitespace and `//` comments into bytes. */
    fun hex(text: String): ByteArray {
        val clean = text.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Odd number of hex digits" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
