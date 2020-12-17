import TextColors.*
import kotlinx.cinterop.*
import libopenssl.SHA256_DIGEST_LENGTH
import platform.posix.*

enum class TextColors(private val value: String) {
    DefaultTxt("0"),
    BrightRedTxt("31;1"),
    BrightGreenTxt("32;1"),
    BrightYellowTxt("33;1"),
    BrightWhiteTxt("37;1");

    override fun toString(): String = "\u001B[${value}m"
}

class Logger(private val std: CValuesRef<FILE>?,private val err: CValuesRef<FILE>?) {
    private fun writeLine(message: String, output: CValuesRef<FILE>?) {
        if (output != null) {
            fprintf(std, "$message\n")
            fflush(std)
        }
    }

    private fun writeColoredLine(message: Any, color: TextColors, output: CValuesRef<FILE>?) =
        writeLine("$color$message$DefaultTxt", output)

    fun info(sender: String, message: String): Logger =
        this.also { writeLine("[$sender] $message", std) }
    fun warn(sender: String, message: String): Logger =
        this.also { writeColoredLine("[$sender] $message", BrightYellowTxt, std) }
    fun error(sender: String, message: String): Logger =
        this.also { writeColoredLine("[$sender] $message", BrightRedTxt, err) }
    fun positive(sender: String, message: String) =
        this.also { writeColoredLine("[$sender] $message", BrightGreenTxt, std) }
    fun debug(sender: String, message: String) =
        this.also { writeColoredLine("[$sender] $message", BrightWhiteTxt, std) }
    fun debugData(data: Any) = this.also { writeColoredLine(data, BrightWhiteTxt, std) }

}