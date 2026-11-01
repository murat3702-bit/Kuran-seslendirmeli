import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksFile = rootProject.file("keystore.properties")
val ksProps = Properties().apply {
    if (ksFile.exists()) ksFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mb.kuranmealleri"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mb.kuran.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2             // yapı (build) numarası
        versionName = "28"
    }

    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.webkit:webkit:1.11.0")
}
