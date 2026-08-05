import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.*
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    compileSdk = 36
    namespace = "org.witness.proofmode"

    buildFeatures {
        viewBinding = true
        compose = true
        dataBinding = true
        buildConfig = true
    }

    flavorDimensions += "default"

    productFlavors {
        create("default") {
            dimension = "default"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "org.witness.proofmode"
        minSdk = 28
        targetSdk = 36
        versionCode = 33010300
        versionName = "3.3.0-ALPHA-3"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.canRead()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            if (!keystoreProperties.stringPropertyNames().isEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.canRead()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            if (!keystoreProperties.stringPropertyNames().isEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Install side-by-side with the official release build.
            // Package becomes org.witness.proofmode.debug; FileProvider
            // authorities use ${applicationId}, so they adapt automatically.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
	    signingConfig = signingConfigs.getByName("release")
            applicationIdSuffix = ""
            versionNameSuffix = ""
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "org.bitcoin.production.checkpoints",
                "org.bitcoin.test.checkpoints",
                "org/bitcoinj/crypto/cacerts",
                "org/bitcoinj/crypto/mnemonic/wordlist/english.txt",
                "lib/x86_64/darwin/libscrypt.dylib",
                "com/google/thirdparty/publicsuffix/PublicSuffixType.gwt.xml",
                "com/google/thirdparty/publicsuffix/PublicSuffixPatterns.gwt.xml",
                "org/apache/commons/cli/AlreadySelectedException.class",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // Point at the build/ root so the flutter_assets/ subdirectory name is
        // preserved inside the APK — the Flutter engine resolves its kernel/snapshot
        // files via the "flutter_assets/" prefix and will fail to boot without it.
        // Test artifacts (test_cache, unit_test_assets, etc.) are deleted by the
        // buildFlutterBundle* tasks' doLast blocks before Gradle walks this directory.
        getByName("main").assets.directories.add("../flutter-location-protocol/build")
    }

    configurations.all {
        resolutionStrategy {
            // do not upgrade above 3.12.0 to support API < 21 while server uses
            // COMPATIBLE_TLS, or okhttp3 is used in project
            // force("com.squareup.okio:okio:3.2.0")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

val flutterModuleDir = rootProject.file("flutter-location-protocol")
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.canRead()) FileInputStream(f).use { load(it) }
}
val flutterExecutableFromLocalProperties = localProperties.getProperty("flutter.sdk")
    ?.takeIf { it.isNotBlank() }
    ?.let { sdkPath ->
        File(sdkPath, if (isWindows) "bin/flutter.bat" else "bin/flutter").absolutePath
    }
val flutterExecutable = flutterExecutableFromLocalProperties ?: if (isWindows) "flutter.bat" else "flutter"

val buildFlutterBundleDebug by tasks.registering(Exec::class) {
    group = "flutter"
    description = "Builds Flutter debug bundle assets (JIT kernel) for headless bridge runtime"
    workingDir = flutterModuleDir
    // --no-pub is load-bearing, not just a speedup: without it flutter_tools regenerates
    // flutter-location-protocol/.android from templates on every invocation, deleting the
    // directory Gradle is actively building out of. See the shim note in settings.gradle.
    // Run `flutter pub get` in flutter-location-protocol/ by hand after editing pubspec.yaml.
    commandLine(flutterExecutable, "build", "bundle", "--no-pub", "--debug", "--target", "lib/main.dart")
    doLast {
        // Use workingDir (this task's own property) rather than the script-level
        // flutterModuleDir val. Capturing a script-level val causes Kotlin to compile
        // the lambda with an implicit this\$0 ref to the script object, which the
        // configuration cache cannot serialize.
        val buildDir = File(workingDir, "build")
        buildDir.resolve("test_cache").deleteRecursively()       // ~64 MB test kernel cache
        buildDir.resolve("unit_test_assets").deleteRecursively() // generated by `flutter test`
        buildDir.resolve("native_assets").deleteRecursively()    // only for native plugin builds
        buildDir.walkTopDown()                                   // Makefile dep files (*.d)
            .filter { it.isFile && it.extension == "d" }
            .forEach { it.delete() }
    }
}

val buildFlutterBundleRelease by tasks.registering(Exec::class) {
    group = "flutter"
    description = "Builds Flutter release bundle assets (AOT snapshot) for headless bridge runtime"
    workingDir = flutterModuleDir
    // See --no-pub rationale on buildFlutterBundleDebug above.
    commandLine(flutterExecutable, "build", "bundle", "--no-pub", "--release", "--target", "lib/main.dart")
    doLast {
        val buildDir = File(workingDir, "build")
        buildDir.resolve("test_cache").deleteRecursively()
        buildDir.resolve("unit_test_assets").deleteRecursively()
        buildDir.resolve("native_assets").deleteRecursively()
        buildDir.walkTopDown()
            .filter { it.isFile && it.extension == "d" }
            .forEach { it.delete() }
    }
}

// Hook each Flutter build to its matching Android variant pre-build task.
// Using variant-specific hooks (preDefaultDebugBuild / preDefaultReleaseBuild)
// instead of the shared preBuild ensures the correct Flutter mode fires for
// each build type, and prevents double-firing when both variants are assembled.
tasks.matching { it.name == "preDefaultDebugBuild" }.configureEach {
    dependsOn(buildFlutterBundleDebug)
}
tasks.matching { it.name == "preDefaultReleaseBuild" }.configureEach {
    dependsOn(buildFlutterBundleRelease)
}

// Increments versionCode by ABI type
// Universal APK gets base versionCode, ABI-specific APKs get base + offset
val abiCodeMap = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 4, "x86_64" to 5)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val baseVersionCode = android.defaultConfig.versionCode ?: 0
            val abi = output.filters.find { it.filterType.toString() == "ABI" }?.identifier
            val abiCode = abiCodeMap[abi] ?: 0
            output.versionCode.set(baseVersionCode * 10 + abiCode)
        }
    }
}

base {
    archivesName.set("Proofmode-${android.defaultConfig.versionName}")
}

dependencies {
    implementation(libs.bundles.navigation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.robolectric:robolectric:4.13")

    implementation(project(":android-libproofmode"))
    implementation(project(":android-cid-lib"))
    implementation(project(":plugin-ipfs-cid"))
    implementation(project(":android-opentimestamps"))
    implementation(project(":android-nostr"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference.ktx)

    // Activity view
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.serialization.json)

    // Room database support (for activity view)
    implementation(libs.bundles.room)
    // Using KSP instead of KAPT for better performance
    ksp(libs.androidx.room.compiler)

    implementation(project(":android-libproofcam"))
    implementation(project(":plugin-location-protocol"))
    // ReLinker is a transitive dep of Flutter's embedding AAR. When Flutter is included as a
    // source project (evaluate include_flutter.groovy) the AAR's runtime deps are not
    // forwarded to the host APK, causing NoClassDefFoundError on FlutterEngineGroup init.
    implementation("com.getkeepsafe.relinker:relinker:1.4.5")
    implementation(libs.google.material)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.legacy.support.v13)

    // To be removed
    implementation(libs.appintro)

    implementation(libs.timber)

    implementation(libs.androidsvg)

    // implementation("com.google.android.gms:play-services-safetynet:18.0.1")
    implementation(libs.listenablefuture)

    // Required -- JUnit 4 framework
    androidTestImplementation(libs.junit)

    // Testing-only dependencies
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)

    // Crash reporting
    implementation(libs.bundles.acra)

    // Background service worker
    implementation(libs.bundles.work)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.zxing.core)

    // freeRASP SDK
    implementation(libs.talsecsecurity.community)

    // durindoor for PLAY RELEASE ONLY
    implementation(libs.durindoor)


}
