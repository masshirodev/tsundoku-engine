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

dependencies {
    implementation(project(":server"))
    implementation(libs.bundles.okhttp)
    testImplementation(kotlin("test"))
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
