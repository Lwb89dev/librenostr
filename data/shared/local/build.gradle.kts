plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
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

    // Source set declarations (https://kotlinlang.org/docs/multiplatform-hierarchy.html)
    sourceSets {
        commonMain {
            dependencies {
                // Internal
                implementation(project(":core:utils"))

                // Core
                implementation(libs.kotlinx.coroutines.core)

                // Logging
                implementation(libs.napier)

                // Cryptography
                implementation(libs.whyoleg.cryptography.core)
                implementation(libs.whyoleg.cryptography.provider.optimal)

                // Room
                api(libs.room3.runtime)
                api(libs.room3.paging)

                // Serialization
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.io)
            }
        }

        androidMain {
            dependencies {
                // Coroutines
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.core.ktx)

                // Cryptography
                implementation(libs.whyoleg.cryptography.provider.jdk)
                implementation(libs.androidx.security.crypto)

                // Room
                api(libs.room3.runtime.android)
                api(libs.jetpack.sqlite.bundled.android)
            }
        }

        val desktopMain by getting
        desktopMain.dependencies {
            // Add JVM-Desktop-specific dependencies here

            // Room & SQLite
            api(libs.jetpack.sqlite.bundled.jvm)
        }

        commonTest {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
