import com.google.protobuf.gradle.GenerateProtoTask
import com.google.protobuf.gradle.id
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "1.8.21"
    id("com.google.protobuf") version "0.9.3"
    id("maven-publish")
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:6.6.0.202305301015-r")
    }
}

group = "com.github.moodletracker"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.21")

    api("com.google.protobuf:protobuf-java:3.23.0")
    api("com.google.protobuf:protobuf-kotlin:3.23.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
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
                java {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
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

tasks.register("publishGo") {
    val generateProtoTask = tasks.getByName<GenerateProtoTask>("generateProto")
    dependsOn(checkGo, generateProtoTask)
    doLast {
        val goOutputPath = generateProtoTask.getOutputDir(GenerateProtoTask.PluginOptions("go"))
                ?.let { "$it/github.com/moodletracker" }
            ?: throw Exception("Go output path not found")

        val path = project.findProperty("publish.go.repopath") ?: (project.rootDir.parent + "/go")
        val gitDirectory = file(path)
        if (gitDirectory.exists()) {
            gitDirectory.deleteRecursively()
        }

        val url =
            project.findProperty("publish.go.giturl")?.toString() ?: "https://github.com/MoodleTracker/Protocol-Go.git"

        logger.debug("Cloning Go repository from {} to {}", url, gitDirectory)

        val git = Git.cloneRepository()
            .setDirectory(gitDirectory)
            .setURI(url)
            .call()

        logger.debug("Cloned Go repository to {}", gitDirectory)

        logger.debug("Copying Go sources from {} to {}", goOutputPath, gitDirectory)
        file(goOutputPath).copyRecursively(gitDirectory, true)
        logger.debug("Copied Go sources to {}", gitDirectory)

        git.add().addFilepattern(".").call()
        val commitHash = project.findProperty("publish.go.commit")?.toString()?.let { " (${it})" } ?: ""
        val commit = git.commit()
            .setAuthor("MoodleTracker Bot", "")
            .setSign(false)
            .setMessage("Update Go sources for version ${project.version}$commitHash")
            .call()

        git.checkout().setName("main").call()

        git.merge()
            .include(commit.id)
            .setSquash(true)
            .setStrategy(MergeStrategy.THEIRS)
            .setCommit(false)
            .call()

        git.tag()
            .setName("v${project.version}")
            .setForceUpdate(true)
            .call()

        val credentials = UsernamePasswordCredentialsProvider(
            project.findProperty("publish.go.git-user")?.toString() ?: System.getenv("GIT_USER") ?: throw Exception(
                "No GIT_USER set"
            ),
            project.findProperty("publish.go.git-password")?.toString() ?: System.getenv("GIT_PASSWORD")
            ?: throw Exception(
                "No GIT_PASSWORD set"
            )
        )

        git.push()
            .setCredentialsProvider(credentials)
            .setPushAll()
            .call()
        git.push()
            .setCredentialsProvider(credentials)
            .setPushTags()
            .call()
    }
}
