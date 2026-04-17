// Root-level build file — configuration that applies to ALL sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.ksp)                 apply false
}
