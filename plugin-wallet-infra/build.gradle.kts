import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

fun loadPropertiesFile(file: File): Properties = Properties().apply {
    if (file.canRead()) {
        FileInputStream(file).use { load(it) }
    }
}

val walletSecrets = Properties().apply {
    putAll(loadPropertiesFile(rootProject.file("secrets.defaults.properties")))
    putAll(loadPropertiesFile(rootProject.file("secrets.properties")))
    putAll(loadPropertiesFile(rootProject.file("local.properties")))
}

fun walletSecret(name: String): String = walletSecrets.getProperty(name).orEmpty()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    alias(libs.plugins.android.library)
    jacoco
}

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.plugins.wallet.infra"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 28
        buildConfigField("Boolean", "FEATURE_SPONSORSHIP_ENABLED", "true")
        buildConfigField("String", "PRIVY_APP_ID", buildConfigString(walletSecret("PRIVY_APP_ID")))
        buildConfigField("String", "PRIVY_APP_CLIENT_ID", buildConfigString(walletSecret("PRIVY_APP_CLIENT_ID")))
        buildConfigField("String", "ZERODEV_PROJECT_ID", buildConfigString(walletSecret("ZERODEV_PROJECT_ID")))
        buildConfigField("String", "ZERODEV_PROJECT_ID_BASE", buildConfigString(walletSecret("ZERODEV_PROJECT_ID_BASE")))
        buildConfigField("String", "ZERODEV_BUNDLER_URL_BASE", buildConfigString(walletSecret("ZERODEV_BUNDLER_URL_BASE")))
        buildConfigField("String", "ZERODEV_PAYMASTER_URL_BASE", buildConfigString(walletSecret("ZERODEV_PAYMASTER_URL_BASE")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Uncomment to produce a self-funded release variant:
            // buildConfigField("Boolean", "FEATURE_SPONSORSHIP_ENABLED", "false")
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
    implementation(libs.privy.android)
    implementation(libs.zerodev.aa)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.security.crypto)
    implementation(libs.timber)

    testImplementation(libs.androidx.test.core)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(libs.junit)
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

    sourceDirectories.setFrom(files("src/main/java"))
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

tasks.register<JacocoCoverageVerification>("verifyCoverageThresholds") {
    dependsOn("createCoverageReport")

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(coverageClassDirectories))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "**/*.exec",
                "**/*.ec",
            )
        }
    )

    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("org.witness.proofmode.plugins.wallet.infra.SendTransactionErrorMapper*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("org.witness.proofmode.plugins.wallet.infra.SendTransactionErrorMapper*")
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}
