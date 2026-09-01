pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.android.application") {
            useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":uhabits-android", ":uhabits-core", ":uhabits-server")
