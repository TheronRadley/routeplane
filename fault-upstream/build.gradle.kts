plugins {
    application
}

dependencies {
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.routeplane.fault.FaultUpstreamMain")
    applicationName = "fault-upstream"
    applicationDefaultJvmArgs = listOf("-XX:+UseG1GC", "-Dfile.encoding=UTF-8")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "routeplane-fault-upstream",
            "Implementation-Version" to project.version
        )
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
