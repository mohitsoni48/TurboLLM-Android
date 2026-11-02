import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

val allCpuVariants = "base;dotprod;i8mm"
val debugCpuVariants = providers.gradleProperty("turbollmSdkAllVariants")
    .map { if (it.toBoolean()) allCpuVariants else "base" }
    .getOrElse("base")

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinxSerialization)
}

android {
    namespace = "dev.turbollm.sdk"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DTURBOLLM_LLAMA_DIR=${rootProject.file("third_party/llama.cpp").absolutePath}",
                )
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild { cmake { arguments += "-DTURBOLLM_VARIANTS=$debugCpuVariants" } }
        }
        release {
            externalNativeBuild { cmake { arguments += "-DTURBOLLM_VARIANTS=$allCpuVariants" } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.litertlm)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
