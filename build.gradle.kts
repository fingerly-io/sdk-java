plugins {
    java
    `maven-publish`
    signing
}

// Jackson is the only runtime dependency, shared by every module so the
// adapters can never drift onto a different version from the core.
val jacksonVersion = "2.18.2"
val junitVersion = "5.11.4"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // The version itself lives in gradle.properties, the one place a release
    // reads it from.
    group = "io.fingerly"

    repositories { mavenCentral() }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<JavaPluginExtension> {
        // Built on 17, emitted for 11: the docs promise Java 11 and newer, and
        // --release checks against the 11 API rather than only the bytecode
        // version, so a 17-only method cannot slip in unnoticed.
        toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(11)
        options.encoding = "UTF-8"
        // The SDK is a library in somebody else's build. A warning it ships is
        // a warning they cannot fix.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    // Every jar, sources and javadoc included, carries the licence, and the
    // main one a stable module name for the module path.
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Automatic-Module-Name" to project.name.replace("fingerly-server", "io.fingerly.server")
                    .replace("-javax", ".javaxservlet").replace("-", "."),
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The version the proxy reports upstream must be the one published.
        systemProperty("fingerly.version", project.version.toString())
        // Tests read the vendored contract snapshot from the repository root,
        // so they do not depend on the working directory a runner chose.
        systemProperty("fingerly.contract.dir", rootProject.file("contract").absolutePath)
        testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Server-side Fingerly SDK for the JVM")
                    url.set("https://docs.fingerly.io/docs/sdks/java")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("fingerly")
                            name.set("Fingerly")
                            email.set("support@fingerly.io")
                            organization.set("Fingerly")
                            organizationUrl.set("https://fingerly.io")
                        }
                    }
                    scm {
                        url.set("https://github.com/fingerly-io/sdk-java")
                        connection.set("scm:git:https://github.com/fingerly-io/sdk-java.git")
                        developerConnection.set("scm:git:ssh://git@github.com/fingerly-io/sdk-java.git")
                    }
                }
            }
        }
        // A directory, not a remote: scripts/upload-to-central.sh sends this
        // tree to the Central Portal as one bundle, so the four artifacts go
        // live together or not at all, and no build plugin ever holds the
        // Portal token.
        repositories {
            maven {
                name = "release"
                url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
            }
        }
    }

    // Only the release job has a key. Everywhere else publishing still works
    // and simply leaves the artifacts unsigned, and the upload script refuses
    // a tree without signatures.
    val signingKey = providers.environmentVariable("MAVEN_SIGNING_KEY").orNull?.takeIf { it.isNotBlank() }
    if (signingKey != null) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey, providers.environmentVariable("MAVEN_SIGNING_PASSWORD").getOrElse(""))
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        }
    }
}
