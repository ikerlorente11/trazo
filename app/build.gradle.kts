import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version from the release tag (vX.Y.Z -> -PversionName=X.Y.Z); versionCode = X*10000 + Y*100 + Z.
val appVersion = (findProperty("versionName") as String?) ?: "0.1.0"
val appCode = appVersion.split('.').map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
    .let { it[0] * 10000 + it[1] * 100 + it[2] }

// Release key: keystore.properties locally, TRAZO_* env vars in CI. The same key always, or updates won't install.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun key(name: String, env: String): String? = keyProps.getProperty(name) ?: System.getenv(env)

android {
    namespace = "dev.pistation.trazo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pistation.trazo"
        minSdk = 30
        targetSdk = 35
        versionCode = appCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            val file = key("storeFile", "TRAZO_KEYSTORE")
            if (file != null) {
                storeFile = file(file)
                storePassword = key("storePassword", "TRAZO_KEYSTORE_PASSWORD")
                keyAlias = key("keyAlias", "TRAZO_KEY_ALIAS") ?: "trazo"
                keyPassword = key("keyPassword", "TRAZO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
        // Same key as releases when it is available, so a local build can replace the Obtainium one.
        debug { if (key("storeFile", "TRAZO_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
}
