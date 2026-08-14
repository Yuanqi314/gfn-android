plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.gfn.protocol.MainKt")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-network"))
    implementation(project(":gfn-auth"))
    implementation(project(":gfn-account"))
    implementation(project(":gfn-games"))
    implementation(project(":gfn-identity"))
    implementation(project(":gfn-session"))
    implementation(project(":gfn-cloudmatch"))
    implementation(project(":diagnostics"))
    implementation(project(":stream-signaling"))
    implementation(project(":stream-input"))
}
