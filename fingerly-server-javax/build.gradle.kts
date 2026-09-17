dependencies {
    api(project(":fingerly-server"))
    // The container supplies the Servlet API; shipping it would collide with
    // whatever the application server already has on the classpath.
    compileOnly("javax.servlet:javax.servlet-api:4.0.1")
    testImplementation("javax.servlet:javax.servlet-api:4.0.1")
}
