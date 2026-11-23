import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String): String = localProps.getProperty(name, "")

android {
    namespace = "dev.mixcloudtv"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.mixcloudtv"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MIXCLOUD_CLIENT_ID", "\"${localProp("MIXCLOUD_CLIENT_ID")}\"")
        buildConfigField("String", "MIXCLOUD_CLIENT_SECRET", "\"${localProp("MIXCLOUD_CLIENT_SECRET")}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = localProp("RELEASE_STORE_FILE")
            if (storePath.isNotEmpty()) {
                storeFile = file(storePath)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload app: use your own keystore if local.properties names one,
            // otherwise sign with the local debug key so the APK still installs.
            signingConfig = if (localProp("RELEASE_STORE_FILE").isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
