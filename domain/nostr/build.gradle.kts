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
    }

    // JVM Target
    jvm("desktop")

    // Source set declarations (https://kotlinlang.org/docs/multiplatform-hierarchy.html)
    sourceSets {
        commonMain {
            dependencies {
                // Internal
                implementation(project(":core:utils"))

                // Kotlin
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // Cryptography
//                implementation(libs.lightning.kmp)
                api(libs.bitcoin.kmp)
                api(libs.secp256k1.kmp)
                api(libs.korlibs.crypto)

                implementation(libs.ktor.io)

                implementation(libs.napier)
                api(libs.bignum)
            }
        }

        androidMain {
            dependencies {
                // Kotlin
                implementation(libs.kotlinx.coroutines.android)

                // Cryptography
                implementation(libs.secp256k1.kmp.jni.android)
            }
        }
        val desktopMain by getting
        desktopMain.dependencies {
            // Cryptography
//                implementation(libs.lightning.kmp.jvm)
            implementation(libs.bitcoin.kmp.jvm)
            implementation(libs.secp256k1.kmp.jvm)
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
