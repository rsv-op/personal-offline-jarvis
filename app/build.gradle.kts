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
            // sherpa-onnx 1.13.8 AAR bundles libsherpa-onnx-jni.so for
            // arm64-v8a and armeabi-v7a. If a transitive AAR also ships
            // an identically-named .so, prefer the sherpa-onnx copy.
            pickFirsts += "**/libsherpa-onnx-jni.so"
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
    // sherpa-onnx 1.13.8 Streaming Zipformer (English, INT8). Replaces
    // the Vosk AAR (PR #27).
    //
    // The Android AAR is published on jitpack.io at
    // `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8@aar`. It bundles
    // the Android-specific `libsherpa-onnx-jni.so` for arm64-v8a and
    // armeabi-v7a, matching the abiFilters above. The AAR's POM does
    // NOT declare transitive dependencies, so the Java classes
    // (OnlineRecognizer, OnlineModelConfig, FeatureConfig, etc.) are
    // NOT inside the AAR — they live in a separate `sherpa-onnx-jvm`
    // jar at the same jitpack coordinates.
    //
    // We list the AAR as `runtimeOnly` so its classes.jar (a subset
    // of the JVM jar, also missing the `Builder` inner classes the
    // new fluent API uses) is NOT in the compile classpath. The JVM
    // jar is the only thing the Kotlin compiler sees, and `runtimeOnly`
    // still flows the AAR's jniLibs (`libsherpa-onnx-jni.so`,
    // `libonnxruntime.so`) and packaging metadata into the APK.
    // At runtime both jars end up in the APK and dex silently accepts
    // the byte-identical duplicates, but the compile-classpath
    // `checkDebugDuplicateClasses` (the only check that actually
    // fails) now sees only the JVM jar.
    runtimeOnly("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8@aar")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:1.13.8") {
        // The AAR's classes.jar is registered by AGP as an internal
        // sub-artifact named `sherpa-onnx-1.13.8-runtime`. Both it
        // and the JVM jar ship AudioEvent and a handful of other
        // com.k2fsa.sherpa.onnx.* classes. The AAR's classes.jar is
        // a subset of the JVM jar and is missing the `Builder` inner
        // classes the new fluent API uses, so the JVM jar is the
        // authoritative source. Excluding the AAR's runtime
        // sub-artifact here drops the AAR's contribution to dex
        // while keeping the AAR's jniLibs (libsherpa-onnx-jni.so
        // for arm64-v8a and armeabi-v7a) and packaging metadata
        // intact (those are on the AAR proper, not on the
        // -runtime sub-artifact).
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-1.13.8-runtime")
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-runtime")
    }
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

// Earlier attempt: a `configurations.all { exclude(group, module) }`
// targeting `sherpa-onnx-1.13.8-runtime` did not work — the AAR's
// classes.jar is not a separately-resolvable module under the
// `com.github.k2-fsa.sherpa-onnx` group, and `packaging { resources {
// excludes } }` only runs at the packaging stage, after the compile
// classpath has already been snapshotted. The fix that works is to
// put the AAR in `runtimeOnly` so its classes.jar is never added to
// the compile classpath in the first place. See the dependency block
// above.
