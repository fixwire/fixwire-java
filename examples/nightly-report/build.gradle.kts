plugins {
    application
}

description = "Example: a cron job reporting to Fixwire"

application { mainClass.set("com.example.report.NightlyReport") }

dependencies {
    implementation(project(":fixwire"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(testFixtures(project(":fixwire")))
}
