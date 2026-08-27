plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
}

kotlin {
    // Android target
    android {
        namespace = "net.primal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
    }

    // JVM Target
    jvm("desktop")

    // Source set declarations (https://kotlinlang.org/docs/multiplatform-hierarchy.html)
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:utils"))

                // Kotlin
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // Networking && Serialization
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
                api(libs.ktor.client.logging)
                api(libs.ktor.client.content.negotiation)
                api(libs.ktor.client.serialization.kotlinx.json)

                // Logging
                implementation(libs.napier)
            }
        }

        androidMain {
            dependencies {
                // Kotlin
                implementation(libs.kotlinx.coroutines.android)

                // Networking
                api(libs.ktor.client.okhttp)
            }
        }

        val desktopMain by getting
        desktopMain.dependencies {
            // Ktor
            api(libs.ktor.client.cio)
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    kotlin.sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
