plugins {
    application
}

dependencies {
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    implementation(libs.hdrhistogram)
    implementation(project(":fault-upstream"))

    // tests validate every scenario's proxy configuration with the real validator and start the real proxy as a child process
    testImplementation(project(":app"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.routeplane.bench.BenchMain")
    applicationName = "routeplane-bench"
    applicationDefaultJvmArgs = listOf("-XX:+UseG1GC", "-Dfile.encoding=UTF-8", "-Djdk.httpclient.allowRestrictedHeaders=host")
}

// The experiment runner launches the proxy and the fault upstreams as separate JVM processes
// (so CPU, RSS and GC can be attributed per process), using their installDist launch scripts.
tasks.named<JavaExec>("run") {
    dependsOn(":app:installDist", ":fault-upstream:installDist")
    workingDir = rootProject.projectDir
}
