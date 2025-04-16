plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("androidx.navigation.safeargs")
}

android {
    compileSdk = 35
    namespace = "org.witness.proofmode.camera"

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    kotlinOptions {
        jvmTarget = "17"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {


    implementation("androidx.compose.foundation:foundation-android:1.7.0")
    val cameraxVersion = "1.5.0-alpha06"
    val nav_version = "2.8.9"
    val accompanist =  "0.36.0"
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // Optional - Add full set of material icons
    implementation("androidx.compose.material:material-icons-extended")
    // Optional - Add window size utils
    implementation("androidx.compose.material3.adaptive:adaptive")

    // Optional - Integration with activities
    implementation("androidx.activity:activity-compose:1.10.1")
    // Optional - Integration with ViewModels
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:$nav_version")
    implementation ("androidx.constraintlayout:constraintlayout-compose:1.1.1")
    implementation(kotlin("stdlib"))

    implementation(project(":android-libproofmode"))

    //new C2PA content authenticity support
    //api ("info.guardianproject:simple_c2pa:0.0.14")
   // implementation ("net.java.dev.jna:jna:5.13.0@aar")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")


    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-compose:$cameraxVersion")

    implementation("com.google.accompanist:accompanist-permissions:$accompanist")

    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.6.0")

    implementation("io.coil-kt:coil:2.2.2"){
    }
    implementation("io.coil-kt:coil-video:2.2.2") {
    }
    implementation("io.coil-kt:coil-compose:2.2.2"){
    }


    //logging
    implementation ("com.jakewharton.timber:timber:5.0.1")

}
