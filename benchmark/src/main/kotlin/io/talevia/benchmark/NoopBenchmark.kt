package io.talevia.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/**
 * Smoke benchmark — proves the kotlinx-benchmark infrastructure is wired up
 * end-to-end. Not a real signal; the point is that `:benchmark:benchmark`
 * completes without "no benchmarks found" or plugin-misconfiguration errors.
 *
 * Real baseline benchmarks (agent loop wall-time, ExportTool render +
 * peak RSS) land on the follow-up bullets `debt-add-benchmark-agent-loop`
 * and `debt-add-benchmark-export-tool` once this infra exists.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class NoopBenchmark {

    @Benchmark
    fun noop(): Int = 1 + 1
}
