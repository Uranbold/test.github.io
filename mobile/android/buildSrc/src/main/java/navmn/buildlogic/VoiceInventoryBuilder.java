package navmn.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * NAV-016 M1 (AC 1, 2, 5, 20): the canonical voice inventory of docs/architecture/voice-pack/pack-format.md §2, built
 * from the same two files the clip-pack generator reads:
 * <ul>
 *   <li>{@code docs/requirements/glossary-c3-spoken.tsv} (BA): every row is one {@code final} entry (columns
 *   {@code key}, {@code spoken_mn});</li>
 *   <li>{@code docs/architecture/voice-pack/inventory.tsv} (architect): every row is one {@code final} entry plus one
 *   {@code nonFinal} entry when {@code nonfinal} = {@code yes}.</li>
 * </ul>
 * Sorted by key, then take (ASCII byte order), serialised as {@code key\ttake\tspokenText\n} (UTF-8, LF, no header),
 * hashed with SHA-256. Used by app/build.gradle.kts (the generated asset {@code voice/inventory.tsv} and
 * {@code BuildConfig.VOICE_INVENTORY_SHA256}) and by JVM unit tests. Plain Java, no dependencies.
 */
public final class VoiceInventoryBuilder {
    private VoiceInventoryBuilder() {
    }

    /** pack-format §2.2: lower-case Mongolian Cyrillic words separated by single spaces. */
    public static final Pattern SPOKEN = Pattern.compile("^[\\u0430-\\u044F\\u0451\\u04AF\\u04E9]+( [\\u0430-\\u044F\\u0451\\u04AF\\u04E9]+)*$");

    /** One inventory entry (pack-format §2.1). */
    public static final class Entry {
        public final String key;
        public final String take;
        public final String spokenText;

        Entry(String key, String take, String spokenText) {
            this.key = key;
            this.take = take;
            this.spokenText = spokenText;
        }
    }

    /** The canonical inventory: entries in order, the canonical bytes and their hash. */
    public static final class Inventory {
        public final List<Entry> entries;
        public final byte[] bytes;
        public final String sha256;

        Inventory(List<Entry> entries, byte[] bytes, String sha256) {
            this.entries = Collections.unmodifiableList(entries);
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    public static Inventory build(File distanceTsv, File inventoryTsv) throws IOException {
        return build(
            new String(Files.readAllBytes(distanceTsv.toPath()), StandardCharsets.UTF_8),
            new String(Files.readAllBytes(inventoryTsv.toPath()), StandardCharsets.UTF_8));
    }

    /** Builds the inventory from the two file texts; throws IllegalArgumentException naming the key on a bad row. */
    public static Inventory build(String distanceTsv, String inventoryTsv) {
        List<Entry> entries = new ArrayList<>();
        for (Map<String, String> r : rows(distanceTsv, "glossary-c3-spoken.tsv", "key", "spoken_mn")) {
            entries.add(new Entry(r.get("key"), "final", r.get("spoken_mn")));
        }
        for (Map<String, String> r : rows(inventoryTsv, "inventory.tsv", "key", "nonfinal", "spoken_mn")) {
            entries.add(new Entry(r.get("key"), "final", r.get("spoken_mn")));
            String nonFinal = r.get("nonfinal");
            if ("yes".equals(nonFinal)) {
                entries.add(new Entry(r.get("key"), "nonFinal", r.get("spoken_mn")));
            } else if (!"no".equals(nonFinal)) {
                throw new IllegalArgumentException("inventory.tsv: " + r.get("key") + ": nonfinal must be yes or no");
            }
        }
        entries.sort((a, b) -> {
            int c = compareBytes(a.key, b.key);
            return c != 0 ? c : compareBytes(a.take, b.take);
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0 && entries.get(i - 1).key.equals(e.key) && entries.get(i - 1).take.equals(e.take)) {
                throw new IllegalArgumentException("duplicate inventory entry " + e.key + " " + e.take);
            }
            if (!SPOKEN.matcher(e.spokenText).matches()) {
                throw new IllegalArgumentException("inventory entry " + e.key + " " + e.take + ": spoken text breaks the pack-format §2.2 rule");
            }
            sb.append(e.key).append('\t').append(e.take).append('\t').append(e.spokenText).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new Inventory(entries, bytes, sha256Hex(bytes));
    }

    private static List<Map<String, String>> rows(String text, String file, String... required) {
        String[] lines = text.split("\n", -1);
        List<String> header = Arrays.asList(lines[0].split("\t", -1));
        for (String column : required) {
            if (!header.contains(column)) throw new IllegalArgumentException(file + ": missing column " + column);
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] cells = lines[i].split("\t", -1);
            if (cells.length < header.size()) throw new IllegalArgumentException(file + ":" + (i + 1) + ": too few columns");
            Map<String, String> row = new HashMap<>();
            for (int j = 0; j < header.size(); j++) row.put(header.get(j), cells[j]);
            out.add(row);
        }
        return out;
    }

    static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (c != 0) return c;
        }
        return x.length - y.length;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
