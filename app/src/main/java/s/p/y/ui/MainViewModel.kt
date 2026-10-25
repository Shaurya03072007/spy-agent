package s.p.y.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import s.p.y.adb.AdbManager
import s.p.y.adb.AdbResult
import s.p.y.adb.ConnectionState
import s.p.y.adb.TerminalLine

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val adbManager = AdbManager.getInstance(application)
    val dnsScanner = s.p.y.adb.AdbDnsScanner(application)

    val connectionState: StateFlow<ConnectionState> = adbManager.connectionState
    val terminalLines: StateFlow<List<TerminalLine>> = adbManager.terminalLines
    val discoveredConnectPort: StateFlow<Int?> = dnsScanner.discoveredConnectPort
    val discoveredPairPort: StateFlow<Int?> = dnsScanner.discoveredPairPort

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<String>>(emptyList())
    val connectedDevices: StateFlow<List<String>> = _connectedDevices.asStateFlow()

    // Command History
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    fun pair(host: String, port: Int, pairingCode: String, onComplete: ((AdbResult) -> Unit)? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = adbManager.pair(host.trim(), port, pairingCode.trim())
            _isBusy.value = false
            onComplete?.invoke(result)
        }
    }

    fun connect(host: String, port: Int, onComplete: ((AdbResult) -> Unit)? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = adbManager.connect(host.trim(), port)
            _isBusy.value = false
            refreshDevices()
            onComplete?.invoke(result)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _isBusy.value = true
            adbManager.disconnect()
            _isBusy.value = false
            refreshDevices()
        }
    }

    fun enableTcpip5555(onComplete: ((AdbResult) -> Unit)? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = adbManager.enableTcpip5555()
            _isBusy.value = false
            refreshDevices()
            onComplete?.invoke(result)
        }
    }

    fun switchUsb(onComplete: ((AdbResult) -> Unit)? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = adbManager.switchUsb()
            _isBusy.value = false
            refreshDevices()
            onComplete?.invoke(result)
        }
    }

    fun executeShell(command: String, onComplete: ((AdbResult) -> Unit)? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        // Add to history
        if (commandHistory.isEmpty() || commandHistory.last() != trimmed) {
            commandHistory.add(trimmed)
        }
        historyIndex = commandHistory.size

        viewModelScope.launch {
            _isBusy.value = true
            val result = adbManager.runShell(trimmed)
            _isBusy.value = false
            onComplete?.invoke(result)
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            val list = adbManager.getDevices()
            _connectedDevices.value = list
        }
    }

    fun restartAdbServer() {
        viewModelScope.launch {
            _isBusy.value = true
            adbManager.restartServer()
            _isBusy.value = false
            refreshDevices()
        }
    }

    fun clearTerminal() {
        adbManager.clearTerminal()
    }

    fun startDnsScan() {
        dnsScanner.startDiscovery()
    }

    fun stopDnsScan() {
        dnsScanner.stopDiscovery()
    }

    fun getPreviousHistoryCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex > 0) {
            historyIndex--
            return commandHistory[historyIndex]
        }
        if (historyIndex == 0) {
            return commandHistory[0]
        }
        return null
    }

    fun getNextHistoryCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex < commandHistory.size - 1) {
            historyIndex++
            return commandHistory[historyIndex]
        } else {
            historyIndex = commandHistory.size
            return ""
        }
    }
}
