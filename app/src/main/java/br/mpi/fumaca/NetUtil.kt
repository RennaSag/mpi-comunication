package br.mpi.fumaca

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** Descobre endereços IP úteis na rede do hotspot. */
object NetUtil {

    /**
     * IP deste celular na rede do hotspot. No aparelho que roteia o hotspot a interface
     * costuma se chamar ap0, swlan0, softap0 ou wlan1; por isso elas têm prioridade.
     */
    fun localIp(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
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
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") -> 3
            else -> 2
        }
        return candidates.minByOrNull { rank(it.first) }?.second
    }

    /** No celular cliente, o gateway do Wi-Fi é o celular que roteia o hotspot. */
    @Suppress("DEPRECATION")
    fun hotspotGateway(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val g = wifi.dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        return "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
    }
}
