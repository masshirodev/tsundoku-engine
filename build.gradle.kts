import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.shadowjar) apply false
}

allprojects {
    group = "mangayomi"
    version = "1.0"

    repositories {
        mavenCentral()
        google()
        // A commit of the android-jar branch, not the branch: Suwayomi regenerates
        // android-jar 1.0.0 in place as its AndroidCompat takes classes over, so a
        // fresh resolve of the branch drops stubs this fork's AndroidCompat still
        // compiles against (doc/FORK.md). Move the pin with the classes it needs.
        maven("https://github.com/Suwayomi/Suwayomi-Server/raw/7488b5cd498c0ee945c41d0b07205ec8f1e21314/")
        maven("https://jogamp.org/deployment/maven")
        maven("https://maven.google.com/")
        maven("https://jitpack.io")
        maven("https://dl.google.com/dl/android/maven2/")
        maven("https://repo1.maven.org/maven2/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

val projects = listOf(
    project(":AndroidCompat"),
    project(":AndroidCompat:Config"),
    project(":server"),
)

configure(projects) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    dependencies {
        implementation(rootProject.libs.bundles.common)
    }
}
