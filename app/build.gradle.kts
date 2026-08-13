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
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":gfn-identity"))
    implementation(project(":diagnostics"))
    implementation(project(":stream-core"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha25")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0-rc01")
}
