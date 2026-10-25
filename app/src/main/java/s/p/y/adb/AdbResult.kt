package s.p.y.adb

data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0L,
    val command: String = ""
) {
    val isSuccess: Boolean get() = exitCode == 0
    val combinedOutput: String get() = when {
        stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n$stderr"
        stdout.isNotBlank() -> stdout
        else -> stderr
    }
}
