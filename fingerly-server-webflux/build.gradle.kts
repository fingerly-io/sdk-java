dependencies {
    api(project(":fingerly-server"))
    // Compiled against Spring 5.3, the oldest supported line; the testSpring7
    // suite below runs the same tests against the newest, so one artifact
    // serves Spring Boot 2, 3 and 4.
    compileOnly("org.springframework:spring-webflux:5.3.39")
    testImplementation("org.springframework:spring-webflux:5.3.39")
    testImplementation("io.projectreactor:reactor-test:3.4.41")
    // MockServerWebExchange: Spring's own harness, so the adapter is driven
    // the way the framework drives it.
    testImplementation("org.springframework:spring-test:5.3.39")
    // MockServerWebExchange reaches into spring-context for LocaleContext.
    testRuntimeOnly("org.springframework:spring-context:5.3.39")
}

// The same tests, against Spring 7 (Spring Boot 4) instead of 5.3.
val spring7 = "7.0.10"
testing {
    suites {
        register<JvmTestSuite>("testSpring7") {
            useJUnitJupiter("5.11.4")
            sources { java { setSrcDirs(listOf("src/test/java")) } }
            dependencies {
                implementation(project())
                implementation("org.springframework:spring-webflux:$spring7")
                implementation("org.springframework:spring-test:$spring7")
                implementation("org.springframework:spring-context:$spring7")
                implementation("io.projectreactor:reactor-test:3.8.8")
                implementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
            }
        }
    }
}

// Spring 7 is Java 17 bytecode, so this one suite compiles for 17.
tasks.named<JavaCompile>("compileTestSpring7Java") { options.release.set(17) }
tasks.named("check") { dependsOn("testSpring7") }
