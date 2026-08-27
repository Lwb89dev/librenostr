plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // Android target
    android {
        namespace = "net.primal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // JVM Target
    jvm("desktop")

    // Source set declarations
    sourceSets {
        commonMain {
            dependencies {
                // Coroutines
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        androidMain {
            dependencies {
                // Kotlin
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val desktopMain by getting
        desktopMain.dependencies {
        }
    }
}
