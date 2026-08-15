plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.gfn.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.gfn.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 20
        versionName = "0.6.0.2-dev"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "INPUT_FORENSICS_ENABLED", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "INPUT_FORENSICS_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-network"))
    implementation(project(":gfn-auth"))
    implementation(project(":gfn-account"))
    implementation(project(":gfn-games"))
    implementation(project(":gfn-cloudmatch"))
    implementation(project(":gfn-session"))
    implementation(project(":gfn-identity"))
    implementation(project(":diagnostics"))
    implementation(project(":stream-core"))
    implementation(project(":stream-signaling"))
    implementation(project(":stream-webrtc"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha25")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0-rc01")
}
