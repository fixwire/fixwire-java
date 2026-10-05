description = "Fixwire for java.net.http: outgoing requests as client spans and breadcrumbs, trace headers to your services"

dependencies {
    api(project(":fixwire"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(testFixtures(project(":fixwire")))
}

// java.net.http came with Java 11.
tasks.named<JavaCompile>("compileJava") { options.release.set(11) }
