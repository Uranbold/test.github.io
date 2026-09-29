// NAV-001 (AC 9, 10): validate a PMTiles v3 archive before it is moved into place.
// Run as a single-file program: java /scripts/PmtilesCheck.java <file.pmtiles> [minMaxZoom]
// Checks magic "PMTiles" + version 3, max zoom, and that metadata.attribution mentions
// OpenStreetMap. Prints one JSON line with the header facts; exits 1 on any failure.
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class PmtilesCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: PmtilesCheck <file> [minMaxZoom]"); System.exit(2); }
        int minMaxZoom = args.length > 1 ? Integer.parseInt(args[1]) : 14;
        try (RandomAccessFile f = new RandomAccessFile(args[0], "r")) {
            byte[] h = new byte[127];
            f.readFully(h);
            String magic = new String(h, 0, 7, StandardCharsets.US_ASCII);
            if (!"PMTiles".equals(magic) || h[7] != 3) fail("bad magic/version: " + magic + " v" + h[7]);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            long metaOff = b.getLong(24), metaLen = b.getLong(32);
            long addressed = b.getLong(72), entries = b.getLong(80), contents = b.getLong(88);
            int internalCompression = h[97];
            int tileType = h[99];
            int minZoom = h[100], maxZoom = h[101];
            double minLon = b.getInt(102) / 1e7, minLat = b.getInt(106) / 1e7;
            double maxLon = b.getInt(110) / 1e7, maxLat = b.getInt(114) / 1e7;
            if (metaLen <= 0 || metaOff + metaLen > f.length()) fail("metadata out of range");
            byte[] meta = new byte[(int) metaLen];
            f.seek(metaOff);
            f.readFully(meta);
            if (internalCompression == 2) meta = gunzip(meta);
            String json = new String(meta, StandardCharsets.UTF_8);
            if (maxZoom < minMaxZoom) fail("maxzoom " + maxZoom + " < " + minMaxZoom);
            if (addressed <= 0) fail("archive has no tiles");
            if (!json.contains("OpenStreetMap")) fail("metadata attribution does not mention OpenStreetMap");
            System.out.printf(java.util.Locale.ROOT,
                "{\"pmtiles_version\":3,\"tile_type\":%d,\"min_zoom\":%d,\"max_zoom\":%d,"
                + "\"bounds\":[%.7f,%.7f,%.7f,%.7f],\"addressed_tiles\":%d,\"tile_entries\":%d,"
                + "\"tile_contents\":%d,\"bytes\":%d,\"attribution_mentions_openstreetmap\":true}%n",
                tileType, minZoom, maxZoom, minLon, minLat, maxLon, maxLat, addressed, entries, contents, f.length());
        }
    }

    static byte[] gunzip(byte[] in) throws Exception {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            z.transferTo(out);
            return out.toByteArray();
        }
    }

    static void fail(String msg) {
        System.err.println("{\"service\":\"tiles-build\",\"level\":\"error\",\"msg\":\"pmtiles check failed: " + msg.replace("\"", "'") + "\"}");
        System.exit(1);
    }
}
