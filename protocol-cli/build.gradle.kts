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
    implementation(project(":gfn-identity"))
    implementation(project(":gfn-session"))
    implementation(project(":gfn-cloudmatch"))
    implementation(project(":core-network"))
    implementation(project(":diagnostics"))
}
