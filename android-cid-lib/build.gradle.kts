import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.cid"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("test") {
            resources.srcDirs("src/test/resources")
        }
        getByName("androidTest") {
            resources.srcDirs("src/test/resources")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.register("verifyRustCidLib") {
    val manifestFile = layout.projectDirectory.file("rust-cid-lib/rust-cid-lib-hashes.txt")
    val jniLibsTree = fileTree(layout.projectDirectory.dir("src/main/jniLibs")) {
        include("**/librust_cid_lib.so")
    }
    val pathPrefix = "android-cid-lib"
    inputs.file(manifestFile)
    inputs.files(jniLibsTree)
    outputs.upToDateWhen { false }
    doLast {
        val expected = manifestFile.asFile.readLines().associate { line ->
            val (hash, path) = line.split("  ", limit = 2)
            path.trim() to hash.trim()
        }
        jniLibsTree.files.forEach { so ->
            val abi = so.parentFile.name
            val rel = "$pathPrefix/src/main/jniLibs/$abi/librust_cid_lib.so"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(so.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected[rel]) { "Hash mismatch for $rel" }
        }
    }
}

tasks.register("verifyUniffiBindings") {
    val manifestFile = layout.projectDirectory.file("rust-cid-lib/rust-cid-lib-uniffi-hashes.txt")
    val uniffiDir = layout.projectDirectory.dir("src/main/java/org/witness/proofmode/cid/uniffi")
    val pathPrefix = "android-cid-lib"
    inputs.file(manifestFile)
    inputs.dir(uniffiDir)
    outputs.upToDateWhen { false }
    doLast {
        val expected = manifestFile.asFile.readLines().associate { line ->
            val (hash, path) = line.split("  ", limit = 2)
            path.trim() to hash.trim()
        }
        uniffiDir.asFile.walkTopDown().filter { it.extension == "kt" }.forEach { kt ->
            val rel = "$pathPrefix/src/main/java/org/witness/proofmode/cid/uniffi/${kt.name}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(kt.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected[rel]) { "UniFFI hash mismatch for $rel" }
        }
    }
}

tasks.named("preBuild") { dependsOn("verifyRustCidLib", "verifyUniffiBindings") }
