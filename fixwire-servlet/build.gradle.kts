description = "Fixwire for Jakarta Servlet 5+ apps (Spring Boot 3, Jetty, Tomcat, …): requests, their errors, sessions and traces"

dependencies {
  api(project(":fixwire"))
  compileOnly(libs.servlet.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.launcher)
  testImplementation(testFixtures(project(":fixwire")))
  testImplementation(libs.jetty.servlet)
}

// Jakarta Servlet 6 needs Java 11.
tasks.named<JavaCompile>("compileJava") { options.release.set(11) }
