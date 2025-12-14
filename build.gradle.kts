plugins {
    java
}

group = "dev.chang"
version = "1.21.8"

java {
    // build with java 21 to match your paper 1.21.x runtime
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    // default dependency repository
    mavenCentral()

    // papermc repository for paper api snapshots
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // compile only because the server provides the paper api at runtime
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

tasks.processResources {
    // replace placeholders in plugin.yml during resource processing
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    // set output jar name and version explicitly
    archiveBaseName.set("SimplePlaytimeLimiter")
    archiveVersion.set(project.version.toString())

    // avoid failing the build if duplicate resources appear
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
