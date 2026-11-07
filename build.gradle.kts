plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    // KSP is used for Room's annotation processor.
    //
    // Room's JavaAP (kapt) processor reads Kotlin metadata through its OWN
    // shaded copy of kotlinx-metadata-jvm, and that copy is capped at a
    // metadata version that is older than the one Kotlin 2.4.21 emits. The
    // result is a hard failure during kaptDebugKotlin:
    //
    //   "Provided Metadata instance has version 2.4.0, while maximum
    //    supported version is 2.3.0"
    //
    // Putting a newer kotlin-metadata-jvm on the processor classpath does
    // NOT help, because the shaded copy is used and the external one cannot
    // override it. The KSP processor takes a different route (it reads
    // metadata through the Kotlin analysis API instead of the shaded
    // library), so it handles Kotlin 2.4 metadata fine.
    //
    // KSP uses a version line of its own since 2.3.x; 2.3.13 is the release
    // that is built and tested against Kotlin 2.4.
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
