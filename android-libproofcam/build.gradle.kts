plugins {
    id("com.android.library")
    id ("com.google.devtools.ksp") version("2.2.20-2.0.3")
    id("androidx.navigation.safeargs")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.camera"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {

    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.compose.foundation:foundation-android:1.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    val cameraxVersion = "1.5.1"
    val nav_version = "2.9.5"
    val accompanist =  "0.37.3"
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // Optional - Add full set of material icons
    //implementation("androidx.compose.material:material-icons-extended")
    // Optional - Add window size utils
    implementation("androidx.compose.material3.adaptive:adaptive")

    // Optional - Integration with activities
    implementation("androidx.activity:activity-compose:1.11.0")
    // Optional - Integration with ViewModels
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:$nav_version")
    implementation ("androidx.constraintlayout:constraintlayout-compose:1.1.1")
    implementation(kotlin("stdlib"))

    implementation(project(":android-libproofmode"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.android.material:material:1.13.0")

   // implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")


    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-compose:$cameraxVersion")

    implementation("com.google.accompanist:accompanist-permissions:$accompanist")

    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    implementation("io.coil-kt:coil:2.2.2"){
    }
    implementation("io.coil-kt:coil-video:2.2.2") {
    }
    implementation("io.coil-kt:coil-compose:2.2.2"){
    }

    // Kotlin + coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    //logging
    implementation ("com.jakewharton.timber:timber:5.0.1")

}
