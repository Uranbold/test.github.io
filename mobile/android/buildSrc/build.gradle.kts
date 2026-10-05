// NAV-019 (ADR-0016 §8.2): build logic shared by app/build.gradle.kts and the app's JVM unit tests. Plain Java with no
// dependencies, so nothing is resolved from a repository and the same source file is compiled into the app's testDebug
// source set (DemoTilesGuardTest).
plugins {
    java
}
