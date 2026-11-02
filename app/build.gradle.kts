plugins {
    application
}

dependencies {
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    implementation(libs.hdrhistogram)

    // Integration tests drive the proxy against the fault-injection upstream.
    testImplementation(project(":fault-upstream"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.routeplane.Main")
    applicationName = "routeplane"
    // Safe defaults for a proxy: bounded heap, container awareness, predictable GC pauses.
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxRAMPercentage=60",
        "-Dfile.encoding=UTF-8",
        "-Djdk.httpclient.allowRestrictedHeaders=host",
        // the JDK HTTP server leaves Nagle's algorithm on by default, which adds ~40 ms per response (see docs/architecture.md)
        "-Dsun.net.httpserver.nodelay=true"
    )
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "routeplane",
            "Implementation-Version" to project.version
        )
    }
}

// `./gradlew run --args="serve --config config/routeplane.yaml"` is invoked from the repository root,
// so relative config paths must resolve against the root rather than against app/.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
