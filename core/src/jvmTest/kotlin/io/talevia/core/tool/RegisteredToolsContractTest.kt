package io.talevia.core.tool

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test: every `*Tool.kt` under `core/src/commonMain/kotlin/io/talevia
 * /core/tool/builtin/` must appear in at least one of the five `AppContainer`
 * files (CLI / Desktop / Server / Android / iOS), so newly-added tools can't
 * silently ship without ever being reachable from any platform's tool registry.
 *
 * History: this invariant was broken at least once before (commit `cb551be`
 * fixed a tool that had been added but never wired). The check is now
 * enforced statically — adding a Tool.kt without a matching `register(...)` /
 * `registry.register(tool: ...)` call in at least one container fails this
 * test. Registering in *one* container is enough to pass; full five-platform
 * coverage is the policy (§3a-8) but tools that intentionally skip some
 * platforms (engine-gated — no Media3 FFmpeg equivalent, etc.) must still
 * live in the one that has the right engine.
 *
 * How it works: walks the filesystem for `*Tool.kt` files, extracts the
 * class name from the file name (Kotlin convention: file name == top-level
 * class name when a file defines a single Tool class), then greps the
 * concatenated container texts for a word-boundary match. Pure string match
 * — no Kotlin reflection — so the test has zero runtime dependency on
 * container wiring succeeding, and runs in milliseconds.
 */
class RegisteredToolsContractTest {

    /**
     * Classes that deliberately never register in any AppContainer. Each
     * entry must document *why* it is exempt so the allowlist doesn't grow
     * into a hiding spot for real "forgot to register" bugs.
     */
    private val testOnlyAllowlist: Map<String, String> = mapOf(
        "EchoTool" to "Trivial smoke / Agent-loop test fixture; intentionally not wired to " +
            "any production AppContainer. Used by tests in core/src/jvmTest/...",
    )

    @Test
    fun everyBuiltinToolIsRegisteredInAtLeastOneAppContainer() {
        val repoRoot = findRepoRoot()
        val builtinDir = repoRoot.resolve("core/src/commonMain/kotlin/io/talevia/core/tool/builtin")
        require(builtinDir.isDirectory) { "builtin tools directory not found at $builtinDir" }

        val toolClassNames: List<String> = builtinDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Tool.kt") }
            .map { it.nameWithoutExtension }
            .toList()

        require(toolClassNames.isNotEmpty()) { "no builtin tool classes found under $builtinDir" }

        val containers = listOf(
            "apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt",
            "apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt",
            "apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt",
            "apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt",
            "apps/ios/Talevia/Platform/AppContainer.swift",
        ).map { repoRoot.resolve(it) }
        containers.forEach { require(it.isFile) { "container file not found: $it" } }
        val mergedRegistrationText = containers.joinToString("\n") { it.readText() }

        val missing = toolClassNames.filter { toolClass ->
            if (testOnlyAllowlist.containsKey(toolClass)) return@filter false
            // Word-boundary match so `ExtractFrameTool` doesn't spuriously match
            // `ExtractFrameToolTest` (even though container files don't import
            // test classes, the belt-and-suspenders is cheap).
            !Regex("\\b${Regex.escape(toolClass)}\\b").containsMatchIn(mergedRegistrationText)
        }

        assertTrue(
            missing.isEmpty(),
            buildString {
                append("The following builtin tool classes are not referenced in any AppContainer ")
                append("(CLI / Desktop / Server / Android / iOS):\n")
                missing.forEach { append("  - $it\n") }
                append("\nAdd a `register(FooTool(...))` (Kotlin containers) or ")
                append("`registry.register(tool: FooTool(...))` (iOS Swift) entry to whichever ")
                append("platforms the tool applies to. If the tool is intentionally never wired ")
                append("(e.g. test-only fixture), add it to `testOnlyAllowlist` with a one-line ")
                append("rationale.")
            },
        )
    }

    /**
     * Walk upward from the current working directory looking for
     * `settings.gradle.kts` — the repo root. Makes the test robust to being
     * invoked from different working dirs (`./gradlew :core:jvmTest` runs
     * from the repo root; IDE test runners may use the module dir).
     */
    private fun findRepoRoot(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("could not find repo root (looked upwards for settings.gradle.kts)")
    }
}
