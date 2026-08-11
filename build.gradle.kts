plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.servertune"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2. The 26.x scheme replaced the old 1.21.x-R0.1-SNAPSHOT coordinates; pinned
    // to an exact stable build rather than a floating snapshot so the jar is reproducible.
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        // Named so the deprecation is visible rather than a "recompile for details" note.
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("ServerTune-${project.version}.jar")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}