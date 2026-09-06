import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.gradlePlayPublisher)
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

// ── Build inputs that live outside this repo or outside git ───────────────────────────────

// The turbollm checkout, resolved to an ABSOLUTE path against the app checkout root. A relative
// value would mean two different directories depending on who resolved it — Exec's workingDir is
// the root project, while Gradle's file() is relative to this module — which is exactly the
// mismatch that had an earlier version looking for turbollm inside the Android repo. The default
// assumes the sibling layout this project is developed in: <parent>/TurboLLM Android and
// <parent>/turbollm. Override with -Pturbollm.dir=<path> or a line in gradle.properties.
val turbollmDir: File = rootProject.projectDir
    .resolve(providers.gradleProperty("turbollm.dir").orNull ?: "../turbollm")
    .normalize()

// local.properties is gitignored, which is why the signing credentials live there rather than in
// this file. Read defensively: a fresh clone has only sdk.dir, and debug builds must still work.
val localProperties = Properties().apply {
    rootProject.projectDir.resolve("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
fun localProp(name: String): String? = localProperties.getProperty(name)

// Whether the (not yet working) Vulkan engine is packaged. Off by default — see the packaging
// block below for what "not working" means concretely.
val shipVulkanEngine: Boolean =
    providers.gradleProperty("turbollmVulkanEngine").orNull?.toBoolean() ?: false

val uploadStoreFile: File? = localProp("TURBOLLM_UPLOAD_STORE_FILE")
    ?.let { rootProject.projectDir.resolve(it).normalize() }
    ?.takeIf { it.exists() }

// Google Play publishing (Gradle Play Publisher, github.com/Triple-T/gradle-play-publisher),
// wired the same way this repo's signingConfig above is: a local, gitignored credential path in
// local.properties, never a value baked into the build script. `serviceAccountCredentials` is
// Google Cloud service-account JSON with "Release manager" access on this app in Play Console —
// create it under Play Console -> Setup -> API access. Deliberately NOT bundled into git the way
// the reference project (Scout, C:\...\AndroidStudioProjects\Scout) commits its own
// play-account.json — a live Play publishing credential in git history is a real secret leak
// regardless of whether the repo is private, and this project isn't repeating that.
//
// track defaults to "internal" (Play's internal testing track — up to 100 named testers, no
// review, publishes in minutes), NOT Scout's "production" + COMPLETED: this is the FIRST ever
// TurboLLM Android submission, and Play requires that first artifact on ANY track to be uploaded
// once through the Play Console web UI by hand regardless of what this plugin is configured to
// do — there is no API path around that for a brand-new app. Override with
// -PturbollmPlayTrack=<track> once a track is actually being used programmatically.
val playServiceAccountFile: File? = localProp("TURBOLLM_PLAY_SERVICE_ACCOUNT")
    ?.let { rootProject.projectDir.resolve(it).normalize() }
val playTrack: String = providers.gradleProperty("turbollmPlayTrack").getOrElse("internal")

// versionName mirrors the npm package exactly (founder direction, 2026-09-06). versionCode has to
// be a single ascending integer for Play, so it is derived from the same semver rather than being
// tracked by hand: 1.12.4 -> 11204. Two digits each for minor and patch caps them at 99, which is
// far past anything this project has used.
val turbollmVersion: String = turbollmDir.resolve("package.json")
    .takeIf { it.exists() }
    ?.let { Regex("""["]version["]\s*:\s*["]([^"]+)["]""").find(it.readText())?.groupValues?.get(1) }
    ?: throw GradleException(
        "Cannot read the version from ${turbollmDir.resolve("package.json")}. " +
            "The Android versionName is derived from the npm package — point at it with " +
            "-Pturbollm.dir=<path>.",
    )

val turbollmVersionCode: Int = turbollmVersion
    .removeSuffix("-SNAPSHOT")
    .split(".", limit = 3)
    .map { it.takeWhile(Char::isDigit).ifEmpty { "0" }.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "com.turbollm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.turbollm.android"
        minSdk = 24
        targetSdk = 35
        // Single source of truth: the npm package's version. The Android app is a shell around
        // that daemon, so two independently-bumped version lines would only ever disagree — and
        // "which version is this?" during a beta bug report has to have one answer.
        versionCode = turbollmVersionCode
        versionName = turbollmVersion

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // armeabi-v7a is deliberately NOT here. That ABI ships libnode.so but no llama
            // engine — jniLibs only carries arm64-v8a and x86_64 builds — so a 32-bit device
            // would install happily, start the daemon, and then have nothing to register as an
            // engine (engines/seed.ts): an app that opens and cannot infer. Listing the ABI is
            // what tells Play it may serve those devices, so the honest fix is to not claim it.
            // Add it back only alongside an armeabi-v7a engine build.
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    // Upload signing, read from local.properties (gitignored) — never from the repo. Created by
    // scripts/create-release-keystore.mjs. Declared only when configured, so a plain
    // `assembleDebug` on a fresh clone still works without any keystore present.
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = uploadStoreFile
                storePassword = localProp("TURBOLLM_UPLOAD_STORE_PASSWORD")
                keyAlias = localProp("TURBOLLM_UPLOAD_KEY_ALIAS")
                keyPassword = localProp("TURBOLLM_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned rather than debug-signed when unconfigured: a debug-signed release
            // artifact is silently rejected by Play, which is a far more confusing failure than
            // the explicit one the bundle/assemble check below raises.
            signingConfig = signingConfigs.findByName("upload")
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
            // The Vulkan engine is BUILT and bundleable (BUILDING-ENGINES.md Build B), but is
            // excluded from shipping builds by default because it does not work yet: on the
            // devices tested it either refuses to initialise (Adreno 616, "Vulkan 1.2 required")
            // or initialises and then crashes (Mali-G78) — and there its mere presence took the
            // working CPU engine down with it. Nothing else needs changing to flip this: the
            // daemon registers the second engine only if the binary is actually in the APK
            // (engines/seed.ts), so excluding the file here means users simply see one engine.
            // Turn it on with -PturbollmVulkanEngine=true once it is proven on real hardware.
            if (!shipVulkanEngine) {
                excludes += "**/libllama_server_vk.so"
            }
        }
    }

    lint {
        // lintVitalAnalyzeRelease CRASHES on this project and so blocks every release build:
        //   Unexpected failure during lint analysis of DaemonWebView.kt
        //   Found class ...KaCallableMemberCall, but interface was expected
        //   detector: androidx.lifecycle.lint.NonNullableMutableLiveDataDetector
        // That is a version skew between the bundled lint checks and the Kotlin analysis API —
        // a tooling bug, not a finding in this app. Disabling the single issue id does NOT help,
        // because the detector still loads during the analysis phase, so the only lever that
        // actually unblocks a release is this one.
        //
        // The trade-off is real and worth stating: release builds no longer fail on fatal lint
        // issues. Lint is NOT gone — `./gradlew :composeApp:lintDebug` still runs the full set —
        // so run that before a release, and revisit this once AGP and the lifecycle lint
        // artifacts are back in step.
        checkReleaseBuilds = false
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

// The play{} block itself is declared unconditionally (same as the reference project) — it just
// stores config on the plugin's extension, and Gradle Play Publisher's own publish* tasks are
// only ever added/invoked on request, so this has zero effect on a normal assembleDebug/
// bundleRelease. Nothing here can make a build task silently turn into a live Play Store publish.
play {
    serviceAccountCredentials.set(playServiceAccountFile)
    track.set(playTrack)
    // resourcesDir/whatsNew etc. intentionally left at plugin defaults for now — this is the
    // scaffolding for the FIRST release, not a tuned pipeline yet (see the comment above
    // playServiceAccountFile for why the very first upload can't go through this plugin anyway).
    defaultToAppBundles.set(true)
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
