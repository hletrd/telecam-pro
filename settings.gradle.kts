pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // OPPO Open Capability Service (CameraUnit SDK) — the official OEM extension SDK for
        // camera features such as stabilization modes. Credentials are the public read-only Maven
        // credentials OPPO documents for third-party developers (github.com/oppo/CameraUnit);
        // they live in gradle.properties (oppoOcsMaven*) so build scripts carry no credential
        // literals for secret scanners to trip on.
        maven {
            url = uri("https://maven.columbus.heytapmobi.com/repository/OpenCapability/")
            credentials {
                username = providers.gradleProperty("oppoOcsMavenUsername").getOrElse("ocuser")
                password = providers.gradleProperty("oppoOcsMavenPassword").getOrElse("")
            }
            content { includeGroup("com.oplus.ocs") }
        }
    }
}

rootProject.name = "FindX9TeleCamera"
include(":app")
