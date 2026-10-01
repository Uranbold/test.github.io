// NAV-005 Android app (ADR-0009). Every dependency comes from Google's Maven repository or Maven Central (AC 70).
// Google's mirror of Maven Central is listed first as a build-time host only (ADR-0009 §11, F12: Maven Central
// rate-limited this build machine); the app never contacts it.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

rootProject.name = "navmn-android"
include(":app")
