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
        versionName = "1.12.4"

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

// ── The daemon that ships inside the APK ──────────────────────────────────────────────────
//
// The app is a WebView over a Node daemon, and that daemon has to be IN the APK: a Play Store
// user has no adb and no `npm install`. Staging it is therefore part of the build, not a
// manual step someone has to remember — forgetting it produces an APK that installs happily
// and then dies at boot with `Cannot find package '@hono/node-server'`, which is exactly how
// this was discovered. See BUILDING-ENGINES.md § "The daemon payload".
//
// Hooked to the merge*Assets tasks rather than preBuild, because those are what actually
// consume src/androidMain/assets — depending on preBuild would let a stale or missing payload
// through whenever Gradle skipped straight to the merge.
//
// The staged directory is gitignored build output; the script rebuilds the daemon bundle each
// time (~200 ms) so the APK can never ship a daemon older than the source.
// Resolved to an ABSOLUTE path up front, against the app checkout root. A relative value would
// mean two different directories depending on who resolves it — Exec's workingDir is the root
// project, while Gradle's file() is relative to this module — which is precisely the mismatch
// that made the first version of this look for turbollm inside the Android repo. The default
// assumes the sibling layout this project is developed in: <parent>/TurboLLM Android and
// <parent>/turbollm. Override with -Pturbollm.dir=<path> or a line in gradle.properties.
val turbollmDir: File = rootProject.projectDir
    .resolve(providers.gradleProperty("turbollm.dir").orNull ?: "../turbollm")
    .normalize()
val stagedDaemonAssets = layout.projectDirectory.dir("src/androidMain/assets/daemon")

val stageDaemonAssets by tasks.registering(Exec::class) {
    group = "build"
    description = "Bundles the TurboLLM Node daemon into src/androidMain/assets/daemon."
    workingDir = rootProject.projectDir
    // `node` rather than `npm`/`npx` so this doesn't depend on a shell resolving a .cmd
    // shim differently across platforms.
    commandLine("node", "scripts/stage-daemon-assets.mjs", turbollmDir.absolutePath)

    // Deliberately NOT declared up-to-date-able on its inputs: the script's own first step is
    // rebuilding the bundle from turbollm/src, and teaching Gradle to track that whole tree
    // across two repos is more fragile than just running a fast script every time.
    outputs.upToDateWhen { false }

    doFirst {
        if (!turbollmDir.exists()) {
            throw GradleException(
                "No turbollm checkout at ${turbollmDir.absolutePath}.\n" +
                    "Set it with -Pturbollm.dir=<path> or in gradle.properties.",
            )
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(stageDaemonAssets)
}

// A build that produced no payload would ship a broken app, so fail loudly at assemble time
// rather than at the user's first launch.
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    doLast {
        val cli = stagedDaemonAssets.file("dist/cli.js").asFile
        if (!cli.exists()) {
            throw GradleException("Daemon payload missing: ${cli.absolutePath} — the APK would boot to a crash.")
        }
    }
}
