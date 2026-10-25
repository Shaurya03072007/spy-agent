package s.p.y.adb

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

class AdbManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AdbManager"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val PREFS_NAME = "adb_settings"
        const val KEY_LAST_HOST = "last_host"
        const val KEY_LAST_PORT = "last_port"
        const val KEY_LAST_PAIR_PORT = "last_pair_port"

        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private var activeTarget: String? = null

    init {
        logInfo("AdbManager initialized on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        ensureAdbBinary()
    }

    /**
     * Resolves and prepares the native libadb.so executable.
     */
    fun ensureAdbBinary(): File {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libadb.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }

        // Fallback: check if local copy in files directory is needed
        val localBin = File(context.filesDir, "bin/libadb.so")
        if (!localBin.exists() || localBin.length() != nativeLib.length()) {
            if (nativeLib.exists()) {
                try {
                    localBin.parentFile?.mkdirs()
                    nativeLib.copyTo(localBin, overwrite = true)
                    localBin.setExecutable(true, false)
                    localBin.setReadable(true, false)
                    Log.i(TAG, "Copied libadb.so to local files directory: ${localBin.absolutePath}")
                    return localBin
                } catch (e: Exception) {
                    Log.e(TAG, "Failed copying libadb to filesDir", e)
                }
            }
        }
        if (localBin.exists() && localBin.canExecute()) {
            return localBin
        }

        return nativeLib
    }

    /**
     * Executes adb command safely by redirecting output to a temporary file.
     * This avoids pipe-locking issues when child daemons are spawned.
     */
    suspend fun executeRaw(
        args: List<String>,
        stdinInput: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): AdbResult = withContext(Dispatchers.IO) {
        val adbBinary = ensureAdbBinary()
        val startTime = System.currentTimeMillis()
        val fullCommand = listOf(adbBinary.absolutePath) + args
        val commandDisplay = "adb ${args.joinToString(" ")}"

        Log.d(TAG, "Executing: $commandDisplay")

        val homeDir = context.filesDir
        val cacheDir = context.cacheDir

        // Ensure key directory exists
        File(homeDir, ".android").mkdirs()

        var tempOutputFile: File? = null

        try {
            tempOutputFile = File.createTempFile("adb_cmd_", ".txt", cacheDir)

            val processBuilder = ProcessBuilder(fullCommand)
                .directory(homeDir)
                .redirectErrorStream(true)
                .redirectOutput(tempOutputFile)

            val env = processBuilder.environment()
            env["HOME"] = homeDir.absolutePath
            env["TMPDIR"] = cacheDir.absolutePath
            env["PATH"] = "${context.applicationInfo.nativeLibraryDir}:/system/bin:/system/xbin"

            val process = processBuilder.start()

            // If input is provided, write to stdin (e.g. for pairing code)
            if (stdinInput != null) {
                try {
                    PrintStream(process.outputStream).use { writer ->
                        writer.println(stdinInput)
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val duration = System.currentTimeMillis() - startTime

            val outputText = if (tempOutputFile.exists()) {
                tempOutputFile.readText().trim()
            } else {
                ""
            }

            if (!finished) {
                process.destroyForcibly()
                return@withContext AdbResult(
                    exitCode = -1,
                    stdout = outputText,
                    stderr = "Execution timed out after ${timeoutSeconds}s",
                    durationMs = duration,
                    command = commandDisplay
                )
            }

            val exitCode = process.exitValue()
            process.destroyForcibly()

            AdbResult(
                exitCode = exitCode,
                stdout = outputText,
                stderr = if (exitCode != 0) outputText else "",
                durationMs = duration,
                command = commandDisplay
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error executing adb command: $commandDisplay", e)
            AdbResult(
                exitCode = -1,
                stdout = "",
                stderr = "Exception: ${e.message}",
                durationMs = duration,
                command = commandDisplay
            )
        } finally {
            try {
                tempOutputFile?.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * Pair with a wireless debugging target.
     * Usage: adb pair <host>:<port> with code piped to stdin.
     */
    suspend fun pair(host: String, port: Int, pairingCode: String): AdbResult {
        val target = "$host:$port"
        _connectionState.value = ConnectionState.Pairing(host, port)
        appendLine("adb pair $target", LineType.PROMPT)

        // Pass pairingCode both as CLI argument and via stdin for maximum compatibility
        val cleanCode = pairingCode.trim()
        val result = executeRaw(
            args = listOf("pair", target, cleanCode),
            stdinInput = cleanCode,
            timeoutSeconds = 20
        )

        val output = result.combinedOutput
        if (result.isSuccess || output.contains("Successfully paired", ignoreCase = true)) {
            appendLine(output.ifBlank { "Successfully paired to $target" }, LineType.SUCCESS)
            prefs.edit().putInt(KEY_LAST_PAIR_PORT, port).apply()
            _connectionState.value = ConnectionState.Disconnected
        } else {
            appendLine(output.ifBlank { "Pairing failed (Exit code: ${result.exitCode})" }, LineType.ERROR)
            _connectionState.value = ConnectionState.Error("Pairing failed: $output")
        }

        return result
    }

    /**
     * Connect to a wireless debugging target.
     * Usage: adb connect <host>:<port>
     */
    suspend fun connect(host: String, port: Int, autoSwitchToTcpip5555: Boolean = true): AdbResult {
        val target = "$host:$port"
        _connectionState.value = ConnectionState.Connecting(host, port)
        appendLine("adb connect $target", LineType.PROMPT)

        val result = executeRaw(listOf("connect", target), timeoutSeconds = 15)

        val output = result.combinedOutput
        if (output.contains("connected to", ignoreCase = true) || output.contains("already connected", ignoreCase = true)) {
            activeTarget = target
            prefs.edit()
                .putString(KEY_LAST_HOST, host)
                .putInt(KEY_LAST_PORT, port)
                .apply()

            // Fetch device model
            val deviceModelResult = executeRaw(listOf("-s", target, "shell", "getprop", "ro.product.model"), timeoutSeconds = 5)
            val modelName = if (deviceModelResult.isSuccess && deviceModelResult.stdout.isNotBlank()) {
                deviceModelResult.stdout.trim()
            } else {
                "Android Device"
            }

            appendLine("Connected to $target ($modelName)", LineType.SUCCESS)
            _connectionState.value = ConnectionState.Connected(host, port, modelName)

            // If we connected on an ephemeral wireless debugging port (not 5555),
            // auto-enable tcpip 5555 so user can use ADB locally without Wi-Fi!
            if (autoSwitchToTcpip5555 && port != 5555) {
                appendLine("[Offline Mode] Switching adbd to TCP/IP 5555 (No Wi-Fi required!)...", LineType.INFO)
                enableTcpip5555(target)
            }
        } else {
            appendLine(output.ifBlank { "Connection failed (Exit code: ${result.exitCode})" }, LineType.ERROR)
            _connectionState.value = ConnectionState.Error("Failed to connect to $target")
        }

        return result
    }

    /**
     * Restarts adbd daemon in TCP/IP mode on port 5555.
     * This allows connecting via 127.0.0.1:5555 even when Wi-Fi is turned off.
     */
    suspend fun enableTcpip5555(target: String? = null): AdbResult {
        val tgt = target ?: activeTarget
        val args = if (tgt != null) {
            listOf("-s", tgt, "tcpip", "5555")
        } else {
            listOf("tcpip", "5555")
        }

        appendLine("adb ${args.joinToString(" ")}", LineType.PROMPT)
        val result = executeRaw(args, timeoutSeconds = 10)
        val output = result.combinedOutput

        if (output.contains("restarting in TCP mode", ignoreCase = true) || result.isSuccess) {
            appendLine("✔ Device adbd restarted in TCP mode port: 5555", LineType.SUCCESS)
            appendLine("Switching to persistent 127.0.0.1:5555 (Offline Mode)...", LineType.INFO)

            // Disconnect stale connections cleanly
            try {
                executeRaw(listOf("disconnect"), timeoutSeconds = 3)
            } catch (_: Exception) {}

            // Delay for adbd to restart and bind socket
            kotlinx.coroutines.delay(2500)

            // Connect to loopback 127.0.0.1:5555 without recursively triggering auto-switch
            connect("127.0.0.1", 5555, autoSwitchToTcpip5555 = false)
        } else {
            appendLine("TCP/IP 5555 result: $output", if (result.isSuccess) LineType.INFO else LineType.ERROR)
        }

        return result
    }

    /**
     * Switch device adbd back to USB mode.
     */
    suspend fun switchUsb(target: String? = null): AdbResult {
        val tgt = target ?: activeTarget
        val args = if (tgt != null) {
            listOf("-s", tgt, "usb")
        } else {
            listOf("usb")
        }

        appendLine("adb ${args.joinToString(" ")}", LineType.PROMPT)
        val result = executeRaw(args, timeoutSeconds = 10)
        appendLine(result.combinedOutput.ifBlank { "Switched to USB mode" }, LineType.INFO)
        return result
    }

    /**
     * Disconnect from current or specific wireless debugging target.
     */
    suspend fun disconnect(host: String? = null, port: Int? = null): AdbResult {
        val target = if (host != null && port != null) "$host:$port" else activeTarget ?: "127.0.0.1:5555"
        appendLine("adb disconnect $target", LineType.PROMPT)

        val result = executeRaw(listOf("disconnect", target), timeoutSeconds = 5)
        appendLine(result.combinedOutput.ifBlank { "Disconnected from $target" }, LineType.INFO)

        activeTarget = null
        _connectionState.value = ConnectionState.Disconnected
        return result
    }

    /**
     * Execute a shell command on the currently connected device.
     */
    suspend fun runShell(command: String): AdbResult {
        val target = activeTarget
        val targetPrefix = target ?: "localhost"
        appendLine("$targetPrefix $ $command", LineType.PROMPT)

        val args = if (target != null) {
            listOf("-s", target, "shell", command)
        } else {
            listOf("shell", command)
        }

        val result = executeRaw(args)

        if (result.stdout.isNotBlank()) {
            appendLine(result.stdout, LineType.STDOUT)
        }

        val statusInfo = "[Exit: ${result.exitCode} | Took: ${result.durationMs}ms]"
        if (result.isSuccess) {
            appendLine(statusInfo, LineType.INFO)
        } else {
            appendLine(statusInfo, LineType.ERROR)
        }

        return result
    }

    /**
     * Run arbitrary adb command (e.g. "devices", "install", etc.)
     */
    suspend fun runAdb(vararg args: String): AdbResult {
        appendLine("adb ${args.joinToString(" ")}", LineType.PROMPT)
        val result = executeRaw(args.toList())

        if (result.stdout.isNotBlank()) {
            appendLine(result.stdout, LineType.STDOUT)
        }

        return result
    }

    /**
     * Query attached devices via 'adb devices -l'.
     */
    suspend fun getDevices(): List<String> {
        val result = executeRaw(listOf("devices", "-l"), timeoutSeconds = 5)
        if (!result.isSuccess) return emptyList()

        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("List of devices") }
    }

    /**
     * Kills and restarts the background ADB daemon.
     */
    suspend fun restartServer(): AdbResult {
        appendLine("adb kill-server", LineType.PROMPT)
        executeRaw(listOf("kill-server"), timeoutSeconds = 5)
        appendLine("adb start-server", LineType.PROMPT)
        val result = executeRaw(listOf("start-server"), timeoutSeconds = 10)
        appendLine("ADB server restarted", LineType.INFO)
        return result
    }

    fun getLastHost(): String = prefs.getString(KEY_LAST_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun getLastPort(): Int = prefs.getInt(KEY_LAST_PORT, 5555)
    fun getLastPairPort(): Int = prefs.getInt(KEY_LAST_PAIR_PORT, 37000)

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun appendLine(text: String, type: LineType) {
        val newLine = TerminalLine(text, type)
        val current = _terminalLines.value.toMutableList()
        current.add(newLine)
        if (current.size > 1000) {
            _terminalLines.value = current.takeLast(1000)
        } else {
            _terminalLines.value = current
        }
    }

    private fun logInfo(text: String) {
        appendLine(text, LineType.INFO)
    }
}
