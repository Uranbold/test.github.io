package navmn.buildlogic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * NAV-019 AC 3 (ADR-0016 §8.2): the demo build needs exactly one basemap source, the Gradle property
 * {@code nav.demoTilesFile} (absolute path to a local PMTiles v3 archive, copied into the APK) or {@code nav.demoTilesUrl}
 * (an https PMTiles URL). Used by {@code checkDemoTiles} (a dependency of {@code preDemoBuild} only) and by the JVM unit
 * test, so debug, release and unit-test tasks never need either property. Values are never committed (AC 41).
 */
public final class DemoTilesGuard {
    private DemoTilesGuard() {
    }

    /** Where the README explains both properties (AC 3: the message points to it). */
    public static final String README = "mobile/android/README.md, section \"Demo build (NAV-019)\"";

    /** PMTiles v3 header: the 7 ASCII bytes "PMTiles" followed by the spec version byte 3. */
    public static final byte[] MAGIC = "PMTiles".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 3;

    /** The message when neither property is set (names both properties and the README). */
    public static String missingMessage() {
        return "The demo build (NAV-019) needs a basemap: set exactly one of the Gradle properties nav.demoTilesFile "
            + "(absolute path to a local .pmtiles archive, e.g. -Pnav.demoTilesFile=<path-to>.pmtiles) or nav.demoTilesUrl "
            + "(e.g. -Pnav.demoTilesUrl=https://<host>/<path>.pmtiles). They can also come from the environment "
            + "(NAV_DEMO_TILES_FILE, NAV_DEMO_TILES_URL) or the uncommitted mobile/android/gateway.local.properties. See "
            + README + ".";
    }

    /** null when the configuration is usable, otherwise the build error message. */
    public static String problem(String file, String url) {
        boolean hasFile = file != null && !file.trim().isEmpty();
        boolean hasUrl = url != null && !url.trim().isEmpty();
        if (!hasFile && !hasUrl) return missingMessage();
        if (hasFile && hasUrl) {
            return "The demo build (NAV-019): set exactly one of nav.demoTilesFile and nav.demoTilesUrl, not both. See " + README + ".";
        }
        if (hasUrl) {
            if (!url.trim().startsWith("https://")) {
                return "The demo build (NAV-019): nav.demoTilesUrl must be an https:// PMTiles URL. See " + README + ".";
            }
            return null;
        }
        File f = new File(file.trim());
        if (!f.isAbsolute()) return "The demo build (NAV-019): nav.demoTilesFile must be an absolute path. See " + README + ".";
        if (!f.isFile() || !f.canRead()) {
            return "The demo build (NAV-019): nav.demoTilesFile does not point to a readable file (" + f + "). See " + README + ".";
        }
        if (!isPmtilesV3(f)) {
            return "The demo build (NAV-019): nav.demoTilesFile is not a PMTiles v3 archive (" + f + "). See " + README + ".";
        }
        return null;
    }

    /** Throws {@link IllegalStateException} with [problem]'s message. */
    public static void check(String file, String url) {
        String p = problem(file, url);
        if (p != null) throw new IllegalStateException(p);
    }

    /** True when the first 8 bytes are the PMTiles v3 magic. */
    public static boolean isPmtilesV3(File f) {
        byte[] head = new byte[MAGIC.length + 1];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) return false;
                n += r;
            }
        } catch (IOException e) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) if (head[i] != MAGIC[i]) return false;
        return head[MAGIC.length] == VERSION;
    }
}
