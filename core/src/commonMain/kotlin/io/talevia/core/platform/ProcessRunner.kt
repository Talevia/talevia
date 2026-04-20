package io.talevia.core.platform

/**
 * Shell / process execution abstraction for the agent's `bash` tool. Purely
 * for invoking short-lived user-level commands (git / ffmpeg / ls / grep) on
 * a desktop-class OS; this is NOT a job runner, has no streaming output, and
 * is not meant for long-running daemons.
 *
 * commonMain-level interface so `BashTool` can sit in commonMain alongside
 * the filesystem tools. Only implemented on JVM via [JvmProcessRunner].
 * iOS / Android don't register the `bash` tool (no shell in reach of the
 * agent on mobile — same posture as [FileSystem]).
 *
 * Semantics:
 *  - The command string is interpreted by `sh -c` on JVM (POSIX shells),
 *    so shell features (pipes, redirects, `$VAR`, quoting) all work.
 *  - Stdout and stderr are captured separately into strings; the agent
 *    sees both but can tell them apart. Each stream is capped at
 *    [maxOutputBytes] — overflow sets `truncated=true` rather than
 *    blowing the tool-result payload.
 *  - [timeoutMillis] is enforced by force-killing the process tree on
 *    timeout; `timedOut=true` is set on the result.
 *  - Non-zero exit is a normal return, not an exception: the agent gets
 *    stderr + exit code and can decide what to do. We only throw for
 *    impossible-to-start errors (binary not found, invalid workingDir).
 */
interface ProcessRunner {
    suspend fun run(
        command: String,
        workingDir: String? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): ProcessResult

    data class ProcessResult(
        /** Process exit status. `-1` when the process was killed by timeout. */
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        /** True if either stream was truncated to fit [maxOutputBytes]. */
        val truncated: Boolean,
        val durationMillis: Long,
    )

    companion object {
        /** 30 s. Tuned for "quick shell command" — git status, ffprobe, ls. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L

        /**
         * Hard ceiling the tool accepts from the LLM. `ffmpeg` / long renders
         * should not go through `bash` anyway — they have dedicated tools —
         * so the hard cap doubles as a nudge back to the right lane.
         */
        const val HARD_MAX_TIMEOUT_MILLIS: Long = 10 * 60 * 1000L

        /**
         * 128 KB per stream. At roughly 4 bytes per token, the worst-case
         * dual-stream payload fits in ~32k tokens — fine for one turn even
         * before any provider-side truncation.
         */
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 128 * 1024
    }
}
