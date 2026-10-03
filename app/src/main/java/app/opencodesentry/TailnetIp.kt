package app.opencodesentry

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds this device's own address inside the tailnet.
 *
 * Tailscale hands out addresses from the CGNAT range 100.64.0.0/10, which no
 * ordinary Wi-Fi or mobile network uses, so matching that range is enough to
 * pick the tunnel address out of the interface list.
 */
object TailnetIp {

    fun detect(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull(::isTailnet)
    }.getOrNull()

    fun isTailnet(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    /** A short human-readable verdict for the settings screen. */
    fun describe(configured: String): String {
        val detected = detect()
        return when {
            detected == null -> "未检测到（Tailscale 未连接？）"
            configured.isBlank() -> "检测到 $detected（尚未填写）"
            configured == detected -> "$detected ✓ 与配置一致"
            else -> "检测到 $detected，与配置的 $configured 不一致"
        }
    }
}
