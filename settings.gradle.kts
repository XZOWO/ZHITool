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
        // Lyricon（词幕生态）发布仓库
        maven("https://repo.fastmcmirror.org/content/repositories/releases/")
        maven("https://jitpack.io")
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "ZHITool"
include(":app")
