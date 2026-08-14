plugins {
    id("com.android.library")
}

android {
    namespace = "dev.gfn.webrtc"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":gfn-identity"))
    implementation(project(":stream-core"))
    implementation(project(":stream-input"))
    implementation(project(":stream-signaling"))

    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    api("io.github.webrtc-sdk:android:144.7559.09")
}
