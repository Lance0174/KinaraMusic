buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // Kotlin 2.2 requires R8 >= 8.10.21; keep shrinking on with a compatible compiler.
        classpath("com.android.tools:r8:8.10.21")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
