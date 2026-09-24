import com.android.build.api.variant.FilterConfiguration
import java.util.*

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

val configProperties by lazy {
    val configFile = File("config.properties")
    if (configFile.exists()) {
        Properties().apply { load(configFile.reader()) }
    } else {
        null
    }
}

data class SigningConfigProperties(
    val storeName: String,
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyAliasPassword: String,
)

fun extractSigningConfigProperties(storeName: String): SigningConfigProperties? {
    val properties = configProperties
    val storeFilePath = properties?.getProperty("$storeName.storeFile")
    if (properties == null || storeFilePath == null) return null

    val absoluteStoreFile = File(storeFilePath)
    val projectStoreFile = File(projectDir, storeFilePath)
    val storeFile = when {
        absoluteStoreFile.exists() -> absoluteStoreFile
        projectStoreFile.exists() -> projectStoreFile
        else -> throw IllegalArgumentException(
            "storeFile for $storeName can not be found " +
                "at $absoluteStoreFile or $projectStoreFile",
        )
    }

    return SigningConfigProperties(
        storeName = storeName,
        storeFile = storeFile,
        storePassword = properties.getProperty("$storeName.storePassword"),
        keyAlias = properties.getProperty("$storeName.keyAlias"),
        keyAliasPassword = properties.getProperty("$storeName.keyPassword"),
    )
}

val appVersionCode = 38
val appVersionName = "0.5.17"

// ELF header layout, used by verifyBuiltInTorLibrary below.
val ELF_HEADER_BYTES = 20
val ELF_CLASS_OFFSET = 4
val ELF_CLASS_64: Byte = 2
val ELF_MACHINE_OFFSET = 18
val ELF_MACHINE_AARCH64 = 0xB7

tasks.register("generateReleaseProperties") {
    doLast {
        val file = File("${project.rootDir}/release.properties")
        file.writeText("version=$appVersionName")
    }
}

// The built-in Tor library (tools/arti-build) is committed under src/main/jniLibs/arm64-v8a. This checks
// the file before every build because a wrong one fails silently and late: a truncated download or a
// library for another architecture installs fine and only reports "Tor is not available" on the
// device. A *missing* library is legitimate on a branch or a machine without the Android toolchain
// (built-in Tor is then simply unavailable and the settings screen says so), so it only fails the
// build when -Plibrenostr.requireBuiltInTor=true, which release builds set.
val builtInTorLibrary = file("src/main/jniLibs/arm64-v8a/libnostr_arti.so")
val requireBuiltInTor = providers.gradleProperty("librenostr.requireBuiltInTor").map { it.toBoolean() }.orElse(false)

val verifyBuiltInTorLibrary = tasks.register("verifyBuiltInTorLibrary") {
    group = "verification"
    description = "Checks that the committed built-in Tor library, if present, is an arm64 ELF shared object."
    doLast {
        if (!builtInTorLibrary.exists()) {
            val message = "Built-in Tor library missing (${builtInTorLibrary.relativeTo(projectDir)}); " +
                "built-in Tor will be unavailable in this build. Build it with tools/arti-build/build-arti.sh."
            if (requireBuiltInTor.get()) throw GradleException(message) else logger.warn(message)
            return@doLast
        }
        val header = builtInTorLibrary.inputStream().use { it.readNBytes(ELF_HEADER_BYTES) }
        val isElf = header.size == ELF_HEADER_BYTES &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        val is64Bit = header.size == ELF_HEADER_BYTES && header[ELF_CLASS_OFFSET] == ELF_CLASS_64
        // e_machine is a little-endian 16-bit field at offset 18; 0x00B7 (183) is AArch64.
        val machine = if (header.size == ELF_HEADER_BYTES) {
            (header[ELF_MACHINE_OFFSET].toInt() and 0xff) or ((header[ELF_MACHINE_OFFSET + 1].toInt() and 0xff) shl 8)
        } else {
            -1
        }
        if (!isElf || !is64Bit || machine != ELF_MACHINE_AARCH64) {
            throw GradleException(
                "${builtInTorLibrary.relativeTo(projectDir)} is not an arm64-v8a ELF shared object " +
                    "(elf=$isElf, 64bit=$is64Bit, e_machine=$machine). It would install and then fail to load.",
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("generateReleaseProperties", verifyBuiltInTorLibrary)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "net.primal.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.librenostr.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            type = "String",
            name = "LOCAL_STORAGE_KEY_ALIAS",
            value = "\"${configProperties?.getProperty("localStorage.keyAlias", "")}\"",
        )
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        extractSigningConfigProperties("alternative")?.let {
            signingConfigs.create(it.storeName) {
                storeFile = it.storeFile
                storePassword = it.storePassword
                keyAlias = it.keyAlias
                keyPassword = it.keyAliasPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("altRelease") {
            initWith(getByName("release"))
            signingConfig = try {
                signingConfigs.getByName("alternative")
            } catch (_: UnknownDomainObjectException) {
                signingConfigs.getByName("debug")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    sourceSets {
        named("altRelease") {
            kotlin.directories.add("src/release/kotlin")
            res.directories.add("src/release/res")
        }

        named("benchmark") {
            kotlin.directories.add("src/release/kotlin")
            res.directories.add("src/release/res")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            optIn.addAll(
                "kotlin.time.ExperimentalTime",
                "kotlin.uuid.ExperimentalUuidApi",
            )
        }
    }

    hilt {
        enableAggregatingTask = true
    }

    lint {
        checkDependencies = true
        checkTestSources = true
        checkReleaseBuilds = false
        disable.add("LocalContextGetResourceValueCall")
    }

    packaging {
        jniLibs {
            // Ship the built-in Tor library exactly as tools/arti-build produced it. Its Cargo release
            // profile already strips it, so AGP's own strip pass would only rewrite the .comment section,
            // making the copy in the APK differ from the committed one and the build unverifiable.
            keepDebugSymbols += "**/libnostr_arti.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // JUnit
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    sourceSets {
        findByName("main")?.java?.srcDirs(project.file("src/main/kotlin"))
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Zapstore (see zapstore.yaml) matches release assets against a fixed filename pattern, so the
// altRelease ABI splits need a stable name instead of AGP's default "app-<abi>-altRelease.apk".
androidComponents {
    onVariants(selector().withBuildType("altRelease")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            if (abi != null) {
                output.outputFileName.set("librenostr-$appVersionName-$abi.apk")
            }
        }
    }
}

// Opt-in Compose compiler stability/skippability reports: -PcomposeReports.
// Output lands in app/build/compose_reports (classes.txt = stability, composables.txt = skippability).
if (project.hasProperty("composeReports")) {
    composeCompiler {
        val reportsDir = layout.buildDirectory.dir("compose_reports")
        reportsDestination = reportsDir
        metricsDestination = reportsDir
    }
}

// Dagger/Hilt 2.59.2 bundles a kotlin-metadata-jvm that only reads metadata up to 2.3.0.
// Force it to match the Kotlin version so the Hilt processor can read Kotlin 2.4.0 metadata.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

dependencies {
    implementation(project(":core:utils"))
    implementation(project(":core:nips"))
    implementation(libs.quartz)
    implementation(project(":core:app-config"))
    implementation(project(":core:networking-http"))
    implementation(project(":core:networking-primal"))
    implementation(project(":core:networking-upload"))
    implementation(project(":core:networking-lightning"))
    implementation(project(":core:caching"))

    implementation(project(":domain:nostr"))
    implementation(project(":domain:primal"))
    implementation(project(":domain:account"))

    implementation(project(":data:caching:remote"))
    implementation(project(":data:caching:repository"))

    implementation(project(":data:account:repository"))
    implementation(project(":data:account:signer"))

    implementation(libs.bignum)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.ktx)
    runtimeOnly(libs.androidx.appcompat)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.placeholder.foundation)
    implementation(libs.compose.placeholder.material3)

    implementation(libs.compose.constraintlayout)
    implementation(libs.constraintlayout)

    implementation(libs.permissions.accompanist)

    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.webkit)

    implementation(libs.markwon.core)
    implementation(libs.markwon.image)
    implementation(libs.markwon.imagecoil)
    implementation(libs.markwon.inlineparser)
    implementation(libs.markwon.latex)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.simple)

    implementation(libs.navigation.compose)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    implementation(libs.room.runtime)
    implementation(libs.sqlcipher.android)

    ksp(libs.bundles.hilt.compiler)
    implementation(libs.bundles.hilt)

    implementation(libs.datastore)
    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.okio)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.retrofit.serialization.converter)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.csv)
    implementation(libs.kotlinx.datetime)
    implementation(libs.guava)

    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.coil.network)
    // Expose Coil's Android OkHttp factory to the app so the image loader can
    // attach Wikimedia-compatible request headers to GIF thumbnails.
    implementation("io.coil-kt.coil3:coil-network-okhttp-android:${libs.versions.coil.get()}")
    implementation(libs.telephoto.zoomable.image)
    implementation(libs.telephoto.zoomable.peek.overlay)
    implementation(libs.telephoto.zoomable.image.coil)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer.core)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.ui)
    implementation(libs.media3.exoplayer.ui.compose)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.zoomimage.compose.coil3)

    implementation(libs.lottie.compose)
    implementation(libs.flippable)
    implementation(libs.reorderable)

    implementation(libs.napier)

    implementation(libs.lightning.kmp)
    implementation(libs.bitcoinj.core)
    implementation(libs.secp256k1.kmp.jvm)
    implementation(libs.secp256k1.kmp.jni.android)
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    implementation(libs.url.detector)

    implementation(libs.qrcode.generator)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.espresso.core)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.kotest.assertions.json)
    androidTestImplementation(libs.mockk.android)
}
