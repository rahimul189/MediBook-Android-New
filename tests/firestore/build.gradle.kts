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
    description = "Runs Java client Security Rules tests against an owned, local demo Firestore emulator."
    useJUnit()
    include("**/RulesIntegrationSuite.class")
    maxParallelForks = 1
    outputs.upToDateWhen { false } // Never reuse a cached integration-test success.
    systemProperty("medibook.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("medibook.rulesTestBuild", layout.buildDirectory.get().asFile.absolutePath)
    reports.html.required = false // XML results only; no generated JavaScript report assets.
    testLogging { events("passed", "skipped", "failed") }
}
