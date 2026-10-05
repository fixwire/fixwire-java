plugins {
    alias(libs.plugins.spring.boot)
}

description = "Example: a Spring Boot shop reporting to Fixwire"

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":fixwire-servlet"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(testFixtures(project(":fixwire")))
    testRuntimeOnly(libs.junit.launcher)
}

// Spring Boot 4 needs Java 17.
tasks.named<JavaCompile>("compileJava") { options.release.set(17) }
