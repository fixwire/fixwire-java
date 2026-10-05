description = "Fixwire for Spring Boot: set up from application properties, requests, RestClient and RestTemplate calls, Logback"

val springBoot = "org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"

dependencies {
    api(project(":fixwire-servlet"))
    implementation(project(":fixwire-logback"))
    compileOnly(platform(springBoot))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-webmvc")
    compileOnly("org.springframework.boot:spring-boot-restclient")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")
    annotationProcessor(platform(springBoot))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(platform(springBoot))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation(testFixtures(project(":fixwire")))
    testRuntimeOnly(libs.junit.launcher)
}

// Spring Boot 4 needs Java 17. Its configuration processor reads only some annotations.
tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)
    options.compilerArgs.add("-Xlint:-processing")
}
