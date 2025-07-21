import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.github.ben-manes.versions") version "0.52.0"
    id("com.gradleup.shadow") version "8.3.8"
}

repositories {
    mavenCentral()
}

val ktorVersion: String by project
val invoker: Configuration by configurations.creating

dependencies {
    compileOnly("com.google.cloud.functions:functions-framework-api:1.1.4")
    invoker("com.google.cloud.functions.invoker:java-function-invoker:1.4.1")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(platform("com.google.cloud:libraries-bom:26.63.0"))

    implementation("com.google.cloud:google-cloud-storage:2.53.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    testImplementation("com.google.cloud.functions:functions-framework-api:1.1.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.3")
    testImplementation("com.google.guava:guava-testlib:33.4.8-jre")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("io.mockk:mockk:1.14.5")

    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_21.majorVersion.toInt())
}

tasks.withType<DependencyUpdatesTask> {
    val preReleaseVersion = "^.*(rc-?\\d*|m\\d+|-[Bb]eta-?\\d*)$".toRegex(RegexOption.IGNORE_CASE)
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
