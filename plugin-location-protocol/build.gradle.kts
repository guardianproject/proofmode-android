import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.plugins.lp"

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":android-libproofmode"))
    implementation(project(":plugin-ipfs-cid"))
    implementation(project(":flutter"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.timber)
    implementation("androidx.annotation:annotation:1.8.2")

    implementation(project(":plugin-wallet-infra"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(libs.okhttp)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// unitTests.isIncludeAndroidResources merges the :flutter module's assets, which
// :flutter:copyFlutterAssets<Variant> writes into flutter-location-protocol/.android. Gradle
// can't infer that producer/consumer edge across the add-to-app build, so declare it.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "merge${variant}UnitTestAssets" }.configureEach {
        dependsOn(":flutter:copyFlutterAssets$variant")
    }
}

val coverageClassDirectories = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    exclude(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
    )
}

tasks.withType<JacocoReport>().configureEach {
    if (name != "createCoverageReport") return@configureEach

    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    classDirectories.setFrom(files(coverageClassDirectories))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "**/*.exec",
                "**/*.ec",
            )
        }
    )
}

