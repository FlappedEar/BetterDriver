package com.flappedear.betterdriver.setup

/**
 * Where a Wi-Fi GPS receiver ([EquipmentModel.TCP_GNSS]) listens: a host name or IP address and a
 * TCP port. Stored as the equipment address in the form "host:port".
 */
data class TcpAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        private const val MaxPort = 65535

        /** Reads "host:port" or a host and port typed apart; null unless both are usable. */
        fun parse(host: String, port: String): TcpAddress? {
            val h = host.trim()
            val p = port.trim().toIntOrNull()
            val usable = h.isNotEmpty() && h.none { it.isWhitespace() || it == ':' } && p != null && p in 1..MaxPort
            return if (usable) TcpAddress(h, p!!) else null
        }

        fun parse(address: String): TcpAddress? =
            parse(address.substringBeforeLast(':', ""), address.substringAfterLast(':', ""))
    }
}
