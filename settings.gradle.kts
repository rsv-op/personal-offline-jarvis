pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jitpack.io for the pre-built sherpa-onnx AAR. The sherpa-onnx
        // GitHub project publishes native AARs with each release; jitpack
        // exposes them at com.github.k2-fsa:sherpa-onnx:<version>. The
        // AAR bundles the Android-specific libsherpa-onnx-jni.so for
        // arm64-v8a and armeabi-v7a, so no extra jniLibs setup is
        // required.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PersonalOfflineJarvis"
include(":app")
