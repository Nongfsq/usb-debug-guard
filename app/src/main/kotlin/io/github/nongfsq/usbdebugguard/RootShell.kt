package io.github.nongfsq.usbdebugguard

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class RootResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = code == 0
}

object RootShell {
    private const val TIMEOUT_MS = 8_000L

    fun run(command: String): RootResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).start()
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return RootResult(-2, "", "Timed out waiting for su")
            }
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText).trim()
            RootResult(process.exitValue(), stdout, stderr)
        } catch (error: Exception) {
            process?.destroyForcibly()
            RootResult(-1, "", error.toString())
        }
    }

    fun sh(command: String): RootResult = run(command)
}
