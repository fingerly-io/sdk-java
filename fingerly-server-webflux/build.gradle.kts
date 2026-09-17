dependencies {
    api(project(":fingerly-server"))
    // Compiled against Spring 5.3, whose org.springframework.web.server types
    // are unchanged in 6.x, so one artifact serves Spring Boot 2 and 3.
    compileOnly("org.springframework:spring-webflux:5.3.39")
    testImplementation("org.springframework:spring-webflux:5.3.39")
    testImplementation("io.projectreactor:reactor-test:3.4.41")
    // MockServerWebExchange: Spring's own harness, so the adapter is driven
    // the way the framework drives it.
    testImplementation("org.springframework:spring-test:5.3.39")
    // MockServerWebExchange reaches into spring-context for LocaleContext.
    testRuntimeOnly("org.springframework:spring-context:5.3.39")
}
