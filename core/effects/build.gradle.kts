import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Effect engine without Android dependencies: renderers are pure functions of
// (time, LED geometry, parameters, audio levels), so they are deterministic and testable.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Only the immutable device model (zones, matrix maps) is used to build LED layouts.
    api(project(":openrgb-sdk"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":openrgb-sdk")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
