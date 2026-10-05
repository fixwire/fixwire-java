description = "Fixwire for Logback: log records as breadcrumbs and events"

dependencies {
    api(project(":fixwire"))
    compileOnly(libs.logback.classic)

    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(testFixtures(project(":fixwire")))
}

// Logback 1.5+ needs Java 11.
tasks.named<JavaCompile>("compileJava") { options.release.set(11) }
