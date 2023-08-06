import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    id("com.github.ben-manes.versions") version "0.47.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

val invoker: Configuration by configurations.creating

dependencies {
    compileOnly("com.google.cloud.functions:functions-framework-api:1.1.0")
    invoker("com.google.cloud.functions.invoker:java-function-invoker:1.3.0")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(platform("com.google.cloud:libraries-bom:26.21.0"))

    implementation("com.google.cloud:google-cloud-storage:2.26.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    testImplementation("com.google.cloud.functions:functions-framework-api:1.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("com.google.guava:guava-testlib:32.1.2-jre")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("io.mockk:mockk:1.13.5")
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_17.majorVersion.toInt())
}

tasks.withType<DependencyUpdatesTask> {
    val preReleaseVersion = "^.*(rc-?\\d*|m\\d+|-Beta)$".toRegex(RegexOption.IGNORE_CASE)
    rejectVersionIf {
        preReleaseVersion.matches(candidate.version)
    }
    gradleReleaseChannel = "current"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.time=ALL-UNNAMED")
}

tasks.register<JavaExec>("runFunction") {
    mainClass.set("com.google.cloud.functions.invoker.runner.Invoker")
    classpath(invoker)
    inputs.files(configurations.runtimeClasspath, sourceSets.main.get().output)
    args(
        "--target", project.findProperty("run.functionTarget") ?: "",
        "--port", project.findProperty("run.port") ?: 8080
    )
    doFirst {
        args("--classpath", files(configurations.runtimeClasspath, sourceSets.main.get().output).asPath)
    }
}

tasks.register<Zip>("zipJar") {
    dependsOn(tasks.shadowJar)

    from(layout.buildDirectory.dir("libs"))
}