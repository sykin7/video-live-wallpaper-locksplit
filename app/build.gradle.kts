plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scg.videowallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scg.videowallpaper.locksplit"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.1.8"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
