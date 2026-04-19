plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // KSP processor generates the Xposed meta-data from @InjectYukiHookWithXposed
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension>  {
    namespace   = "dev.lok1s.handoffmyvpn"
    compileSdk  = 35

    // NDK version — AGP downloads it automatically if not present on disk
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId  = "dev.lok1s.handoffmyvpn"
        minSdk         = 28          // Android 9 — Dobby ARM64 trampoline requirement
        targetSdk      = 35
        versionCode    = 5
        versionName    = "2.5.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-O2")
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // CMake build file for the native Dobby-based payload
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // YukiHookAPI KSP output directory
    sourceSets["main"].kotlin.directories.add("build/generated/ksp/main/kotlin")

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.yukihook.api)
    implementation(libs.material)
    compileOnly("de.robv.android.xposed:api:82") { isTransitive = false }
    ksp(libs.yukihook.ksp.xposed)

    // AndroidX & Compose
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
