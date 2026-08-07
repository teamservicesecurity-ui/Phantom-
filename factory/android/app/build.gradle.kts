plugins {
    id("com.android.application")
}

android {
    namespace = "com.phantom.rat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phantom.rat"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
