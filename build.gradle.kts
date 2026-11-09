// Root build file: plugins are declared here (not applied) so that every module
// resolves the same plugin versions from the version catalog.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
