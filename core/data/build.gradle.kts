plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.eno2.openrgbremote.core.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Room's MigrationTestHelper reads the exported schemas from the test assets.
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    // SSH jump host. BouncyCastle provides Ed25519 and Curve25519, which Android's crypto lacks.
    implementation(libs.jsch)
    implementation(libs.bouncycastle.prov)
    ksp(libs.hilt.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":openrgb-sdk")))
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    // A real SSH server in the tests, for the jump host tunnel.
    testImplementation(libs.sshd.core)
    // Lets the test server offer Ed25519 host keys like OpenSSH (the app itself uses BouncyCastle).
    testImplementation(libs.eddsa)
}

// Same offline Robolectric runtime as in :app (see app/build.gradle.kts).
val robolectricRuntime: Configuration = configurations.create("robolectricRuntime") { isTransitive = false }
dependencies {
    robolectricRuntime(libs.robolectric.android.all)
}
val robolectricJarDir = layout.buildDirectory.dir("robolectric-runtime")
val copyRobolectricRuntime = tasks.register<Copy>("copyRobolectricRuntime") {
    from(robolectricRuntime)
    into(robolectricJarDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJarDir.get().asFile.absolutePath)
}
