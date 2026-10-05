// The Fixwire SDK for Java and Kotlin. The libraries run on Java 8 and
// newer (so the core can serve Android too) and depend on nothing; tests
// run on Java 17+.
plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(libs.versions.google.java.format.get())
        }
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.versions.ktlint.get())
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get())
        }
    }

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(if (name.startsWith("compileTest")) 17 else 8)
        options.compilerArgs.addAll(listOf("-Xlint:all,-options,-serial,-try", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
