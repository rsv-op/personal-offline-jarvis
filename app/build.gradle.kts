plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.voiceassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.voiceassistant"
        minSdk = 26
        targetSdk = 36

        // CI passes the workflow run number so a newly downloaded APK always installs
        // over the previous one instead of being rejected as a downgrade.
        versionCode = System.getenv("JARVIS_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Phones only: this keeps the bundled native speech/LLM libraries at a
            // sane size instead of shipping emulator ABIs nobody will install.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/liblitertlm_jni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The unit-test JVM defaults to a small heap; the telematics ticker
            // and the Compose render tests both allocate non-trivial memory
            // during a single test class, so give them room to breathe.
            it.jvmArgs = (it.jvmArgs ?: listOf()) + listOf("-Xmx2g")
        }
        // Run unit tests serially. Robolectric + Compose render tests hold
        // the entire Android framework in heap, and running them in parallel
        // exhausts the 7 GB CI runner. Sequential execution finishes slower
        // but is reliable; parallel was breaking JarvisMemoryTest and
        // AssistantScreenTest with OutOfMemoryError.
        unitTests.maxParallelForks = 1
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.0")

    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-android-compiler:2.57.2")
    // Keep Hilt's processor classpath compatible with Kotlin 2.4 metadata.
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Real org.json implementation for plain JVM unit tests (the Android one is a stub).
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
