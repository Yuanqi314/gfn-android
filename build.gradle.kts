buildscript {
    dependencies {
        // AGP 9.x built-in Kotlin、JVM 模块与 Compose Compiler 统一使用同一 Kotlin 版本。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
