plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// CI passes the version (see .github/workflows/build.yml); local builds use the defaults.
val ciVersionCode = providers.environmentVariable("VERSION_CODE").map { it.toInt() }
val ciVersionName = providers.environmentVariable("VERSION_NAME")

// Release signing: a keystore from the environment (CI secrets), otherwise the debug key so the
// release build can still be installed for testing. Such an APK cannot update one signed with
// the real key and vice versa.
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE_FILE")

android {
    namespace = "dev.eno2.openrgbremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.eno2.openrgbremote"
        minSdk = 26
        targetSdk = 37
        versionCode = ciVersionCode.getOrElse(1)
        versionName = ciVersionName.getOrElse("0.1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Installed next to the release build; src/debug renames it in the launcher.
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Versions are managed in the catalog; this check queries Maven Central on every run.
        disable += "NewerVersionAvailable"
        warningsAsErrors = true
        abortOnError = true
    }
    androidResources {
        // Only ship the languages the app is translated into.
        localeFilters += listOf("en", "de")
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(testFixtures(project(":openrgb-sdk")))
    testImplementation(testFixtures(project(":core:domain")))
}

// Robolectric normally downloads its Android runtime itself at test time, without caching or
// retries. Resolving it through Gradle instead makes unit tests work offline and in CI.
// The artifact version must match what the Robolectric version uses for the configured SDK
// (robolectric.properties: sdk=36).
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
