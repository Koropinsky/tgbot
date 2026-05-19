plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.familybot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // Драйвер для підключення до бази даних PostgreSQL
    implementation("org.postgresql:postgresql:42.7.2")
    implementation("com.google.code.gson:gson:2.10.1")
}

application {
    mainClass.set("MainKt")
}