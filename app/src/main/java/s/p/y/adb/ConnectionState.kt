package s.p.y.adb

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Pairing(val host: String, val port: Int) : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Connected(val host: String, val port: Int, val deviceName: String = "") : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
