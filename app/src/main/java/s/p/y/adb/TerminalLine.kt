package s.p.y.adb

enum class LineType {
    PROMPT,
    STDOUT,
    STDERR,
    INFO,
    SUCCESS,
    ERROR
}

data class TerminalLine(
    val text: String,
    val type: LineType,
    val timestamp: Long = System.currentTimeMillis()
)
