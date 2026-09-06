// Plain Android library, not the Kotlin Multiplatform module shape used
// elsewhere in this repo's core:* modules (e.g. core:networking-lightning) —
// this one needs a concrete Android/NDK target for its CMake/C++ build,
// which the newer KMP Android library plugin doesn't have the same
// established support for. This is also the first native (C++/NDK) module
// in this codebase; see src/main/cpp/CMakeLists.txt for the full native
// dependency story (bergamot-translator, fetched at build time, not
// vendored — no other module here vendors C++ source trees).
plugins {
    alias(libs.plugins.com.android.library)
}

android {
    namespace = "net.primal.core.translation"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        ndk {
            // armeabi-v7a and x86_64 dropped app-wide: armeabi-v7a because
            // simd_utils' NEON path (vfmaq_f32, an FMA instruction) isn't
            // guaranteed on plain -march=armv7-a, and x86_64 because real
            // native x86_64 Android phones are essentially nonexistent —
            // the rare x86 devices (Chromebooks) run arm64 binaries via
            // their own ARM translation layer anyway. With minSdk 28, this
            // leaves arm64-v8a as the only architecture worth targeting.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Notes are short: no need for marian's own CLI tools/tests, and no
                // reason to burn extra build time compiling them for every ABI.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}
