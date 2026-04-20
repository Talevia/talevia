package io.talevia.core.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * JVM [ProcessRunner] backed by `ProcessBuilder("sh", "-c", command)`.
 *
 * Stream handling notes:
 *  - stdout and stderr are drained on dedicated threads so the process can't
 *    deadlock by blocking on a full pipe buffer while we `waitFor`.
 *  - Each drainer caps its capture at `maxOutputBytes`; once the cap is hit,
 *    further reads go to /dev/null but the drain keeps running so the
 *    process itself doesn't block.
 *  - On timeout we `destroyForcibly` (SIGKILL on POSIX). We still try to
 *    return what we drained before the kill.
 *
 * Not registered on iOS / Android — `ProcessRunner` stays unimplemented
 * there and the `bash` tool isn't registered in those containers.
 */
class JvmProcessRunner : ProcessRunner {
    override suspend fun run(
        command: String,
        workingDir: String?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ProcessRunner.ProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "command must not be blank" }
        require(timeoutMillis in 1..ProcessRunner.HARD_MAX_TIMEOUT_MILLIS) {
            "timeoutMillis $timeoutMillis out of range [1, ${ProcessRunner.HARD_MAX_TIMEOUT_MILLIS}]"
        }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive (got $maxOutputBytes)" }

        val cwd = workingDir?.let { File(it) }
        if (cwd != null) {
            require(cwd.isAbsolute) { "workingDir must be absolute: $workingDir" }
            require(cwd.isDirectory) { "workingDir is not a directory: $workingDir" }
        }

        val pb = ProcessBuilder("sh", "-c", command).apply {
            if (cwd != null) directory(cwd)
            redirectErrorStream(false)
        }
        val started = System.currentTimeMillis()
        val process = try {
            pb.start()
        } catch (e: Exception) {
            throw IllegalArgumentException("failed to start process: ${e.message}", e)
        }

        val stdoutSink = BoundedSink(maxOutputBytes)
        val stderrSink = BoundedSink(maxOutputBytes)
        val stdoutThread = thread(name = "bash-stdout", isDaemon = true) {
            drainInto(process.inputStream, stdoutSink)
        }
        val stderrThread = thread(name = "bash-stderr", isDaemon = true) {
            drainInto(process.errorStream, stderrSink)
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        val timedOut = !finished
        if (timedOut) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        // Give drainers a brief window to finish copying already-buffered
        // bytes; they hold no lock so joining is safe.
        stdoutThread.join(200)
        stderrThread.join(200)

        val exit = if (finished) process.exitValue() else -1
        val truncated = stdoutSink.truncated || stderrSink.truncated

        ProcessRunner.ProcessResult(
            exitCode = exit,
            stdout = stdoutSink.text(),
            stderr = stderrSink.text(),
            timedOut = timedOut,
            truncated = truncated,
            durationMillis = System.currentTimeMillis() - started,
        )
    }

    private fun drainInto(stream: InputStream, sink: BoundedSink) {
        try {
            stream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, n)
                }
            }
        } catch (_: Exception) {
            // Stream closed / process killed — drainer just exits.
        }
    }

    private class BoundedSink(private val cap: Int) {
        private val buf = ByteArrayOutputStream()
        var truncated: Boolean = false
            private set

        fun write(src: ByteArray, len: Int) {
            val remaining = cap - buf.size()
            if (remaining <= 0) {
                truncated = true
                return
            }
            val take = if (len <= remaining) len else remaining.also { truncated = true }
            buf.write(src, 0, take)
        }

        fun text(): String = buf.toString(Charsets.UTF_8)
    }
}
