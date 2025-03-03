plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("androidx.navigation.safeargs")
}

android {
    compileSdk = 34
    namespace = "org.witness.proofmode.camera"

    defaultConfig {
        minSdk = 24
        targetSdk = 34
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {


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


    implementation("androidx.camera:camera-core:1.2.3")
    implementation("androidx.camera:camera-camera2:1.2.3")
    implementation("androidx.camera:camera-lifecycle:1.2.3")
    implementation("androidx.camera:camera-extensions:1.2.3")
    implementation("androidx.camera:camera-view:1.2.3")

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

    //logging
    implementation ("com.jakewharton.timber:timber:5.0.1")

}
