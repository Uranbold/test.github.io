import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ------------------------------------------------------------------------------------------------ paths
val repoRoot: File = rootProject.projectDir.resolve("../..").canonicalFile
val webDir: File = repoRoot.resolve("web")
val tokensFile: File = repoRoot.resolve("docs/design/tokens.json")
val glossaryFile: File = repoRoot.resolve("docs/requirements/glossary.md")
val maneuverFixture: File = webDir.resolve("src/route/maneuvers.fixture.json")
// NAV-011 AC 5 / ADR-0012 §2: the shared ADR-0006 query-assistance vectors (read by web and Android tests).
val queryPlanVectors: File = webDir.resolve("src/search/queryPlan.vectors.json")

// ------------------------------------------------------------------------------------------------ gateway URL (A7)
// ADR-0009 §11: Gradle property nav.gatewayBaseUrl → env NAV_GATEWAY_BASE_URL → uncommitted gateway.local.properties
// → debug default http://127.0.0.1:8080 (adb reverse tcp:8080 tcp:8080). Release builds fail unless https://.
// No server hostname or IP is ever committed (AC 66).
val debugDefaultGateway = "http://127.0.0.1:8080"
val configuredGateway: String? = run {
    val fromProperty = providers.gradleProperty("nav.gatewayBaseUrl").orNull
    val fromEnv = providers.environmentVariable("NAV_GATEWAY_BASE_URL").orNull
    val localFile = rootProject.file("gateway.local.properties")
    val fromFile = if (localFile.isFile) {
        Properties().apply { localFile.inputStream().use { load(it) } }.getProperty("nav.gatewayBaseUrl")
    } else {
        null
    }
    listOf(fromProperty, fromEnv, fromFile).firstOrNull { !it.isNullOrBlank() }?.trim()?.trimEnd('/')
}

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "mn.navmn.app"
    compileSdk = 36

    defaultConfig {
        // Placeholder application ID (D64): the final ID is fixed before any Play upload.
        applicationId = "mn.navmn.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-nav005"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GATEWAY_BASE_URL", quoted(configuredGateway ?: debugDefaultGateway))
            buildConfigField("boolean", "DEBUG_LOGS", "true")
        }
        release {
            // Not minified in this slice (no store upload, D17); R8 rules for JNA/UniFFI come with NAV-012.
            isMinifyEnabled = false
            buildConfigField("String", "GATEWAY_BASE_URL", quoted(configuredGateway ?: ""))
            buildConfigField("boolean", "DEBUG_LOGS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ferrostar core requires core library desugaring (kotlinx-datetime; AAR metadata).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // values/ = Mongolian (default), values-en/ = English (ADR-0009 §8). Nothing else is packaged.
        localeFilters += listOf("mn", "en")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = false
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // Identical key sets in values/ (mn) and values-en/ (en) are enforced (AC 61).
        error += listOf("MissingTranslation", "ExtraTranslation", "HardcodedText", "SetTextI18n")
        // The guidance screen keeps the screen on deliberately (AC 18).
        disable += listOf("Wakelock", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

// Release builds need an https gateway URL (AC 66, ADR-0009 §11). Checked when a release task runs, so debug builds
// and unit tests never need a URL.
val checkReleaseGatewayUrl by tasks.registering {
    group = "verification"
    description = "Fails unless nav.gatewayBaseUrl (or NAV_GATEWAY_BASE_URL / gateway.local.properties) is https://"
    val url = configuredGateway
    doLast {
        if (url == null || !url.startsWith("https://")) {
            throw GradleException(
                "Release builds need an https gateway URL: set -Pnav.gatewayBaseUrl=https://…, NAV_GATEWAY_BASE_URL " +
                    "or nav.gatewayBaseUrl in mobile/android/gateway.local.properties (currently: ${url ?: "unset"})",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseGatewayUrl) }

// ------------------------------------------------------------------------------------------------ generated sources
/** Kotlin colours from docs/design/tokens.json (no copied hex values; ADR-0009 §6, ADR-0004 Amendment 1). */
abstract class GenerateTokenColours : DefaultTask() {
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(tokens.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val color = root["color"] as Map<String, Map<String, Any?>>
        val version = ((root["meta"] as Map<*, *>)["version"]).toString()
        fun leaves(node: Map<*, *>, prefix: String, out: MutableMap<String, String>) {
            for ((k, v) in node) {
                val key = k.toString()
                if (key.startsWith("$")) continue
                if (v is Map<*, *> && v.containsKey("\$value")) out[prefix + key] = v["\$value"].toString()
                else if (v is Map<*, *>) leaves(v, "$prefix$key.", out)
            }
        }
        fun ident(path: String): String = path.split('.', '-').mapIndexed { i, p ->
            if (i == 0) p else p.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        fun argb(value: String): String {
            val v = value.trim()
            val hex = Regex("^#([0-9A-Fa-f]{6})$").find(v)
            if (hex != null) return "0xFF" + hex.groupValues[1].uppercase()
            val rgba = Regex("^rgba\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*([0-9.]+)\\)$").find(v)
                ?: throw GradleException("Unsupported colour value in tokens.json: $value")
            val (r, g, b, a) = rgba.destructured
            val alpha = Math.round(a.toDouble() * 255).toInt()
            return "0x%02X%02X%02X%02X".format(alpha, r.toInt(), g.toInt(), b.toInt())
        }
        val groups = listOf("ui", "nav", "route", "pin", "location")
        val modes = mapOf("light" to "Day", "night" to "Night")
        val perMode = modes.keys.associateWith { mode ->
            val out = linkedMapOf<String, String>()
            for (g in groups) leaves(color.getValue(mode)[g] as Map<*, *>, "$g.", out)
            out
        }
        val keys = perMode.getValue("light").keys
        require(keys == perMode.getValue("night").keys) { "tokens.json light/night key sets differ" }
        val sb = StringBuilder()
        sb.appendLine("// GENERATED from docs/design/tokens.json v$version by :app:generateTokenColours. Do not edit.")
        sb.appendLine("package mn.navmn.app.ui.theme")
        sb.appendLine()
        sb.appendLine("/** ARGB colour tokens (color.<mode>.{${groups.joinToString(",")}}). */")
        sb.appendLine("data class TokenColours(")
        for (k in keys) sb.appendLine("    val ${ident(k)}: Long,")
        sb.appendLine(")")
        sb.appendLine()
        sb.appendLine("object Tokens {")
        sb.appendLine("    const val VERSION: String = \"$version\"")
        for ((mode, name) in modes) {
            sb.appendLine("    val $name: TokenColours = TokenColours(")
            for ((k, v) in perMode.getValue(mode)) sb.appendLine("        ${ident(k)} = ${argb(v)},")
            sb.appendLine("    )")
        }
        sb.appendLine("}")
        val dir = outputDir.get().asFile.resolve("mn/navmn/app/ui/theme")
        dir.mkdirs()
        dir.resolve("TokenColours.kt").writeText(sb.toString())
    }
}

val generateTokenColours by tasks.registering(GenerateTokenColours::class) {
    tokens.set(tokensFile)
    outputDir.set(layout.buildDirectory.dir("generated/tokens/kotlin"))
}

/** Glyphs and sprites: the vendored web copy (ADR-0004 §4), copied at build time, never committed twice (ADR-0009 §6). */
abstract class SyncBasemapAssets : DefaultTask() {
    @get:InputDirectory abstract val fontsDir: DirectoryProperty
    @get:InputDirectory abstract val spritesDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        fs.sync {
            into(outputDir)
            from(fontsDir) {
                into("fonts")
                include("Noto Sans Regular/**", "Noto Sans Medium/**", "Noto Sans Italic/**", "OFL.txt")
            }
            from(spritesDir) { into("sprites") }
        }
    }
}

val syncBasemapAssets by tasks.registering(SyncBasemapAssets::class) {
    fontsDir.set(webDir.resolve("public/fonts"))
    spritesDir.set(webDir.resolve("public/sprites"))
    outputDir.set(layout.buildDirectory.dir("generated/basemapAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateTokenColours, GenerateTokenColours::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(syncBasemapAssets, SyncBasemapAssets::outputDir)
    }
}

// ------------------------------------------------------------------------------------------------ test inputs
// AC 29 / ADR-0009 §7: the shared manoeuvre fixture stays in web/; Gradle copies exactly that file (plus the
// glossary and tokens for the resource checks) into a generated test-resources directory.
val sharedTestResourcesDir = layout.buildDirectory.dir("generated/sharedTestResources")
val syncSharedTestResources by tasks.registering(Sync::class) {
    from(maneuverFixture) { into("shared") }
    from(queryPlanVectors) { into("shared") }
    from(glossaryFile) { into("shared") }
    from(tokensFile) { into("shared") }
    into(sharedTestResourcesDir)
}
android.sourceSets.getByName("test").resources.srcDir(sharedTestResourcesDir)
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }
    .configureEach { dependsOn(syncSharedTestResources) }

// ADR-0009 §10 / M-1: the real Ferrostar core on the host JVM. tools/build-host-ferrostar.sh builds libferrostar from
// the crates.io source into a cache outside the repo; unit tests point JNA at it. -Pnav.hostFerrostar=off skips it
// (the Ferrostar-backed tests are then skipped, never faked silently: HostFerrostar.require() reports it).
val ferrostarVersion: String = libs.versions.ferrostar.get()
val hostFerrostarCache: File = File(
    providers.environmentVariable("NAV_FERROSTAR_HOST_CACHE").orNull
        ?: ((providers.environmentVariable("XDG_CACHE_HOME").orNull ?: (System.getProperty("user.home") + "/.cache")) +
            "/navmn/ferrostar-host"),
)
val hostFerrostarMode: String = providers.gradleProperty("nav.hostFerrostar").orNull ?: "auto"
val buildHostFerrostar by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the host libferrostar for JVM tests (tools/build-host-ferrostar.sh)"
    onlyIf { hostFerrostarMode != "off" }
    commandLine(rootProject.file("tools/build-host-ferrostar.sh").absolutePath, ferrostarVersion)
    isIgnoreExitValue = hostFerrostarMode != "required"
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostFerrostar)
    systemProperty("jna.library.path", hostFerrostarCache.resolve(ferrostarVersion).absolutePath)
    systemProperty("nav.hostFerrostar", hostFerrostarMode)
    systemProperty("nav.repoRoot", repoRoot.absolutePath)
    // Opt-in live checks against the dev gateway (LiveGatewayTest), e.g. -Pnav.liveGateway=http://127.0.0.1:8080
    systemProperty("nav.liveGateway", providers.gradleProperty("nav.liveGateway").orNull ?: "")
    // Robolectric downloads android-all from Maven Central; use Google's mirror (ADR-0009 F12).
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    maxHeapSize = "3g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.ferrostar.core)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.viewmodel.compose)
    ksp(libs.hilt.compiler)
    // Dagger 2.58 reads Kotlin metadata with the kotlin-metadata-jvm on the processor path: match the compiler.
    ksp(libs.kotlin.metadata.jvm)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    kspTest(libs.kotlin.metadata.jvm)
    debugImplementation(libs.compose.ui.test.manifest)
    // Desktop JNA (with the linux/macOS dispatch library) for the host Ferrostar core; the AAR variant has none.
    testRuntimeOnly(libs.jna.jvm)
}
