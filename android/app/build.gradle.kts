plugins { id("com.android.application") }

android {
    namespace = "io.moli.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.moli.android"
        minSdk = 28
        targetSdk = 35
        // The CI gives its run number: each APK installs over the previous one,
        // and says which it is (Settings, Apps, Moli: « version 56 »).
        val build = findProperty("moliVersionCode") as String? ?: "1"
        versionCode = build.toInt()
        versionName = build
        // The engine (scripts/build-android.sh): ARM64 only, the tablet's.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        // Signed afterwards by the CI (android.yml), with the owner's certificate.
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    doFirst {
        require(file("src/main/jniLibs/arm64-v8a/libmoli_android.so").isFile) {
            "Build the engine first: sh scripts/build-android.sh"
        }
    }
}
