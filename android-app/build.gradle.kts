buildscript {
    dependencies { classpath("org.jetbrains:annotations:13.0") }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
