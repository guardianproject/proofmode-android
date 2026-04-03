import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.net.URI
import java.util.*

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
    }

    flavorDimensions += "default"

    productFlavors {
        create("artwork") {
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
        versionCode = 30230100
        versionName = "3.0.2-RC-2"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
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
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
	    signingConfig = signingConfigs.getByName("release")
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

    implementation(project(":android-libproofmode"))
    implementation(project(":android-opentimestamps"))

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
    implementation(libs.google.material)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.legacy.support.v13)

    // To be removed
    implementation(libs.appintro)

    implementation(libs.timber)

    implementation(libs.androidsvg)

    implementation(libs.tedimagepicker)

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

    implementation(libs.zxing.core)

}
