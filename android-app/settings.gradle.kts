pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // An optional local mirror keeps offline development possible without
        // making generated dependency artifacts part of the public repository.
        if (file("../android-local-repo").isDirectory) {
            maven { url = uri("../android-local-repo") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "PhoneMouse"
include(":app")
