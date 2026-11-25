import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val defaultTraktClientId: String = localProps.getProperty("TRAKT_CLIENT_ID", "")
val defaultTraktClientSecret: String = localProps.getProperty("TRAKT_CLIENT_SECRET", "")
val defaultSimklClientId: String = localProps.getProperty("SIMKL_CLIENT_ID", "")
val defaultTmdbApiKey: String = localProps.getProperty("TMDB_API_KEY", "")

val userHome: String = System.getProperty("user.home") ?: ""
val signingDirCandidates = listOf(
    File("$userHome/Documents/Android Development/Android Signing"),
    File("$userHome/Documents/Android Signing")
)
val activeSigningDir = signingDirCandidates.firstOrNull {
    File(it, "burbsklopek.jks").isFile && File(it, "burbsklopek.jks.pass").isFile
}

android {
    namespace = "com.burbsklopek.flickdeck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.burbsklopek.sway"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.7"

        buildConfigField("String", "DEFAULT_TRAKT_CLIENT_ID", "\"$defaultTraktClientId\"")
        buildConfigField("String", "DEFAULT_TRAKT_CLIENT_SECRET", "\"$defaultTraktClientSecret\"")
        buildConfigField("String", "DEFAULT_SIMKL_CLIENT_ID", "\"$defaultSimklClientId\"")
        buildConfigField("String", "DEFAULT_TMDB_API_KEY", "\"$defaultTmdbApiKey\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (activeSigningDir != null) {
            create("burbsRelease") {
                storeFile = File(activeSigningDir, "burbsklopek.jks")
                val pass = File(activeSigningDir, "burbsklopek.jks.pass").readText().trim()
                storePassword = pass
                keyAlias = "burbsklopek"
                keyPassword = pass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Sway Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (activeSigningDir != null) {
                signingConfigs.getByName("burbsRelease")
            } else {
                signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
