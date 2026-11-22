plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mahdi.uploader"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.mahdi.uploader"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.7"
    }

    signingConfigs {
        create("uploader") {
            storeFile = file("uploader.keystore")
            storePassword = "mahdi-uploader-2025"
            keyAlias = "uploader"
            keyPassword = "mahdi-uploader-2025"
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
            signingConfig = signingConfigs.getByName("uploader")
        }
        debug {
            signingConfig = signingConfigs.getByName("uploader")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
