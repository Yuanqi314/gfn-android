buildscript {
    dependencies {
        // AGP 9.x built-in Kotlin uses KGP internally. Pin the same compiler version used by
        // JVM modules and the Compose Compiler plugin so the whole build uses one Kotlin line.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
