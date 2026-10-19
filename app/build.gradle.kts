import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider

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
            // The sherpa-onnx `libsherpa-onnx-jni.so` is now pulled
            // out of the AAR by `extractSherpaJniLibs` and lives at
            // `app/build/sherpa-jni/jni/<abi>/`. The litertlm AAR also
            // brings its own JNI libraries; if any of them happen to
            // share a filename, prefer the litertlm copy (it is what
            // the rest of the app is built and tested against).
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

// Private configuration that holds the sherpa-onnx AAR ONLY so its
// jniLibs can be extracted at build time. The AAR is not added to any
// of the regular Android configurations, so its classes.jar never
// reaches the runtime classpath and there is no duplicate with the
// sherpa-onnx-jvm jar that supplies the Java classes.
val sherpaJniLibs: org.gradle.api.artifacts.Configuration by configurations.creating {
    isVisible = false
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Extract `jni/{arm64-v8a,armeabi-v7a}/*.so` from the AAR into a
// build directory. The directory is added to
// `android.sourceSets["main"].jniLibs.srcDirs` further below so the
// .so files ship in the APK the same way committed jniLibs do.
//
// We do the unzip in `doFirst` (lazily) because the AAR file
// resolution is itself lazy — at configuration time the
// `sherpaJniLibs` configuration is not yet resolved.
val extractSherpaJniLibs: TaskProvider<Copy> = tasks.register<Copy>("extractSherpaJniLibs") {
    group = "build"
    description = "Unzip the sherpa-onnx AAR and copy its jni/{arm64-v8a,armeabi-v7a}/*.so into the build dir."
    val outDir = layout.buildDirectory.dir("sherpa-jni")
    outputs.dir(outDir)
    // Re-run when the AAR is re-resolved (e.g. version bump).
    inputs.files(sherpaJniLibs)
    doFirst {
        val aar = sherpaJniLibs.files.singleOrNull()
            ?: error("Expected exactly one sherpa-onnx AAR in '${sherpaJniLibs.name}'; got ${sherpaJniLibs.files}")
        // Clear the destination so stale .so files from a previous
        // AAR version don't linger.
        project.delete(outDir)
        // Each entry preserves its path under `jni/`, so
        // `jni/arm64-v8a/libsherpa-onnx-jni.so` lands at
        // `<outDir>/jni/arm64-v8a/libsherpa-onnx-jni.so`. We then
        // register `<outDir>/jni` as a jniLibs srcDir, which is the
        // layout AGP expects.
        from(zipTree(aar))
        into(outDir)
        include("jni/arm64-v8a/**")
        include("jni/armeabi-v7a/**")
    }
}

// Hand the extracted `<outDir>/jni` to AGP as if it were a committed
// `app/src/main/jniLibs/`. The `<outDir>/jni/{arm64-v8a,armeabi-v7a}`
// subtree is exactly the layout AGP expects, so no further rewriting
// is required.
val sherpaJniSrcDir = layout.buildDirectory.dir("sherpa-jni/jni")
android.sourceSets["main"].jniLibs.srcDirs(sherpaJniSrcDir)

// Make sure the .so files are extracted before any AGP task that
// inspects the jniLibs srcDirs (e.g. mergeDebugJniLibs,
// packageDebug, the Build workflow's aapt2 dump). Hooking into
// `preBuild` is the safest: it runs before everything AGP does
// during a normal build, so the jniLibs srcDir is populated by the
// time AGP walks it.
tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn(extractSherpaJniLibs) }
// Belt-and-suspenders: also depend on the specific merge and package
// tasks in case preBuild is bypassed (e.g. by `gradle :app:mergeDebugJniLibs`).
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibs") }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name == "mergeDebugJniLibs" || it.name == "mergeReleaseJniLibs" }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }
    .configureEach { dependsOn(extractSherpaJniLibs) }
tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }
    .configureEach { dependsOn(extractSherpaJniLibs) }

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
    // Several rounds of "exclude" attempts failed: AGP registers the
    // AAR's classes.jar as an internal sub-artifact named
    // `sherpa-onnx-1.13.8-runtime`, and the AAR's classes.jar always
    // shows up in the runtime classpath regardless of
    // `runtimeOnly` / `packaging { resources { excludes } }` /
    // `configurations.all { exclude(group, module) }`.
    //
    // The robust fix is to drop the AAR as a regular dependency
    // entirely. We declare it in a private `sherpaJniLibs`
    // configuration just so Gradle resolves and downloads it, then
    // `extractSherpaJniLibs` (a Copy task, see the
    // `tasks.register` block at the bottom of this file) unzips the
    // AAR and copies `jni/{arm64-v8a,armeabi-v7a}/*.so` into
    // `app/build/sherpa-jni/<abi>/`. That directory is added to
    // `android.sourceSets["main"].jniLibs.srcDirs` so the .so files
    // are bundled into the APK as if they were committed to
    // `app/src/main/jniLibs/`.
    //
    // The JVM jar is the only thing on the classpath. Its
    // `com.k2fsa.sherpa.onnx.*` types are byte-identical to (and a
    // superset of) the AAR's, so dex never sees a duplicate.
    "sherpaJniLibs"("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8@aar")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:1.13.8")
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

// Earlier attempts that did NOT work and why:
//  * `configurations.all { exclude(group, module = "sherpa-onnx-1.13.8-runtime") }` —
//    the AAR's classes.jar is not a separately-resolvable module under
//    the `com.github.k2-fsa.sherpa-onnx` group, so the exclude silently
//    matches nothing.
//  * `packaging { resources { excludes += "**/com/k2fsa/sherpa/onnx/AudioEvent.class" } }` —
//    the `excludes` only runs at the APK packaging stage, AFTER
//    `:app:checkDebugDuplicateClasses` has already failed on the
//    duplicate in the runtime classpath.
//  * `runtimeOnly` alone, or `runtimeOnly(...) { exclude(module = "sherpa-onnx-1.13.8-runtime") }` —
//    AGP still creates an internal `sherpa-onnx-1.13.8-runtime`
//    sub-artifact from the AAR's classes.jar and feeds it into the
//    runtime classpath. The `-runtime` name is a Gradle construct,
//    not a module, so the exclude never matches. `checkDebugDuplicateClasses`
//    keeps failing.
//
// The working fix is to declare the AAR in a private `sherpaJniLibs`
// configuration (above the `dependencies` block) and unzip its
// jni/{arm64-v8a,armeabi-v7a}/*.so into a build directory via the
// `extractSherpaJniLibs` Copy task. The directory is registered as a
// jniLibs srcDir, so the .so files ship in the APK as if they were
// committed to `app/src/main/jniLibs/`. The AAR's classes.jar is
// never on the classpath, so there is no duplicate with the JVM jar.
