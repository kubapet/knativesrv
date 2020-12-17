import Severity.*
import TextColors.*
import kotlinx.cinterop.*
import kotlinx.datetime.Clock
import platform.posix.*

enum class TextColors(private val value: String) {
    DefaultTxt("0"),
    BrightRedTxt("31;1"),
    BrightGreenTxt("32;1"),
    BrightYellowTxt("33;1"),
    BrightWhiteTxt("37;1");

    override fun toString(): String = "\u001B[${value}m"
}

enum class Severity {
    INFO, WARN, ERROR, SEVERE, DEBUG
}

data class LogRecord(val severity: Severity, val message: Any, val context: Any, var color: TextColors? = null)

class Logger(
    private val formatter: LogRecord.() -> String = {
        val formattedMessage = "[${Clock.System.now()}] [${severity.name}] [$context] $message"
        if (color != null) "$color$formattedMessage$DefaultTxt\n" else "$formattedMessage\n"
    },
    private val defaultSeverity: Severity = INFO,
    private val defaultContext: String = "main",
    private val useErr: Boolean = true,
    private val std: CValuesRef<FILE>? = stdout,
    private val err: CValuesRef<FILE>? = stderr
) {

    operator fun invoke(
        severity: Severity? = null,
        message: Any = "",
        context: Any? = null,
        forcedColor: TextColors? = null
    ) {
        val actualSeverity = severity ?: defaultSeverity
        val color = forcedColor ?: when(actualSeverity) {
            INFO -> BrightGreenTxt
            WARN -> BrightYellowTxt
            ERROR -> BrightRedTxt
            SEVERE -> BrightRedTxt
            DEBUG -> BrightWhiteTxt
        }
        val logRecord = LogRecord(actualSeverity, message, context ?: defaultContext, color)
        val output = if(useErr && severity in listOf(ERROR, SEVERE)) err else std
        if (output != null) {
            fprintf(output, formatter(logRecord))
            fflush(output)
        }
    }
}