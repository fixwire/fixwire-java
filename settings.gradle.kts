plugins {
    // Downloads the JDK the build runs on (gradle/gradle-daemon-jvm.properties) when it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fixwire-java"

include("fixwire", "fixwire-servlet", "fixwire-kotlin")

// Real apps for the docs, run by their tests against a fake ingest.
include("examples:spring-boot-shop", "examples:nightly-report", "examples:order-worker")
