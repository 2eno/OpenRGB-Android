package dev.eno2.openrgbremote.core.domain.model

/**
 * Whether connecting to [host] counts as local network access in the sense of Android 17's
 * ACCESS_LOCAL_NETWORK permission: private and link-local addresses and `.local` names. Loopback
 * is exempt, public addresses are internet traffic. Other host names cannot be told apart without
 * resolving them; a PC name usually means the home network, so they count as local.
 */
fun isLocalNetworkAddress(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
    if (h.isEmpty() || h == "localhost") return false
    val ipv4 = h.split('.').takeIf { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }?.map { it.toInt() }
    if (ipv4 != null) {
        val (a, b) = ipv4
        return when {
            a == 127 -> false
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
    if (':' in h) {
        return when {
            h == "::1" -> false
            h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") -> true // fe80::/10
            h.startsWith("fc") || h.startsWith("fd") -> true // fc00::/7, unique local
            else -> false
        }
    }
    return true
}
