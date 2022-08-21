import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    id("com.github.ben-manes.versions") version "0.42.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

repositories {
    mavenCentral()
}

val invoker: Configuration by configurations.creating

dependencies {
    compileOnly("com.google.cloud.functions:functions-framework-api:1.0.4")
    invoker("com.google.cloud.functions.invoker:java-function-invoker:1.1.1")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(platform("com.google.cloud:libraries-bom:26.1.0"))

    implementation("com.google.cloud:google-cloud-storage:2.11.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")

    testImplementation("com.google.cloud.functions:functions-framework-api:1.0.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testImplementation("com.google.truth:truth:1.1.3")
    testImplementation("org.mockito:mockito-core:4.7.0")
    testImplementation("com.google.guava:guava-testlib:31.1-jre")
    testImplementation("org.assertj:assertj-core:3.23.1")
    testImplementation("io.mockk:mockk:1.12.5")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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