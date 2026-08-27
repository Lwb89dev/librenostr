plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktorfit)
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
                implementation(project(":core:networking-http"))
                implementation(project(":core:networking-primal"))
                implementation(project(":domain:nostr"))
                implementation(project(":domain:primal"))

                // Core
                implementation(libs.kotlinx.coroutines.core)

                // Serialization
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io)

                // Ktorfit
                implementation(libs.ktorfit.light)
                implementation(libs.ktorfit.converters.response)
                implementation(libs.ktorfit.converters.call)

                // Logging
                implementation(libs.napier)
            }
        }

        androidMain {
            dependencies {
                // Coroutines
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        val desktopMain by getting
        desktopMain.dependencies {
            // Add JVM-Desktop-specific dependencies here
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
