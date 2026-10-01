package mn.navmn.app.support

import java.io.File

object Fixtures {
    fun route(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("routes/$name")) { "missing fixture routes/$name" }.readBytes()

    fun shared(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("shared/$name")) { "missing shared test resource $name (Gradle syncSharedTestResources)" }
            .readBytes().decodeToString()

    fun repoFile(path: String): File = File(TestStrings.repoRoot, path)
}
