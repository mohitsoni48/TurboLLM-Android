import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Declared so the module SHAPE is ready for iOS — none of these targets are actually
    // built/shipped yet (no iosApp Xcode project exists). See NodeRuntimeLauncher.ios.kt.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.appcompat)
            implementation(libs.android.material)
            implementation(libs.ktor.client.android)
            implementation(libs.lottie.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.turbollm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.turbollm.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike-a"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        // The vendored nodejs-mobile .so's — see PROVENANCE.md for what's known about them.
        // src/androidMain/jniLibs/<abi> holds the NDK-cross-compiled llama-server engine
        // (llama-server itself renamed libllama_server.so to fit the lib*.so convention) —
        // this is the ONLY location on Android an app can execute a file from that it didn't
        // ship in the APK itself: files an app writes to its own private storage at runtime
        // (which is how it was staged for earlier verification passes) get EACCES from
        // execve() on Android 10+ (W^X hardening) even with the execute bit set and even
        // though `run-as`'s elevated debug shell can run them directly — confirmed live
        // (BLUEPRINT.md §7 Spike D). Files here land in the installed APK's own
        // nativeLibraryDir, which Android explicitly exempts from that restriction.
        jniLibs.srcDirs("src/androidMain/cpp/libnode/bin", "src/androidMain/jniLibs")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // AGP's modern default packs native libs uncompressed *inside* the APK zip and mmaps them
    // directly (no on-disk extraction) — confirmed live this leaves the installed app's
    // nativeLibraryDir on disk but genuinely EMPTY. That's fine for a JNI library only ever
    // dlopen'd into this process (libnode.so, libnative-lib.so), but llama-server needs to be
    // spawned as a separate PROCESS via execve() from a real file path — there's no file to
    // point at without this. Forces the older "always extract native libs to disk at install
    // time" behavior instead.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
