import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Conventions shared by every module. Module-specific wiring lives in each module's build.gradle.kts.

// JDK-8369227: virtual threads can stay parked for seconds on 25.0.0-25.0.2 and 26.0.0 (fixed in 25.0.3, 26.0.1, 27;
// JDK 21 and 24 are not affected). Tests and benchmarks of a virtual-thread proxy are unreliable on those builds.
System.getProperty("java.version").let { v ->
    if (Regex("^(25(\\.0\\.[0-2])?|26(\\.0\\.0)?)([^0-9.].*)?$").matches(v)) {
        logger.warn("WARNING: JDK $v is affected by JDK-8369227 (virtual threads can stall for seconds). Use JDK 21, 24, 25.0.3+ or 26.0.1+; see docs/adr/0001-http-stack.md.")
    }
}

allprojects {
    group = "dev.routeplane"
    version = "0.1.0-SNAPSHOT"

    // Lock every resolvable configuration. Lockfiles are generated with:
    //   ./gradlew resolveAndLockAll --write-locks
    // and must be committed (see docs/development.md). Without lockfiles Gradle resolves the exact versions
    // pinned in gradle/libs.versions.toml; none of them use version ranges.
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // --release (not toolchains): any JDK >= 21 can build, bytecode and API are pinned to Java 21.
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "768m"
        // JVM-wide limits of the JDK HTTP server and client (see docs/adr/0001-http-stack.md).
        jvmArgs(
            "-Djdk.httpclient.allowRestrictedHeaders=host",
            "-Dsun.net.httpserver.maxReqHeaderSize=65536",
            "-Dsun.net.httpserver.maxReqTime=20",
            "-Dsun.net.httpserver.nodelay=true",
            "-Dfile.encoding=UTF-8"
        )
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
