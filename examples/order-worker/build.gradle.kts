plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Example: a Kotlin worker handling orders in coroutines, reporting to Fixwire"

application { mainClass.set("com.example.orders.OrderWorkerKt") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.named<JavaCompile>("compileJava") { options.release.set(17) }

dependencies {
    implementation(project(":fixwire-kotlin"))
    implementation(libs.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(testFixtures(project(":fixwire")))
    testImplementation(kotlin("test"))
}
