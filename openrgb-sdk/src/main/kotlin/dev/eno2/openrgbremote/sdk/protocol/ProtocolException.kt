package dev.eno2.openrgbremote.sdk.protocol

import java.io.IOException

/**
 * Thrown when bytes received from the server do not form a valid OpenRGB SDK packet,
 * e.g. wrong magic, truncated data or inconsistent length fields.
 */
public class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)
