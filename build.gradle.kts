// Root build file: plugins are declared here (not applied) so that every module
// resolves the same plugin versions from the version catalog.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// All unit tests, debug variants only (`test` would also run every Android module's release
// unit tests, which doubles the time without testing anything new).
tasks.register("unitTests") {
    group = "verification"
    description = "Runs the unit tests of all modules."
    dependsOn(":openrgb-sdk:test", ":core:effects:test", ":core:domain:test", ":core:data:testDebugUnitTest", ":app:testDebugUnitTest")
}
