description = "Fixwire SDK for Java: errors, traces, sessions, cron check-ins and feedback"

plugins {
    `java-test-fixtures`
}

dependencies {
    testFixturesImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.jackson.databind)
}
