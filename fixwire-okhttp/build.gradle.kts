description = "Fixwire for OkHttp: outgoing requests as client spans and breadcrumbs, trace headers to your services"

dependencies {
    api(project(":fixwire"))
    compileOnly(libs.okhttp)

    testImplementation(libs.okhttp)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(testFixtures(project(":fixwire")))
}
