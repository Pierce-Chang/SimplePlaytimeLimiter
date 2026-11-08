plugins {
    java
}

group = "dev.chang"
version = "26.3"

java {
    // build with java 25 to match your paper 26.x runtime
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
    compileOnly("io.papermc.paper:paper-api:26.3.build.244-stable")
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
