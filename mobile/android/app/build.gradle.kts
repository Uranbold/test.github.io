import groovy.json.JsonSlurper
import java.util.Properties
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

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

// ------------------------------------------------------------------------------------------------ demo build (NAV-019)
// ADR-0016 §8.1: the basemap of the `demo` build type. Gradle property → environment → the uncommitted
// gateway.local.properties, like the gateway URL. Exactly one must be set for demo tasks (checkDemoTiles); no other task
// reads them. Values are never committed (AC 41): the README shows placeholders only.
fun navSetting(property: String, env: String): String? {
    val fromProperty = providers.gradleProperty(property).orNull
    val fromEnv = providers.environmentVariable(env).orNull
    val localFile = rootProject.file("gateway.local.properties")
    val fromFile = if (localFile.isFile) Properties().apply { localFile.inputStream().use { load(it) } }.getProperty(property) else null
    return listOf(fromProperty, fromEnv, fromFile).firstOrNull { !it.isNullOrBlank() }?.trim()
}
val demoTilesFile: String? = navSetting("nav.demoTilesFile", "NAV_DEMO_TILES_FILE")
val demoTilesUrl: String? = navSetting("nav.demoTilesUrl", "NAV_DEMO_TILES_URL")
// NAV-022 AC 47 / P14: the offline-pack base URL, i.e. the directory that holds `mn/manifest.json` (for a static host such as
// https://<host>/packs/). Gradle property → environment → the uncommitted gateway.local.properties, like the gateway URL.
// Unset: the app uses <gateway>/packs (openapi 0.6.0 getOfflinePackManifest). Never committed (no hostname in the repo).
val configuredPackBase: String? = navSetting("nav.packBaseUrl", "NAV_PACK_BASE_URL")?.trimEnd('/')
// NAV-019 AC 4 (ADR-0016 §8.3): the NAV-017 route manifest; its picker routes and tracks are copied at build time.
val demoManifestFile: File = webDir.resolve("src/demo/routes.manifest.json")

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
            buildConfigField("String", "PACK_BASE_URL", quoted(configuredPackBase.orEmpty())) // NAV-022 P14
            buildConfigField("boolean", "DEBUG_LOGS", "true")
            // B-NAV019-01: the last crash's stack trace is stored app-private and shown on the next launch (no adb needed).
            buildConfigField("boolean", "CRASH_DIAGNOSTICS", "true")
        }
        release {
            // Not minified in this slice (no store upload, D17); R8 rules for JNA/UniFFI come with NAV-012.
            isMinifyEnabled = false
            buildConfigField("String", "GATEWAY_BASE_URL", quoted(configuredGateway ?: ""))
            buildConfigField("String", "PACK_BASE_URL", quoted(configuredPackBase.orEmpty())) // NAV-022 P14
            buildConfigField("boolean", "DEBUG_LOGS", "false")
            buildConfigField("boolean", "CRASH_DIAGNOSTICS", "false")
        }
        // NAV-019 (ADR-0016 §2): the demo build for the PO's phone. Installs next to the debug app (.demo), signed with the
        // local debug key (nothing signing-related is committed), not debuggable, not minified (ADR-0013 B-A8 still open).
        // The gateway URL is a fixed loopback discard address that is never contacted (§7): nav.gatewayBaseUrl is ignored,
        // so a developer's gateway can never end up in the APK the PO receives.
        create("demo") {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += listOf("release")
            buildConfigField("String", "GATEWAY_BASE_URL", quoted("https://127.0.0.1:9"))
            // NAV-022 UX B7: the demo has no offline pack (0 pack requests); nav.packBaseUrl is ignored like the gateway URL.
            buildConfigField("String", "PACK_BASE_URL", quoted(""))
            buildConfigField("boolean", "DEBUG_LOGS", "false")
            buildConfigField("boolean", "CRASH_DIAGNOSTICS", "true") // B-NAV019-01: the PO's phone has no adb
            buildConfigField("String", "DEMO_TILES_URL", quoted(if (demoTilesFile.isNullOrBlank()) demoTilesUrl.orEmpty() else ""))
        }
    }

    // ADR-0016 §2: src/replay is pure Kotlin (no Android types, no Hilt modules). It is compiled into the demo build and
    // into the debug unit tests, so the replay tests run in the existing testDebugUnitTest. src/demo is the demo build
    // type's own source set (Android, Hilt, UI, resources, manifest overlay).
    sourceSets {
        getByName("demo") { java.srcDir("src/replay/java") }
        getByName("testDebug") {
            java.srcDir("src/replay/java")
            // The Gradle guard (AC 3) is the same source file the build script runs (DemoTilesGuardTest).
            java.srcDir(rootProject.file("buildSrc/src/main/java"))
        }
        // NAV-019 TC-B19-01/02: testDemoUnitTest (only enabled with nav.demoTilesFile, see androidComponents below) also
        // compiles src/test, whose DemoBuildTest needs the buildSrc guard. src/replay is already in the demo main sources.
        getByName("testDemo") { java.srcDir(rootProject.file("buildSrc/src/main/java")) }
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
        aidl = true // NAV-021 R2: IOnDeviceRouting (the :routing bound service)
    }

    androidResources {
        // values/ = Mongolian (default), values-en/ = English (ADR-0009 §8). Nothing else is packaged.
        localeFilters += listOf("mn", "en")
        // NAV-019 (ADR-0016 §8.3): the bundled demo archive stays uncompressed (streamed copy, known length).
        noCompress += "pmtiles"
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

// NAV-022 P14: a configured pack base URL must be https:// in release builds (debug may use loopback, AC 47).
val checkReleasePackUrl by tasks.registering {
    group = "verification"
    description = "Fails if nav.packBaseUrl is set but not https:// (release builds)"
    val url = configuredPackBase
    doLast {
        if (url != null && !url.startsWith("https://")) {
            throw GradleException("Release builds need an https pack base URL: nav.packBaseUrl is $url")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleasePackUrl) }

// NAV-019 AC 3 (ADR-0016 §8.2): demo tasks fail early unless exactly one basemap source is set. Bound to preDemoBuild only,
// so assembleDebug, assembleRelease and testDebugUnitTest never need the properties. The rule is navmn.buildlogic.DemoTilesGuard
// (buildSrc), the same source file the JVM test DemoTilesGuardTest runs.
val checkDemoTiles by tasks.registering {
    group = "verification"
    description = "Fails unless exactly one of nav.demoTilesFile / nav.demoTilesUrl is set (NAV-019 demo build)"
    val file = demoTilesFile
    val url = demoTilesUrl
    doLast {
        val problem = navmn.buildlogic.DemoTilesGuard.problem(file, url)
        if (problem != null) throw GradleException(problem)
    }
}
tasks.matching { it.name == "preDemoBuild" }.configureEach { dependsOn(checkDemoTiles) }

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
        val groups = listOf("ui", "nav", "route", "pin", "location", "demo")
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

/**
 * NAV-019 AC 4 (ADR-0016 §8.3): the demo build's assets, copied byte for byte from their repo paths at build time (no
 * copies under mobile/): `demo/routes.manifest.json` (the NAV-017 manifest), `demo/files/<repo path>` for the route and
 * track of every `picker: true` entry, and `demo/basemap.pmtiles` from nav.demoTilesFile (file mode only). Validated
 * like ADR-0011 §5: the manifest parses, ≥ 1 picker entry, every file exists, each route has code "Ok" and ≥ 1 route,
 * each track ≥ 2 timed <trkpt>. Registered for the demo build type only.
 */
abstract class SyncDemoAssets : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:Input abstract val rootDirPath: Property<String>
    @get:InputFiles abstract val pickerFiles: ConfigurableFileCollection
    @get:Optional @get:InputFile abstract val tiles: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val root = File(rootDirPath.get())
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val demo = out.resolve("demo").apply { mkdirs() }
        val manifestFile = manifest.get().asFile
        @Suppress("UNCHECKED_CAST")
        val m = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val entries = (m["routes"] as? List<Map<String, Any?>>).orEmpty().filter { it["picker"] == true }
        if (entries.isEmpty()) throw GradleException("NAV-019: ${manifestFile.name} has no picker entry")
        manifestFile.copyTo(demo.resolve("routes.manifest.json"), overwrite = true)
        val trkpt = Regex("<trkpt\\b[^>]*>[\\s\\S]*?<time>[^<]+</time>[\\s\\S]*?</trkpt>")
        for (e in entries) {
            val id = e["id"]
            for (key in listOf("route", "track")) {
                val rel = e[key] as? String ?: throw GradleException("NAV-019: manifest entry $id has no $key")
                val src = root.resolve(rel)
                if (!src.isFile) throw GradleException("NAV-019: manifest entry $id: $rel does not exist")
                if (key == "route") {
                    @Suppress("UNCHECKED_CAST")
                    val r = runCatching { JsonSlurper().parse(src) as Map<String, Any?> }.getOrNull()
                    val routes = r?.get("routes") as? List<*>
                    if (r?.get("code") != "Ok" || routes.isNullOrEmpty()) throw GradleException("NAV-019: $rel is not an OSRM response with code Ok")
                } else if (trkpt.findAll(src.readText()).count() < 2) {
                    throw GradleException("NAV-019: $rel has fewer than 2 timed <trkpt>")
                }
                src.copyTo(demo.resolve("files/$rel"), overwrite = true)
            }
        }
        if (tiles.isPresent) tiles.get().asFile.copyTo(demo.resolve("basemap.pmtiles"), overwrite = true)
    }
}

val syncDemoAssets by tasks.registering(SyncDemoAssets::class) {
    manifest.set(demoManifestFile)
    rootDirPath.set(repoRoot.absolutePath)
    @Suppress("UNCHECKED_CAST")
    val pickerPaths = runCatching {
        ((JsonSlurper().parse(demoManifestFile) as Map<String, Any?>)["routes"] as List<Map<String, Any?>>)
            .filter { it["picker"] == true }
            .flatMap { listOfNotNull(it["route"] as? String, it["track"] as? String) }
    }.getOrDefault(emptyList())
    pickerFiles.from(pickerPaths.map { repoRoot.resolve(it) }.filter { it.isFile })
    val file = demoTilesFile
    if (!file.isNullOrBlank() && File(file).isFile) tiles.set(File(file))
    outputDir.set(layout.buildDirectory.dir("generated/demoAssets"))
    dependsOn(checkDemoTiles)
}

// NAV-019 review housekeeping (ADR-0016 known gap 8) with the NAV-019 bug-lane regression test kept runnable
// (NAV-005 test plan §8 Q9, option a). Without nav.demoTilesFile the demo build type has no unit-test variant, so
// ./gradlew test, check and build work with no demo properties (the replay code is tested in testDebugUnitTest). With
// nav.demoTilesFile (file mode, which TC-B19-02 asserts) testDemoUnitTest exists and runs only the demo-specific tests
// in src/testDemo (QaNav019DemoStartupTest), not the whole src/test suite against the demo bindings.
val demoUnitTestsEnabled: Boolean = !demoTilesFile.isNullOrBlank()
val demoOnlyTestRoot: File = file("src/testDemo/java")
val demoOnlyTestPatterns: List<String> = fileTree(demoOnlyTestRoot) { include("**/*.kt", "**/*.java") }.files
    .map { it.relativeTo(demoOnlyTestRoot).invariantSeparatorsPath.substringBeforeLast('.').replace('/', '.') + "*" }
    .sorted()
tasks.withType<Test>().matching { it.name == "testDemoUnitTest" }.configureEach {
    enabled = demoOnlyTestPatterns.isNotEmpty() // never fall back to running all of src/test under the demo bindings
    filter {
        isFailOnNoMatchingTests = true
        demoOnlyTestPatterns.forEach { includeTestsMatching(it) }
    }
}

// ------------------------------------------------------------------------------------------------ licences (NAV-005 P, Q)
// NAV-005 AC 88–98: the licences screen's data is generated, never written by hand. writeLicenceDeps records the resolved
// runtime classpath of every variant; checkThirdPartyNotices (part of every build through the asset tasks, and of
// `check`) runs tools/gen-third-party-notices.py --check, which fails on an artifact without a rule, an entry without a
// licence text or copyright line, a licence outside the allow-list, or a THIRD_PARTY_NOTICES.md that differs from the
// generated one, or a native library whose version differs from its pinned notice list (AC 96, ADR-0017 A5 §2).
// generate<Variant>LicenceAssets writes that variant's index and texts into its assets (AC 90, 98).
val licenceVariants = listOf("debug", "demo", "release")

fun runtimeCoordinates(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<String> {
    val out = sortedSetOf<String>()
    val visited = HashSet<org.gradle.api.artifacts.component.ComponentIdentifier>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (!visited.add(c.id)) continue
        (c.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { out += "${it.group}:${it.module}:${it.version}" }
        c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { queue += it.selected }
    }
    return out.toList()
}

abstract class WriteLicenceDeps : DefaultTask() {
    @get:Input abstract val variants: MapProperty<String, List<String>>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(variants.get().toSortedMap())) + "\n")
    }
}

val writeLicenceDeps by tasks.registering(WriteLicenceDeps::class) {
    group = "verification"
    description = "Writes the resolved runtime classpath of every variant for tools/gen-third-party-notices.py (NAV-005 AC 90, 96)"
    for (v in licenceVariants) {
        variants.put(v, configurations.named("${v}RuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }.map(::runtimeCoordinates))
    }
    output.set(layout.buildDirectory.file("licences/deps.json"))
}

/** Runs a Python tool of mobile/android/tools; a non-zero exit fails the build with the tool's output (never a skip). */
abstract class PythonCheck : DefaultTask() {
    @get:Input abstract val toolArgs: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val toolInputs: ConfigurableFileCollection
    @get:Input @get:Optional abstract val androidHome: Property<String>
    @get:Input abstract val failureTitle: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val out = ByteArrayOutputStream()
        val result = try {
            exec.exec {
                commandLine(listOf("python3") + toolArgs.get())
                androidHome.orNull?.let { environment("ANDROID_HOME", it) }
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            throw GradleException("${failureTitle.get()}: python3 could not be started (${e.message}). Python 3 is required; this check is never skipped.")
        }
        val text = out.toString(Charsets.UTF_8)
        report.get().asFile.writeText(text)
        if (result.exitValue != 0) throw GradleException("${failureTitle.get()} (exit ${result.exitValue}):\n$text")
        logger.lifecycle(text.trim().lines().lastOrNull().orEmpty())
    }
}

val licenceToolInputs = files(
    rootProject.file("tools/gen-third-party-notices.py"),
    rootProject.file("THIRD_PARTY_NOTICES.md"),
    rootProject.file("licenses"),
    webDir.resolve("licenses"),
    webDir.resolve("public/fonts/OFL.txt"),
    webDir.resolve("public/fonts/BASEMAPS_ASSETS_COMMIT"),
    webDir.resolve("package.json"),
)

val checkThirdPartyNotices by tasks.registering(PythonCheck::class) {
    group = "verification"
    description = "Fails when THIRD_PARTY_NOTICES.md or the licence rules drift from the shipped runtime classpath (NAV-005 AC 96)"
    val deps = writeLicenceDeps.flatMap { it.output }
    toolInputs.from(licenceToolInputs, deps)
    toolArgs.set(deps.map { listOf(rootProject.file("tools/gen-third-party-notices.py").absolutePath, "--check", "--deps", it.asFile.absolutePath) })
    failureTitle.set("checkThirdPartyNotices failed (NAV-005 AC 96)")
    report.set(layout.buildDirectory.file("licences/check.txt"))
}

// AC 98 release gate (ADR-0017 A5 §1): while a shipped licence waits for the NAV-005 AC 96 amendment (PENDING_AC96 in the
// generator), packaging a release APK or bundle fails. `check`, debug and demo builds are not blocked.
val checkReleaseLicenceGate by tasks.registering(PythonCheck::class) {
    group = "verification"
    description = "Fails while a shipped licence is outside the NAV-005 AC 96 allow-list (release packaging only, AC 98)"
    val deps = writeLicenceDeps.flatMap { it.output }
    toolInputs.from(licenceToolInputs, deps)
    toolArgs.set(deps.map { listOf(rootProject.file("tools/gen-third-party-notices.py").absolutePath, "--release-gate", "--deps", it.asFile.absolutePath) })
    failureTitle.set("checkReleaseLicenceGate failed (NAV-005 AC 96, AC 98)")
    report.set(layout.buildDirectory.file("licences/release-gate.txt"))
}
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(checkReleaseLicenceGate) }

val testThirdPartyNoticesGenerator by tasks.registering(PythonCheck::class) {
    group = "verification"
    description = "Self-test of tools/gen-third-party-notices.py: an unmapped library, a disallowed licence, a native version bump without a new list and a pending licence at the release gate fail (NAV-005 AC 96)"
    toolInputs.from(rootProject.file("tools/gen-third-party-notices.py"))
    toolArgs.set(listOf(rootProject.file("tools/gen-third-party-notices.py").absolutePath, "--self-test"))
    failureTitle.set("testThirdPartyNoticesGenerator failed (NAV-005 AC 96)")
    report.set(layout.buildDirectory.file("licences/self-test.txt"))
}

abstract class GenerateLicenceAssets : DefaultTask() {
    @get:Input abstract val variantName: Property<String>
    @get:InputFile abstract val deps: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:InputFile abstract val generator: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        exec.exec {
            commandLine("python3", generator.get().asFile.absolutePath, "--deps", deps.get().asFile.absolutePath, "--variant", variantName.get(), "--assets", out.absolutePath)
        }
    }
}

// NAV-005 AC 99–100 (bug B-NAV012-01): tools/apk_api_level_types.py on every APK a build produces. assemble<Variant> is
// finalized by checkApkApiLevelTypes<Variant>, so `assembleDebug` (one of the mobile checks, AC 71) runs it on the debug
// APK and `assembleDemo` on the demo APK; `check` depends on the debug one. The checker's self-test (B-NAV012-01 fixture
// exits 1, the ...Api31 holder exits 0, missing tools exit 2) runs first. A missing tool fails the task, never skips it.
val androidSdkDir: Provider<String> = androidComponents.sdkComponents.sdkDirectory.map { it.asFile.absolutePath }
val testApkApiLevelChecker by tasks.registering(PythonCheck::class) {
    group = "verification"
    description = "Self-test of tools/apk_api_level_types.py with the B-NAV012-01 fixtures (NAV-005 AC 100)"
    toolInputs.from(rootProject.file("tools/apk_api_level_types.py"), rootProject.file("tools/test_apk_api_level_types.py"), rootProject.file("tools/fixtures/apk-api-level"))
    toolArgs.set(listOf(rootProject.file("tools/test_apk_api_level_types.py").absolutePath))
    androidHome.set(androidSdkDir)
    failureTitle.set("testApkApiLevelChecker failed (NAV-005 AC 100)")
    report.set(layout.buildDirectory.file("apiLevelCheck/self-test.txt"))
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val assets = tasks.register<GenerateLicenceAssets>("generate${cap}LicenceAssets") {
            description = "Licences screen data for the ${variant.name} variant (NAV-005 AC 88–98)"
            variantName.set(variant.name)
            deps.set(writeLicenceDeps.flatMap { it.output })
            sources.from(licenceToolInputs)
            generator.set(rootProject.file("tools/gen-third-party-notices.py"))
            outputDir.set(layout.buildDirectory.dir("generated/licenceAssets/${variant.name}"))
            dependsOn(checkThirdPartyNotices)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(assets, GenerateLicenceAssets::outputDir)

        val apkCheck = tasks.register<PythonCheck>("checkApkApiLevelTypes$cap") {
            group = "verification"
            description = "B-NAV012-01: no framework type above minSdk outside ...Api<N> holders in the ${variant.name} APK (NAV-005 AC 99)"
            val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            toolInputs.from(apkDir, rootProject.file("tools/apk_api_level_types.py"))
            toolArgs.set(apkDir.map { dir ->
                val apks = dir.asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.map { it.absolutePath }.sorted().toList()
                listOf(rootProject.file("tools/apk_api_level_types.py").absolutePath) + apks.flatMap { listOf("--apk", it) }
                    .ifEmpty { listOf("--apk", dir.asFile.resolve("missing.apk").absolutePath) }
            })
            androidHome.set(androidSdkDir)
            failureTitle.set("checkApkApiLevelTypes$cap failed (NAV-005 AC 99, bug B-NAV012-01)")
            report.set(layout.buildDirectory.file("apiLevelCheck/${variant.name}.txt"))
            dependsOn(testApkApiLevelChecker)
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(apkCheck) }
    }
}

tasks.register("checkApkApiLevelTypes") {
    group = "verification"
    description = "Runs the APK API-level checker on the debug APK (NAV-005 AC 99)"
    dependsOn("checkApkApiLevelTypesDebug")
}
tasks.named("check") { dependsOn("checkApkApiLevelTypes", checkThirdPartyNotices, testThirdPartyNoticesGenerator) }

androidComponents {
    beforeVariants(selector().withBuildType("demo")) { it.enableUnitTest = demoUnitTestsEnabled }
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateTokenColours, GenerateTokenColours::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(syncBasemapAssets, SyncBasemapAssets::outputDir)
    }
    // NAV-019 AC 4–5: only the demo build type gets the demo assets; debug and release never see them.
    onVariants(selector().withBuildType("demo")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncDemoAssets, SyncDemoAssets::outputDir)
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

// NAV-023 (ADR-0017 §3, task SM1): JVM tests run the shipped androidx.sqlite BundledSQLiteDriver (the Android artefact's
// classes, which call System.loadLibrary("sqliteJni")) on the host. The native library comes from the same release's JVM
// artefact (same JNI, same bundled SQLite with FTS5, R*Tree and trigram); nothing of it is packaged into the app.
val sqliteHostNatives: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { sqliteHostNatives(libs.androidx.sqlite.bundled.jvm) }
val sqliteHostNativesDir: File = layout.buildDirectory.dir("sqlite-host-natives").get().asFile
val extractSqliteHostNatives by tasks.registering(Sync::class) {
    group = "verification"
    description = "Extracts the host libsqliteJni of androidx.sqlite:sqlite-bundled-jvm for JVM unit tests (NAV-023)"
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    val dir = when {
        os.contains("mac") -> if (arm) "osx_arm64" else "osx_x64"
        os.contains("win") -> "windows_x64"
        else -> if (arm) "linux_arm64" else "linux_x64"
    }
    from({ sqliteHostNatives.map { zipTree(it) } }) {
        include("natives/$dir/*")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(sqliteHostNativesDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostFerrostar)
    dependsOn(extractSqliteHostNatives)
    systemProperty("java.library.path", sqliteHostNativesDir.absolutePath + File.pathSeparator + System.getProperty("java.library.path"))
    // NAV-023 opt-in: run the engine on a real search.sqlite (OfflineGoldenHarnessTest), e.g. -Pnav.searchDb=/path/search.sqlite
    systemProperty("nav.searchDb", providers.gradleProperty("nav.searchDb").orNull ?: "")
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

// ------------------------------------------------------------------------------------------------ dependency locking (SEC-4B)
// ADR-0018 §6, story AC 27–28: the release runtime classpath (what ships in the release APK) is locked in
// app/gradle.lockfile. STRICT fails the build when the lock state is missing, and any version that differs from the
// lock fails resolution, so a transitive change can only land through an intended `--write-locks` (README "Updating
// dependencies"). Debug, demo, test and plugin classpaths are not locked; gradle/verification-metadata.xml still pins
// every file of them by sha256 (ADR-0018 §5).
dependencyLocking { lockMode.set(LockMode.STRICT) }
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach { resolutionStrategy.activateDependencyLocking() }

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
    // NAV-021: the on-device engine (loaded only in the :routing process) and Moshi for its error envelope.
    implementation(libs.valhalla.mobile)
    implementation(libs.moshi)
    // NAV-022 (ADR-0017 §5, §3): pack downloads and updates; the search-file self-test (FTS5).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.sqlite.bundled)
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
