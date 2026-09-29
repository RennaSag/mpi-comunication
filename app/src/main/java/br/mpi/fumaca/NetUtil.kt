package br.mpi.fumaca

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    fun localIp(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !isCellular(it.name) }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .map { nif.name to it.hostAddress!! }
                }
        } catch (_: Exception) {
            emptyList()
        }
        fun rank(name: String) = when {
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 0
            name.startsWith("wlan") -> 1
            else -> 2
        }
        return candidates.minByOrNull { rank(it.first) }?.second
    }

    private fun isCellular(name: String) =
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")
}
