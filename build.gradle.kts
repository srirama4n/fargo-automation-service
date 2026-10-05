import javax.inject.Inject
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.wf.fargo"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

configurations.named("compileOnly") {
    extendsFrom(configurations.named("developmentOnly").get())
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-mongo:5.16.0")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    val flapdoodle = "de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x:4.18.0"
    val kafkaTest = "org.springframework.kafka:spring-kafka-test"
    // Standalone shades HttpClient so WireMock 3.13 can start on Boot 3.3.
    val wiremock = "org.wiremock:wiremock-standalone:3.13.1"

    developmentOnly(flapdoodle)
    developmentOnly(kafkaTest)
    developmentOnly(wiremock)
    testImplementation(flapdoodle)
    testImplementation(kafkaTest)
    testImplementation(wiremock)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("io.cucumber:cucumber-bom:7.20.1"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-spring")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation("org.junit.platform:junit-platform-suite")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 1
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.wf.fargo.automation.standalone.StandaloneWalkthroughTest")
    }
}

val standaloneWalkthrough = tasks.register<Test>("standaloneWalkthrough") {
    group = "verification"
    description = "Runs the section 13.4 standalone walkthrough in its own JVM."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.wf.fargo.automation.standalone.StandaloneWalkthroughTest")
    }
}

val bddA2a = tasks.register<Test>("bddA2a") {
    group = "verification"
    description = "Runs the BDD feature files with automation.dispatch.mode=a2a."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("automation.it.dispatch-mode", "a2a")
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.wf.fargo.automation.CucumberTest")
    }
}

tasks.named<BootJar>("bootJar") {
    exclude("**/com/wf/fargo/automation/standalone/**")
    exclude("**/org.springframework.boot.env.EnvironmentPostProcessor.imports")
    exclude("**/META-INF/spring.factories")
}

abstract class CheckBootJar : DefaultTask() {
    @get:InputFile
    abstract val archive: RegularFileProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun verify() {
        val forbidden = listOf(
            "com/wf/fargo/automation/standalone/",
            "de.flapdoodle",
            "de/flapdoodle/",
            "spring-kafka-test",
            "wiremock",
        )
        val hits = mutableListOf<String>()
        archiveOperations.zipTree(archive).visit {
            if (!isDirectory) {
                val entryPath = path.replace('\\', '/')
                if (forbidden.any { fragment -> entryPath.contains(fragment) }) {
                    hits.add(entryPath)
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Production bootJar contains standalone classes or embedded libraries:\n" +
                    hits.joinToString("\n"),
            )
        }
    }
}

val checkBootJar = tasks.register<CheckBootJar>("checkBootJar") {
    group = "verification"
    description = "Fails if standalone classes or embedded libraries are inside the production bootJar."
    archive.set(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
}

tasks.named("check") {
    dependsOn(checkBootJar, standaloneWalkthrough, bddA2a)
}
