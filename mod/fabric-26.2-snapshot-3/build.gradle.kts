import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.0-alpha.6"
}

base {
    archivesName.set("debugbridge-26.2-snapshot-3-shell")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    compileOnly(project(":core"))
    minecraft("com.mojang:minecraft:26.2-snapshot-3")
    implementation("net.fabricmc:fabric-loader:0.19.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
