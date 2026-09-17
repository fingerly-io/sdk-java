dependencies {
    api(project(":fingerly-server"))
    // The container supplies the Servlet API; shipping it would collide with
    // whatever the application server already has on the classpath.
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
}
