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

// Staging directories for the Flutter release (AOT) build. Declared up here
// because android.sourceSets below has to reference them by path at
// configuration time; the tasks that populate them are registered further down.
// Each is a separate directory: overlapping task outputs break Gradle's
// incremental checks for flavored single-ABI builds (flutter/flutter#186810).
val flutterAotReleaseDir = layout.buildDirectory.dir("flutter/aot/release").get().asFile
val flutterReleaseAssetsDir = layout.buildDirectory.dir("flutter/assets/release").get().asFile
val flutterReleaseJniLibsDir = layout.buildDirectory.dir("flutter/jniLibs/release").get().asFile

// ABI -> Flutter target-platform name. x86 is absent on purpose: Flutter
// publishes no x86_release engine, which is why flutter-android-build-shim.gradle
// omits it from releaseImplementation too.
val flutterReleaseAbis = mapOf(
    "armeabi-v7a" to "android-arm",
    "arm64-v8a" to "android-arm64",
    "x86_64" to "android-x64",
)

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
        versionCode = 33020500
        versionName = "3.3.0-BETA-5"
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
        // Debug (JIT): buildFlutterBundleDebug writes flutter_assets/kernel_blob.bin
        // here. Point at the build/ root so the flutter_assets/ subdirectory name is
        // preserved inside the APK — the Flutter engine resolves its kernel/snapshot
        // files via the "flutter_assets/" prefix and will fail to boot without it.
        // Test artifacts (test_cache, unit_test_assets, etc.) are deleted by the
        // buildFlutterBundleDebug task's doLast block before Gradle walks this directory.
        //
        // This is scoped to debug rather than main on purpose: the release engine
        // cannot execute a JIT kernel, so shipping this directory in the release APK
        // would only add a few MB of dead weight.
        getByName("debug").assets.directories.add("../flutter-location-protocol/build")

        // Release (AOT): staged by syncFlutterAssetsRelease / syncFlutterAotJniLibsRelease
        // from the `flutter assemble` output. libapp.so is the Dart snapshot the
        // release engine loads — without it FlutterJNI.performNativeAttach segfaults.
        getByName("release").assets.directories.add(flutterReleaseAssetsDir.path)
        getByName("release").jniLibs.directories.add(flutterReleaseJniLibsDir.path)
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

// `flutter build bundle --release` does NOT produce an AOT snapshot — it only
// emits flutter_assets (AssetManifest.bin, fonts, shaders) with no kernel_blob.bin
// and no app.so. The release engine (flutter_embedding_release + *_release .so,
// see flutter-android-build-shim.gradle) has no JIT and loads all Dart code from
// libapp.so, so a bundle-only release APK died at startup with:
//
//   [dart_vm_data.cc] VM snapshot invalid and could not be inferred from settings.
//   [dart_vm_lifecycle.cc] Could not create Dart VM instance.
//   Fatal signal 11 (SIGSEGV) ... io.flutter.embedding.engine.FlutterJNI.performNativeAttach
//
// Normally dev.flutter.flutter-gradle-plugin's FlutterTask runs this step, but the
// shim replaces that plugin (it NPEs under AGP 9), so we invoke `flutter assemble`
// with the same arguments and rule names the plugin uses. Output layout is
// <out>/<abi>/app.so plus <out>/flutter_assets/.
val flutterAssembleReleaseCommand = buildList {
    add(flutterExecutable)
    add("assemble")
    add("--no-version-check")
    add("--output=${flutterAotReleaseDir.path}")
    add("--depfile=${File(flutterAotReleaseDir, "flutter_build.d").path}")
    add("-dTargetFile=lib/main.dart")
    add("-dTargetPlatform=android")
    add("-dBuildMode=release")
    add("-dAndroidArchs=${flutterReleaseAbis.values.joinToString(" ")}")
    add("-dMinSdkVersion=${android.defaultConfig.minSdk}")
    add("-dTrackWidgetCreation=false")
    flutterReleaseAbis.values.forEach { add("android_aot_bundle_release_$it") }
}

val buildFlutterAotRelease by tasks.registering(Exec::class) {
    group = "flutter"
    description = "Compiles the Flutter module to a release AOT snapshot (app.so) plus flutter_assets"
    workingDir = flutterModuleDir
    commandLine(flutterAssembleReleaseCommand)
}

// Stage <out>/<abi>/app.so as <staging>/<abi>/libapp.so, the layout AGP expects
// from a jniLibs source directory. Mirrors the plugin's CopyFlutterJniLibsTask,
// including the native_assets passthrough for packages that ship native code.
val syncFlutterAotJniLibsRelease by tasks.registering(Sync::class) {
    group = "flutter"
    description = "Stages the Flutter AOT snapshot as <abi>/libapp.so for the release APK"
    dependsOn(buildFlutterAotRelease)
    into(flutterReleaseJniLibsDir)
    flutterReleaseAbis.keys.forEach { abi ->
        from(File(flutterAotReleaseDir, abi)) {
            include("*.so")
            rename { fileName -> "lib$fileName" }
            into(abi)
        }
        from(File(flutterAotReleaseDir, "native_assets/jniLibs/lib/$abi")) {
            include("*.so")
            into(abi)
        }
    }
}

// flutter_assets only — the sibling <abi>/ directories in the assemble output
// hold app.so, which belongs in jniLibs, not in assets.
val syncFlutterAssetsRelease by tasks.registering(Sync::class) {
    group = "flutter"
    description = "Stages the Flutter release flutter_assets/ for the release APK"
    dependsOn(buildFlutterAotRelease)
    into(flutterReleaseAssetsDir)
    from(flutterAotReleaseDir) {
        include("flutter_assets/**")
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
    dependsOn(syncFlutterAotJniLibsRelease, syncFlutterAssetsRelease)
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
