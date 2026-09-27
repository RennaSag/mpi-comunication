package br.mpi.fumaca

import java.net.Inet4Address
import java.net.NetworkInterface

/** Descobre o IP deste celular na rede local (usado no port_name da conexão direta). */
object NetUtil {

    /**
     * IP deste celular na rede do hotspot. No aparelho que roteia o hotspot a interface
     * costuma se chamar ap0, swlan0, softap0 ou wlan1; por isso elas têm prioridade.
     */
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

    /** Dados móveis e VPN não alcançam o outro celular, então não servem de port_name. */
    private fun isCellular(name: String) =
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")
}
