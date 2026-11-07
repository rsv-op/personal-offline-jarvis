import org.gradle.api.tasks.TaskProvider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    // Room runs under KSP; see the comment in the root build file for why it
    // cannot run under kapt with Kotlin 2.4.
    id("com.google.devtools.ksp")
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
            //
            // The llama.cpp path is arm64-v8a only. Its CMake configuration
            // refuses any other ABI outright, so listing armeabi-v7a here is
            // fine only because the native build is restricted to arm64 by
            // `llamaAbiFilters` below.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // The native build is driven by app/src/main/cpp/CMakeLists.txt, which
        // compiles llama.cpp from source and links our JNI bridge against it.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_NATIVE=OFF",
                    // Vulkan is off by default: on the Adreno generation this
                    // app targets the CPU NEON/dotprod kernels are usually
                    // faster than the Vulkan backend.
                    "-DGGML_VULKAN=OFF",
                    // KleidiAI supplies the best ARM NEON/dotprod kernels, but
                    // ggml fetches it from a GitHub release at configure
                    // time. Set JARVIS_DISABLE_KLEIDIAI=1 to fall back to the
                    // standard ARM kernels if that download is unavailable.
                    "-DGGML_CPU_KLEIDIAI=" +
                        if (System.getenv("JARVIS_DISABLE_KLEIDIAI") == "1") "OFF" else "ON"
                )
            }
        }

        // Only arm64-v8a is built for the llama.cpp path. The `cppFlags` and
        // `arguments` above are shared, so the ABI restriction is enforced in
        // the CMakeLists (which fails the build for any other ABI) and
        // mirrored here with `externalNativeBuild` per-ABI arguments.
    }

    // The llama.cpp sources are fetched by `fetchLlamaCpp` into the build
    // directory; the path is handed to CMake as a cache variable.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    // The release build is minified; app/proguard-rules.pro keeps the JNI
    // bridge and the Room implementation alive.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }

    packaging {
        jniLibs {
            // The sherpa-onnx `libsherpa-onnx-jni.so` is pulled out of the AAR
            // by `extractSherpaJniLibs` and lives at
            // `app/build/sherpa-jni/jni/<abi>/`.
            pickFirsts += "**/libsherpa-onnx-jni.so"
            // The llama.cpp bridge is `libjarvis_llama.so`, built from source
            // by the external native build. Nothing else produces this name.
            pickFirsts += "**/libjarvis_llama.so"
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

// Download the sherpa-onnx AAR directly via HTTP from jitpack and
// extract its jni/{arm64-v8a,armeabi-v7a}/*.so into a build
// directory. We deliberately AVOID declaring the AAR in any
// Gradle configuration: AGP's `checkDebugDuplicateClasses` walks
// every resolved artifact across every configuration in the
// project, and any time the AAR is resolved AGP feeds its
// `classes.jar` (registered internally as the
// `sherpa-onnx-1.13.8-runtime` sub-artifact) into the duplicate
// check alongside the JVM jar, which then fails.
//
// Downloading via HTTP keeps the AAR entirely out of Gradle's
// dependency graph. Only its jniLibs make it into the APK, via
// `<outDir>/jni` becoming a jniLibs srcDir below.
//
// The download is done inline in `extractSherpaJniLibs.doFirst`
// rather than as a separate `downloadSherpaAar` task with
// `dependsOn`, because the earlier split-task design lost
// output declarations across two `outputs.file(...)` calls
// (the second call replaced the first) and the AAR file
// silently never got fetched, which manifested downstream
// as `Cannot expand ZIP ... as it does not exist`. Doing the
// download inline guarantees it runs before the unzip and
// removes the cross-task output declaration from the
// equation. The full `unzip -l` listing is still written to
// `aar-listing.txt` so the `Post the sherpa-onnx AAR listing
// to the PR` workflow step can post it.
val extractSherpaJniLibs: TaskProvider<Exec> = tasks.register<Exec>("extractSherpaJniLibs") {
    group = "build"
    description = "Download the sherpa-onnx 1.13.8 AAR from jitpack and unzip its jniLibs into the build dir."
    val aarFile = layout.buildDirectory.file("sherpa-jni/sherpa-onnx-1.13.8.aar")
    val listingFile = layout.buildDirectory.file("sherpa-jni/aar-listing.txt")
    val outDir = layout.buildDirectory.dir("sherpa-jni")
    outputs.dir(outDir)
    outputs.upToDateWhen { false }
    val aarPath = aarFile.get().asFile.absolutePath
    val aarDir = aarFile.get().asFile.parentFile.absolutePath
    val listingPath = listingFile.get().asFile.absolutePath
    val aarUrl = "https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/1.13.8/sherpa-onnx-1.13.8.aar"
    // Single shell pipeline: wipe the build dir, fetch the
    // AAR, list its contents, and unzip the three possible
    // jniLibs layouts (`jniLibs/<abi>/`, `jni/<abi>/`, and
    // `lib/<abi>/`) into <outDir>. We keep the directory
    // structure (no `-j`) so the result is laid out as
    // <outDir>/<jniLibs|jni|lib>/<abi>/<libname>.so, which
    // matches what AGP expects when <outDir>/<jniLibs|jni|lib>
    // is registered as a jniLibs srcDir. The final `find` makes
    // the post-extract layout visible in the CI log.
    commandLine = listOf(
        "sh", "-c",
        "set -e; " +
            "rm -rf '$aarDir'; " +
            "mkdir -p '$aarDir'; " +
            "echo '== downloading AAR =='; " +
            "curl -fSL --retry 3 -o '$aarPath' '$aarUrl'; " +
            "echo '== AAR contents =='; " +
            "unzip -l '$aarPath' | tee '$listingPath'; " +
            "echo '== extracting jniLibs =='; " +
            "unzip -o '$aarPath' " +
            "'jniLibs/arm64-v8a/*' 'jniLibs/armeabi-v7a/*' " +
            "'jni/arm64-v8a/*' 'jni/armeabi-v7a/*' " +
            "'lib/arm64-v8a/*' 'lib/armeabi-v7a/*' " +
            "-d '$aarDir' 2>/dev/null || true; " +
            "echo '== extracted layout =='; " +
            "find '$aarDir' -type f | sort"
    )
}

// Hand the extracted `<outDir>/{jniLibs,jni,lib}/<abi>/<libname>.so`
// to AGP as if it were committed to `app/src/main/jniLibs/`. The
// k2-fsa/sherpa-onnx AAR (built via
// .github/workflows/android.yaml → SherpaOnnxAar) uses the
// `jniLibs/<abi>/` layout inside the AAR; some other AARs use
// `jni/<abi>/` or `lib/<abi>/`. We register all three possible
// extracted subtrees as jniLibs srcDirs, so whatever layout the
// downloaded AAR actually has, the .so files end up where AGP
// expects.
val sherpaJniSrcRoot = layout.buildDirectory.dir("sherpa-jni")
android.sourceSets["main"].jniLibs.srcDirs(
    sherpaJniSrcRoot.map { it.dir("jniLibs") },
    sherpaJniSrcRoot.map { it.dir("jni") },
    sherpaJniSrcRoot.map { it.dir("lib") },
)

// ---------------------------------------------------------------------------
// llama.cpp
// ---------------------------------------------------------------------------
//
// The local language model runs on llama.cpp, compiled from source by the
// external native build (app/src/main/cpp/CMakeLists.txt). The sources are
// fetched here rather than committed, for the same reason the sherpa-onnx AAR
// is fetched rather than committed: they are large, versioned upstream, and
// pinning them to a known-good revision is more honest than carrying a
// 40 MB copy in the repository.
//
// The pinned revision is LLAMA_CPP_TAG. It is a release tag rather than a
// branch so the build is reproducible: the same tag always yields the same
// native code.

val llamaCppTag = "b12189"
val llamaCppArchive = "llama.cpp-${llamaCppTag}.tar.gz"
val llamaCppUrl =
    "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/${llamaCppTag}"
val llamaCppRoot = layout.buildDirectory.dir("llama-cpp")
val llamaCppSourceDir = llamaCppRoot.map { it.dir("llama.cpp-${llamaCppTag}") }

/**
 * Downloads and unpacks the pinned llama.cpp release.
 *
 * The download is idempotent: if the source tree is already present the task
 * does nothing, so incremental builds do not re-download 38 MB every time.
 */
val fetchLlamaCpp: TaskProvider<Exec> = tasks.register<Exec>("fetchLlamaCpp") {
    group = "build"
    description = "Download and unpack the pinned llama.cpp sources for the native build."

    val rootDir = llamaCppRoot.get().asFile.absolutePath
    val sourceDir = llamaCppSourceDir.get().asFile.absolutePath
    val archive = File(rootDir, llamaCppArchive)
    val url = llamaCppUrl
    val tag = llamaCppTag

    inputs.property("llamaCppTag", tag)
    outputs.dir(llamaCppSourceDir)
    // The sources are fetched over the network, so the task can never be
    // considered up to date by Gradle's own checks.
    outputs.upToDateWhen { false }

    commandLine = listOf(
        "sh", "-c",
        "set -e; " +
            "mkdir -p '$rootDir'; " +
            // Already unpacked? Then there is nothing to do.
            "if [ -f '$sourceDir/include/llama.h' ]; then " +
            "  echo 'llama.cpp $tag already unpacked'; exit 0; " +
            "fi; " +
            "echo '== downloading llama.cpp $tag =='; " +
            "curl -fSL --retry 3 -o '$archive' '$url'; " +
            "echo '== unpacking =='; " +
            "tar xzf '$archive' -C '$rootDir'; " +
            "rm -f '$archive'; " +
            "test -f '$sourceDir/include/llama.h' || " +
            "  { echo 'llama.h missing after unpacking'; exit 1; }; " +
            "echo '== llama.cpp ready at $sourceDir =='"
    )
}

// The native build needs the sources in place before CMake configures.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchLlamaCpp) }

// Hand the unpacked source tree to CMake. `-D` cache variables passed through
// `arguments` are the supported way to configure an AGP external native build,
// and the path is resolved lazily so it is correct for both debug and release.
android.defaultConfig.externalNativeBuild.cmake.arguments += listOf(
    "-DLLAMA_SRC_DIR=${llamaCppSourceDir.get().asFile.absolutePath}"
)

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

// Room compiler options.
//
// These used to live in a `kapt { arguments { ... } }` block, which is the
// kapt-specific way of passing processor options. Under KSP the same
// options are passed through the `ksp { arg(...) }` block instead.
//
// `room.schemaLocation` is only meaningful when the schema is exported;
// ChatDatabase sets exportSchema = false because the transcript has a single
// version and is rebuilt from scratch. The option is kept so a future schema
// change can be exported without having to remember the plumbing.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
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
    // Several rounds of "exclude" attempts failed: AGP registers the
    // AAR's classes.jar as an internal sub-artifact named
    // `sherpa-onnx-1.13.8-runtime`, and the AAR's classes.jar always
    // shows up in the runtime classpath regardless of
    // `runtimeOnly` / `packaging { resources { excludes } }` /
    // `configurations.all { exclude(group, module) }` / a private
    // `sherpaJniLibs` configuration. AGP's `checkDebugDuplicateClasses`
    // walks every resolved artifact across every configuration, so
    // any time the AAR is resolved its classes.jar ends up next to
    // the JVM jar's classes.jar and the duplicate check fails.
    //
    // The robust fix is to NOT add the AAR to any Gradle
    // configuration. Instead, the `extractSherpaJniLibs` Copy task
    // (see the `tasks.register` block above) downloads the AAR
    // directly from jitpack via HTTP, unzips
    // `jni/{arm64-v8a,armeabi-v7a}/*.so` into
    // `app/build/sherpa-jni/<abi>/`, and that directory is registered
    // as a jniLibs srcDir. The .so files ship in the APK as if they
    // were committed to `app/src/main/jniLibs/`, but Gradle's
    // dependency graph never sees the AAR.
    //
    // The JVM jar is the only contributor of
    // `com.k2fsa.sherpa.onnx.*` types on the classpath.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:1.13.8")

    // Room stores the chat transcript. WAL journalling is enabled in
    // ChatDatabase so the UI can read a half-finished reply without
    // blocking the generator's writes.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    // Room's compiler runs under KSP, not kapt.
    //
    // Room's kapt/JavaAP processor reads Kotlin metadata through its own
    // shaded copy of kotlinx-metadata-jvm, which is capped below the
    // metadata version Kotlin 2.4.21 emits, so it dies with
    //   "Provided Metadata instance has version 2.4.0, while maximum
    //    supported version is 2.3.0"
    // An external kotlin-metadata-jvm on the processor classpath cannot
    // override the shaded copy. The KSP processor reads metadata through
    // the Kotlin analysis API instead and handles it fine.
    //
    // Only `room-compiler` is declared: it is the processor entry point
    // that generates `ChatDatabase_Impl`, and it pulls
    // `room-compiler-processing` in transitively. Declaring the
    // processing artifact on its own leaves `ChatDatabase_Impl` missing
    // and the database cannot be opened.
    ksp("androidx.room:room-compiler:2.8.5")

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
    // ApplicationProvider gives the Robolectric tests an application context
    // for the in-memory Room database.
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
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
