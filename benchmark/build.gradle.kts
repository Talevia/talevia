plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.kotlin.allopen)
}

// JMH / kotlinx-benchmark requires @Benchmark-carrying classes to be non-final
// so the generated benchmark subclasses can override them. allOpen makes
// classes annotated with @State open, which covers the common benchmark shape.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqldelight.driver.sqlite)
    implementation(libs.okio)
    implementation(libs.okio.fakefilesystem)
}

benchmark {
    // Single JVM target named "main". Gradle tasks land as
    // :benchmark:mainBenchmark and the aggregate :benchmark:benchmark.
    targets {
        register("main")
    }

    configurations {
        named("main") {
            // Keep smoke runs fast — bullet scope is "infrastructure + one
            // noop bench compiles and runs". Full baselines land on the
            // follow-up bullets debt-add-benchmark-agent-loop +
            // debt-add-benchmark-export-tool.
            warmups = 1
            iterations = 2
            iterationTime = 1
            iterationTimeUnit = "sec"
            reportFormat = "json"
        }
    }
}

kotlin {
    jvmToolchain(21)
}
