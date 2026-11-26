// The browser target (doc/FORK.md, "The browser target"): the engine run in a
// browser tab by CheerpJ, with no socket. It depends on :server and never
// changes it: the desktop jar (:server:shadowJar) has none of this module's
// classes, its ConnectInterceptor included.

plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
    id(
        libs.plugins.kotlinter
            .get()
            .pluginId,
    )
}

// The slim jar's builder: a build tool, never in the jar it builds.
val slim: SourceSet by sourceSets.creating

dependencies {
    implementation(project(":server"))
    // For the classes this target replaces (QuickJs), compiled against their neighbours.
    implementation(project(":AndroidCompat"))
    implementation(libs.bundles.okhttp)
    testImplementation(kotlin("test"))

    "slimImplementation"(libs.bundles.asm)
    "slimImplementation"(libs.kotlin.stdlib.jdk8)
}

tasks.test {
    useJUnit()
    // The transport's desktop stand-in, in place of the JavaScript native.
    systemProperty("tsundoku.browser.transport", "jdk")
    // Kept in the build folder: on some machines /tmp is memory.
    val tmp = layout.buildDirectory.dir("tmp/test-files")
    doFirst { tmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", tmp.get().asFile.absolutePath)
}

/**
 * Packages extensions are compiled against, kept whole whatever this build
 * was run with (SlimJar.kt). Derived from the classes Keiyoushi extensions
 * of every kind name (plain HTML, JSON APIs, ProtoBuf, QuickJS, factories):
 * `./gradlew :browser:extensionSurface -Pjars=<dir>` lists them for a folder
 * of extension jars, to check this list against.
 */
val extensionApi =
    listOf(
        "eu/kanade/tachiyomi/",
        "android/",
        "androidx/",
        "okhttp3/",
        "okio/",
        "com/squareup/zstd/",
        "org/jsoup/",
        "kotlin/",
        "kotlinx/coroutines/",
        "kotlinx/serialization/",
        "rx/",
        "uy/kohesive/injekt/",
        "app/cash/quickjs/",
        "org/json/",
    )

/** What a tab never runs: not kept, and not followed. */
val neverInBrowser =
    listOf(
        // Chromium's bridge (KCEF, JCEF) and what it draws with.
        "dev/datlag/kcef/",
        "org/cef/",
        "com/jogamp/",
        "jogamp/",
        "com/sun/jna/",
        // The desktop window (`--ui`).
        "mextensionserver/ui/",
        // quickjs4j and Chicory: the browser's own JavaScript engine runs
        // extensions' scripts (BrowserScripts), and only AndroidCompat's
        // QuickJs, which this target replaces, reached these.
        "io/roastedroot/",
        "com/dylibso/",
    )

val droppedResources =
    listOf(
        "font/",
        "res/",
        "resources.arsc",
        "AndroidManifest.xml",
        "native/",
        "jni/",
        "linux-",
        "win32-",
        "darwin-",
        "META-INF/maven/",
        // The desktop window's icon.
        "icon-black.png",
    )

val slimJar by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds engine-browser.jar, the engine's classes a browser tab can load (doc/FORK.md)."
    val inputsList = layout.buildDirectory.file("slim/inputs.txt")
    val out = layout.buildDirectory.file("libs/engine-browser.jar")
    val report = layout.buildDirectory.file("slim/report.txt")
    // This module's own classes first: the first copy of a class wins.
    val classpathIn = files(tasks.jar, configurations.runtimeClasspath)
    inputs.files(classpathIn)
    outputs.file(out)
    outputs.file(report)
    classpath = slim.runtimeClasspath
    mainClass.set("tsundoku.browser.slim.SlimJarKt")
    maxHeapSize = "2g"
    doFirst {
        inputsList.get().asFile.apply {
            parentFile.mkdirs()
            writeText(classpathIn.files.joinToString("\n") { it.absolutePath })
        }
    }
    args(
        buildList {
            add("--out")
            add(out.get().asFile.absolutePath)
            add("--inputs")
            add(inputsList.get().asFile.absolutePath)
            add("--report")
            add(report.get().asFile.absolutePath)
            add("--root-class")
            add("tsundoku/browser/BrowserEngine")
            extensionApi.forEach {
                add("--root-package")
                add(it)
            }
            neverInBrowser.forEach {
                add("--exclude")
                add(it)
            }
            droppedResources.forEach {
                add("--drop-resource")
                add(it)
            }
        },
    )
}

tasks.register<JavaExec>("extensionSurface") {
    group = "help"
    description = "Lists the packages outside an extension that a folder of extension jars names (-Pjars=<dir>)."
    classpath = slim.runtimeClasspath
    mainClass.set("tsundoku.browser.slim.SurfaceKt")
    args(providers.gradleProperty("jars").orElse("").get())
}
