import com.google.protobuf.gradle.id
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "1.8.21"
    id("com.google.protobuf") version "0.9.3"
    id("maven-publish")
}

group = "com.github.moodletracker"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.21")

    api("com.google.protobuf:protobuf-java:3.23.0")
    api("com.google.protobuf:protobuf-kotlin:3.23.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.23.0"
    }
    plugins {
        id("kotlin")
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                kotlin {}
                java {}
                // check if protoc-gen-go is installed
                if (exec {
                        commandLine("bash", "-c", "command -v protoc-gen-go")
                        isIgnoreExitValue = true
                        standardOutput = ByteArrayOutputStream()
                    }.exitValue == 0) {
                    logger.info("protoc-gen-go found, generating Go sources")
                    create("go")
                } else {
                    logger.warn("protoc-gen-go not found, skipping generation of Go sources")
                }
            }
            plugins {
                id("kotlin")
            }
        }
    }
}

// create task that checks if protoc-gen-go is installed
val checkGo = project.tasks.register("checkProtocGenGo", Exec::class.java) {
    commandLine("protoc-gen-go", "--version")
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0) {
            throw Exception("protoc-gen-go not found")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MoodleTracker/Protocol")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}
