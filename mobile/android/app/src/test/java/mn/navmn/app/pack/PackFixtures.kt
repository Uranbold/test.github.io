package mn.navmn.app.pack

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mn.navmn.app.routing.EngineAnswer
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** One pack file as the NAV-020 pipeline publishes it: raw bytes, the gzip copy and both checksums. */
class FixtureFile(val kind: PackKind, val version: String, val raw: ByteArray, val dataTimestamp: String, val format: String) {
    val gz: ByteArray = gzip(raw)
    val path: String get() = "$version/${kind.installedName}.gz"
    val sha: String get() = sha256(raw)
    val gzSha: String get() = sha256(gz)

    fun json(): String =
        """{"kind":"${kind.wire}","version":"$version","path":"$path","encoding":"gzip","download_bytes":${gz.size},""" +
            """"download_sha256":"$gzSha","bytes":${raw.size},"sha256":"$sha","data_timestamp":"$dataTimestamp","format":$format,"future_field":1}"""

    companion object {
        fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** A PMTiles v3 header (z0–14, bounds of Mongolia) followed by incompressible filler. */
        fun tiles(version: String, size: Int = 40_000, seed: Int = 1, dataTimestamp: String = "2026-09-26T20:21:03Z"): FixtureFile {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("PMTiles".toByteArray()).put(3)
            b.position(100)
            b.put(0).put(14)
            b.putInt((87.7 * 1e7).toInt()).putInt((41.5 * 1e7).toInt()).putInt((119.9 * 1e7).toInt()).putInt((52.2 * 1e7).toInt())
            val filler = Random(seed).nextBytes(size - 127)
            b.position(127)
            b.put(filler)
            return FixtureFile(PackKind.TILES, version, b.array(), dataTimestamp, """{"pmtiles":3,"minzoom":0,"maxzoom":14}""")
        }

        fun routing(version: String, size: Int = 30_000, seed: Int = 2, dataTimestamp: String = "2026-10-04T19:00:00Z", builder: String = "valhalla 3.9.0") =
            FixtureFile(PackKind.ROUTING, version, Random(seed).nextBytes(size), dataTimestamp, """{"graph_builder":"$builder"}""")

        fun search(version: String, size: Int = 20_000, seed: Int = 3, dataTimestamp: String = "2026-10-03T22:59:05Z", schema: Int = 1) =
            FixtureFile(PackKind.SEARCH, version, Random(seed).nextBytes(size), dataTimestamp, """{"search_schema":$schema}""")
    }
}

object Manifests {
    fun json(files: List<FixtureFile>, packSchema: Int = 1, packVersion: String = files.maxOf { it.version }): String =
        """{"pack_schema":$packSchema,"region":"mn","pack_version":"$packVersion","published_at":"2026-10-04T20:00:00Z",""" +
            """"attribution":"© OpenStreetMap contributors","licence":{"name":"ODbL-1.0","url":"https://127.0.0.1/odbl-1.0",""" +
            """"method_url":"https://127.0.0.1/method"},"files":[${files.joinToString(",") { it.json() }}],""" +
            """"total_bytes":${files.sumOf { it.raw.size }},"total_download_bytes":${files.sumOf { it.gz.size }},""" +
            """"self_test":{"route":{"from":{"lat":47.9189,"lon":106.9176},"to":{"lat":47.8858,"lon":106.9173},"costing":"auto"},"search":{"q":"Сүхбаатар"}},"unknown_top":true}"""
}

/**
 * A static `/packs/` tree like nginx or LiteSpeed: strong ETags, `Range` → 206 with `Content-Range`, `If-Range` with a
 * different validator → 200 full body, 304 on a matching `If-None-Match`. Records every request and the bytes it sent.
 */
class PackServer : Dispatcher() {
    @Volatile var manifest: String = "{}"
    @Volatile var manifestEtag: String = "\"m1\""
    @Volatile var manifestCode: Int = 200
    val files = Collections.synchronizedMap(HashMap<String, ByteArray>())
    val etags = Collections.synchronizedMap(HashMap<String, String>())
    /** Paths that answer 404 once (a retired file) before being served. */
    val retireOnce: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /** When true, Range is ignored (a server without range support): always 200. */
    @Volatile var ignoreRange = false
    /** Corrupts the byte at the middle of these paths in every answer (a test proxy). */
    val flipMiddle: MutableSet<String> = Collections.synchronizedSet(HashSet())
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    val bytesSent = Collections.synchronizedMap(HashMap<String, Long>())

    fun publish(m: String, list: List<FixtureFile>, etag: String = "\"m" + m.hashCode() + "\"") {
        manifest = m
        manifestEtag = etag
        for (f in list) {
            files["/packs/mn/${f.path}"] = f.gz
            etags["/packs/mn/${f.path}"] = "\"" + f.gzSha.take(16) + "\""
        }
    }

    fun fileRequests(): List<RecordedRequest> = requests.filter { !it.url.encodedPath.endsWith("manifest.json") }
    fun manifestRequests(): List<RecordedRequest> = requests.filter { it.url.encodedPath.endsWith("manifest.json") }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        if (path == "/packs/mn/manifest.json") {
            if (manifestCode != 200) return MockResponse.Builder().code(manifestCode).build()
            if (request.headers["If-None-Match"] == manifestEtag) return MockResponse.Builder().code(304).build()
            return MockResponse.Builder().code(200).addHeader("ETag", manifestEtag).body(manifest).build()
        }
        if (retireOnce.remove(path)) return MockResponse.Builder().code(404).build()
        val body = files[path]?.let { b -> if (path.removePrefix("/packs/mn/") in flipMiddle) b.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() } else b }
            ?: return MockResponse.Builder().code(404).build()
        val etag = etags[path]!!
        val range = request.headers["Range"]?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() }
        val ifRange = request.headers["If-Range"]
        if (range != null && !ignoreRange && (ifRange == null || ifRange == etag)) {
            if (range >= body.size) return MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${body.size}").build()
            val part = body.copyOfRange(range.toInt(), body.size)
            bytesSent.merge(path, part.size.toLong(), Long::plus)
            return MockResponse.Builder().code(206).addHeader("ETag", etag)
                .addHeader("Content-Range", "bytes $range-${body.size - 1}/${body.size}").body(Buffer().write(part)).build()
        }
        bytesSent.merge(path, body.size.toLong(), Long::plus)
        return MockResponse.Builder().code(200).addHeader("ETag", etag).body(Buffer().write(body)).build()
    }
}

/** Self-tests that pass (or fail for chosen kinds); the real ones need the `:routing` process and FTS5. */
class FakeSelfTests(var failKinds: Set<PackKind> = emptySet(), var throwKinds: Set<PackKind> = emptySet()) : SelfTests {
    val ran = Collections.synchronizedList(ArrayList<PackKind>())
    override suspend fun tiles(f: PackFile, file: File): Boolean = run(PackKind.TILES)
    override suspend fun routing(f: PackFile, file: File, manifest: PackManifest): Boolean = run(PackKind.ROUTING)
    override suspend fun search(f: PackFile, file: File, manifest: PackManifest): Boolean = run(PackKind.SEARCH)
    private fun run(k: PackKind): Boolean {
        ran += k
        if (k in throwKinds) throw IllegalStateException("self-test crashed: $k")
        return k !in failKinds
    }
}

class FakeGate(@Volatile var validated: Boolean = true, @Volatile var unmetered: Boolean = true) : NetworkGate {
    override fun validated() = validated
    override fun unmetered() = unmetered
}

/** A routing self-test answering a fixed OSRM body. */
class FixedRoutingSelfTest(private val body: String) : RoutingSelfTest {
    var calls = 0
    override suspend fun route(version: String, tar: File, requestJson: String): EngineAnswer {
        calls++
        return EngineAnswer.Osrm(body.toByteArray())
    }
}
