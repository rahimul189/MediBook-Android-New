plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.medibook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.medibook"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Offline resource checks can opt out of account/reminder Application initialization.
        testInstrumentationRunner = providers.gradleProperty("medibook.uiTestRunner")
            .orElse("androidx.test.runner.AndroidJUnitRunner").get()
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.glide)

    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
