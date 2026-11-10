plugins { java }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    testImplementation(libs.junit)
    testImplementation("com.google.code.gson:gson:2.14.0")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    description = "Java-only Rules and SSE tests against an owned local demo RTDB emulator."
    useJUnit()
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    systemProperty("medibook.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("medibook.rtdbTestBuild", layout.buildDirectory.get().asFile.absolutePath)
    reports.html.required = false
    testLogging { events("passed", "skipped", "failed") }
}
