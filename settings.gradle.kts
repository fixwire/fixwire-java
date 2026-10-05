plugins {
    // Downloads the JDK the build runs on (gradle/gradle-daemon-jvm.properties) when it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fixwire-java"

include("fixwire")
