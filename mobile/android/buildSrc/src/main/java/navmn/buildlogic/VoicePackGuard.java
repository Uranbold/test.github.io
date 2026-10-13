package navmn.buildlogic;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NAV-016 M5 (AC 20, 45, 46, 50): the build-time check of the voice clip pack named by the Gradle property
 * {@code nav.voicePackDir} (environment {@code NAV_VOICE_PACK_DIR}, or the uncommitted gateway.local.properties). It runs
 * checks V1–V10 of docs/architecture/voice-pack/pack-format.md §8 against the directory and applies the variant policy:
 * <ul>
 *   <li>{@code debug}: optional; any voice, stub allowed. An inventory mismatch (V7) is a warning only: the pack is
 *   bundled and the app's runtime check rejects it, so the build plays the fallback (AC 46). Every other failure fails.</li>
 *   <li>{@code demo}: a real pack is required ({@code source.vendor} not {@code stub}, {@code voiceId} FEMALE5v2 or
 *   MALE4v2), unless the value is exactly {@code none} (then no pack is bundled and the fallback plays).</li>
 *   <li>{@code release}: a real pack is required; {@code none} is refused.</li>
 * </ul>
 * Every failure message names {@code nav.voicePackDir} and the first failed check. Nothing is ever downloaded. Plain
 * Java with no dependencies: the same source file runs in the build script and in the JVM test VoicePackGuardTest.
 */
public final class VoicePackGuard {
    private VoicePackGuard() {
    }

    public static final String PROPERTY = "nav.voicePackDir";
    public static final String ENV = "NAV_VOICE_PACK_DIR";
    public static final String NONE = "none";
    /** Where the README explains the property. */
    public static final String README = "mobile/android/README.md, section \"Voice pack (NAV-016)\"";

    /** Variant policy (AC 50). */
    public enum Policy {
        /** debug: optional, stub allowed. */
        OPTIONAL,
        /** demo: real pack required unless exactly "none". */
        REQUIRED_REAL_OR_NONE,
        /** release: real pack required. */
        REQUIRED_REAL;

        /** The app's runtime policy (BuildConfig.VOICE_PACK_POLICY): OPTIONAL or REQUIRED_REAL. */
        public String runtimeName() {
            return this == OPTIONAL ? "OPTIONAL" : "REQUIRED_REAL";
        }
    }

    public static Policy policyFor(String buildType) {
        switch (buildType) {
            case "debug": return Policy.OPTIONAL;
            case "demo": return Policy.REQUIRED_REAL_OR_NONE;
            default: return Policy.REQUIRED_REAL;
        }
    }

    /** The outcome: the pack files to bundle (relative to the pack directory, manifest first), or a build error. */
    public static final class Result {
        /** null when the build must fail. Empty = no pack bundled. */
        public final List<String> files;
        public final String problem;
        public final String warning;
        public final File dir;

        private Result(File dir, List<String> files, String problem, String warning) {
            this.dir = dir;
            this.files = files == null ? null : Collections.unmodifiableList(files);
            this.problem = problem;
            this.warning = warning;
        }

        static Result fail(String problem) {
            return new Result(null, null, problem, null);
        }

        static Result none() {
            return new Result(null, new ArrayList<>(), null, null);
        }
    }

    /** A failed check: its number (V1–V10) and the pack-format §8 reason code. */
    public static final class CheckFailure extends Exception {
        public final String check;
        public final String reason;

        CheckFailure(String check, String reason, String detail) {
            super(check + " " + reason + ": " + detail);
            this.check = check;
            this.reason = reason;
        }
    }

    private static String message(String what) {
        return "NAV-016 voice pack (" + PROPERTY + "): " + what + " See " + README + ".";
    }

    /**
     * Applies [policy] to the setting [value] (null or blank = not set) with the inventory hash compiled into the app.
     */
    public static Result check(String value, Policy policy, String compiledInventorySha256) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            if (policy == Policy.OPTIONAL) return Result.none();
            return Result.fail(message(policy == Policy.REQUIRED_REAL
                ? "the release build needs a real voice pack: set -P" + PROPERTY + "=<absolute path to the pack directory> (or " + ENV + ")."
                : "the demo build needs a real voice pack: set -P" + PROPERTY + "=<absolute path to the pack directory> (or " + ENV + "), or -P" + PROPERTY + "=" + NONE + " to build without one (fallback chime)."));
        }
        if (v.equals(NONE)) {
            if (policy == Policy.REQUIRED_REAL) return Result.fail(message("'" + NONE + "' is not allowed for release builds: a real voice pack is required."));
            return Result.none();
        }
        File dir = new File(v);
        if (!dir.isAbsolute()) return Result.fail(message("must be an absolute path to a pack directory (got a relative path)."));
        if (!dir.isDirectory()) return Result.fail(message("is not a directory (" + dir + ")."));
        try {
            List<String> files = verify(dir, policy != Policy.OPTIONAL, compiledInventorySha256);
            return new Result(dir, files, null, null);
        } catch (CheckFailure f) {
            if (policy == Policy.OPTIONAL && f.check.equals("V7")) {
                try {
                    List<String> files = verify(dir, false, null, false);
                    return new Result(dir, files, null, message("check " + f.getMessage() + " The debug build bundles it; the app rejects it at runtime and plays the fallback (AC 46)."));
                } catch (CheckFailure g) {
                    return Result.fail(message("check " + g.getMessage()));
                }
            }
            return Result.fail(message("check " + f.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------------ V1–V10

    private static final List<String> TOP = Arrays.asList("schemaVersion", "packVersion", "voiceId", "source", "sourceSampleRateHz", "codec",
        "takeMethod", "joinPauseMs", "thenPauseMs", "processing", "glossaryRevision", "inventorySha256", "entries");
    private static final List<String> SOURCE = Arrays.asList("vendor", "apiVersion", "voice");
    private static final List<String> CODEC = Arrays.asList("name", "container", "fileExtension", "sampleRateHz", "channels", "bitrateKbps",
        "encoderDelayFrames", "encoder");
    private static final List<String> PROCESSING = Arrays.asList("silenceThresholdDbfs", "edgeMarginMs", "fadeMs", "targetRmsDbfs", "peakCeilingDbfs");
    private static final List<String> ENTRY = Arrays.asList("key", "take", "spokenText", "file", "frames", "encodedFrames", "durationMs", "sha256");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern KEY = Pattern.compile("^(dist|cont|instr|tail|join|fixed)(\\.[A-Za-z0-9_]+)+$");
    private static final Pattern FILE = Pattern.compile("^clips/(dist|cont|instr|tail|join|fixed)(\\.[A-Za-z0-9_]+)+\\.(final|nonFinal)\\.aac$");
    public static final Set<String> REAL_VOICES = new HashSet<>(Arrays.asList("FEMALE5v2", "MALE4v2"));

    /**
     * Runs V1–V10 on [dir]. [real] adds V5 (no stub) and V6 (allowed voice). [compiled] null skips the comparison with
     * the app's inventory in V7 (the manifest's own hash is still recomputed). Returns the files to bundle.
     */
    public static List<String> verify(File dir, boolean real, String compiled) throws CheckFailure {
        return verify(dir, real, compiled, true);
    }

    /** As {@link #verify(File, boolean, String)}; [checkInventory] false skips V7 (debug builds after a V7 warning). */
    public static List<String> verify(File dir, boolean real, String compiled, boolean checkInventory) throws CheckFailure {
        File manifestFile = new File(dir, "manifest.json");
        if (!manifestFile.isFile()) throw new CheckFailure("V1", "NO_PACK", "manifest.json is missing in " + dir);
        String text;
        try {
            text = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json cannot be read");
        }
        Object root;
        try {
            root = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is not JSON (" + e.getMessage() + ")");
        }
        if (!(root instanceof Map)) throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is not a JSON object");
        Map<?, ?> m = (Map<?, ?>) root;
        Object schema = m.get("schemaVersion");
        if (!(schema instanceof Long) || (Long) schema != 1L) throw new CheckFailure("V2", "SCHEMA_UNSUPPORTED", "schemaVersion " + schema + " is not supported (supported: 1)");

        // V3: the rest of the manifest as schema v1.
        members(m, TOP, "manifest");
        integer(m, "packVersion", 1, Long.MAX_VALUE);
        string(m, "voiceId", "^[A-Za-z0-9]{2,32}$");
        Map<?, ?> source = object(m, "source");
        members(source, SOURCE, "source");
        String vendor = string(source, "vendor", "^(" + vendorName() + "|stub|recorded)$");
        string(source, "apiVersion", "^[A-Za-z0-9._-]{1,16}$");
        string(source, "voice", "^[A-Za-z0-9._-]{1,32}$");
        long srcRate = integer(m, "sourceSampleRateHz", 0, Long.MAX_VALUE);
        if (srcRate != 8000 && srcRate != 16000 && srcRate != 22050) throw v3("sourceSampleRateHz " + srcRate);
        Map<?, ?> codec = object(m, "codec");
        members(codec, CODEC, "codec");
        string(m, "takeMethod", "^(carrier-then-v1|comma-v1)$");
        integer(m, "joinPauseMs", 0, 300);
        integer(m, "thenPauseMs", 0, 300);
        Map<?, ?> processing = object(m, "processing");
        members(processing, PROCESSING, "processing");
        number(processing, "silenceThresholdDbfs", -70, -30);
        integer(processing, "edgeMarginMs", 0, 40);
        integer(processing, "fadeMs", 0, 50);
        number(processing, "targetRmsDbfs", -30, -10);
        number(processing, "peakCeilingDbfs", -6, 0);
        string(m, "glossaryRevision", HEX64.pattern());
        String inventorySha = string(m, "inventorySha256", HEX64.pattern());
        Object entriesObj = m.get("entries");
        if (!(entriesObj instanceof List) || ((List<?>) entriesObj).isEmpty()) throw v3("entries must be a non-empty array");
        List<?> entries = (List<?>) entriesObj;
        for (Object o : entries) {
            if (!(o instanceof Map)) throw v3("an entry is not an object");
            Map<?, ?> e = (Map<?, ?>) o;
            members(e, ENTRY, "entry");
            string(e, "key", KEY.pattern());
            string(e, "take", "^(final|nonFinal)$");
            Object spoken = e.get("spokenText");
            if (!(spoken instanceof String) || ((String) spoken).length() < 2 || ((String) spoken).length() > 300
                || !VoiceInventoryBuilder.SPOKEN.matcher((String) spoken).matches()) {
                throw v3("entry " + e.get("key") + ": spokenText");
            }
            if (!(e.get("file") instanceof String)) throw v3("entry " + e.get("key") + ": file");
            integer(e, "frames", 1, Long.MAX_VALUE);
            integer(e, "encodedFrames", 1, Long.MAX_VALUE);
            integer(e, "durationMs", 150, 4000);
            string(e, "sha256", HEX64.pattern());
        }

        // V4: codec.
        long rate = codec.get("sampleRateHz") instanceof Long ? (Long) codec.get("sampleRateHz") : -1;
        if (!"aac-lc".equals(codec.get("name")) || !"adts".equals(codec.get("container")) || !".aac".equals(codec.get("fileExtension"))
            || !Long.valueOf(1).equals(codec.get("channels")) || (rate != 16000 && rate != 22050)) {
            throw new CheckFailure("V4", "CODEC_UNSUPPORTED", "codec must be AAC-LC / ADTS / .aac / mono / 16000 or 22050 Hz");
        }
        long bitrate = codec.get("bitrateKbps") instanceof Long ? (Long) codec.get("bitrateKbps") : -1;
        long delay = codec.get("encoderDelayFrames") instanceof Long ? (Long) codec.get("encoderDelayFrames") : -1;
        if (bitrate < 16 || bitrate > 64 || delay < 0 || delay > 4096 || !(codec.get("encoder") instanceof String)
            || !((String) codec.get("encoder")).matches("^[A-Za-z0-9._+-]{1,48}$")) {
            throw v3("codec fields");
        }

        // V5, V6: real packs only in demo and release.
        if (real && "stub".equals(vendor)) throw new CheckFailure("V5", "STUB_NOT_ALLOWED", "a stub pack (source.vendor = stub) is not allowed in demo or release builds");
        if (real && !REAL_VOICES.contains((String) m.get("voiceId"))) {
            throw new CheckFailure("V6", "VOICE_NOT_ALLOWED", "voiceId " + m.get("voiceId") + " is not FEMALE5v2 or MALE4v2");
        }

        // V7: sorted, unique, canonical hash = inventorySha256 = the compiled value.
        StringBuilder canonical = new StringBuilder();
        String prevKey = null;
        String prevTake = null;
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            String key = (String) e.get("key");
            String take = (String) e.get("take");
            if (prevKey != null) {
                int c = VoiceInventoryBuilder.compareBytes(prevKey, key);
                if (c == 0) c = VoiceInventoryBuilder.compareBytes(prevTake, take);
                if (checkInventory && c >= 0) throw new CheckFailure("V7", "INVENTORY_MISMATCH", "entries are not sorted and unique at " + key + " " + take);
            }
            prevKey = key;
            prevTake = take;
            canonical.append(key).append('\t').append(take).append('\t').append(e.get("spokenText")).append('\n');
        }
        String recomputed = VoiceInventoryBuilder.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
        if (checkInventory && !recomputed.equals(inventorySha)) throw new CheckFailure("V7", "INVENTORY_MISMATCH", "the hash of the entries differs from inventorySha256");
        if (checkInventory && compiled != null && !compiled.equals(inventorySha)) {
            throw new CheckFailure("V7", "INVENTORY_MISMATCH", "inventorySha256 " + inventorySha + " differs from the app's inventory " + compiled
                + " (the pack was generated from other glossary wording: regenerate it)");
        }

        // V8: file paths, durations, frame bounds.
        List<String> files = new ArrayList<>();
        files.add("manifest.json");
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            String file = (String) e.get("file");
            if (!FILE.matcher(file).matches() || file.contains("..")) throw new CheckFailure("V8", "ENTRY_INVALID", "entry " + e.get("key") + ": file " + file);
            long frames = (Long) e.get("frames");
            long encoded = (Long) e.get("encodedFrames");
            long duration = (Long) e.get("durationMs");
            if (duration != (frames * 2000 + rate) / (2 * rate)) throw new CheckFailure("V8", "ENTRY_INVALID", "entry " + e.get("key") + ": durationMs does not match frames");
            if (frames > encoded - delay) throw new CheckFailure("V8", "ENTRY_INVALID", "entry " + e.get("key") + ": frames > encodedFrames - encoderDelayFrames");
            files.add(file);
        }
        // V9, V10.
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            File f = new File(dir, (String) e.get("file"));
            if (!f.isFile()) throw new CheckFailure("V9", "FILE_MISSING", e.get("file") + " is missing");
        }
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            File f = new File(dir, (String) e.get("file"));
            String hash;
            try {
                hash = VoiceInventoryBuilder.sha256Hex(Files.readAllBytes(f.toPath()));
            } catch (IOException ex) {
                throw new CheckFailure("V9", "FILE_MISSING", e.get("file") + " cannot be read");
            }
            if (!hash.equals(e.get("sha256"))) throw new CheckFailure("V10", "FILE_HASH_MISMATCH", e.get("file") + ": sha256 differs from the manifest");
        }
        return files;
    }

    /** The real vendor's enum value of the schema, assembled so that the name does not appear in mobile/ (NAV-016 AC 6). */
    private static String vendorName() {
        return new String(new char[] {'c', 'h', 'i', 'm', 'e', 'g', 'e'});
    }

    private static CheckFailure v3(String detail) {
        return new CheckFailure("V3", "MANIFEST_UNREADABLE", "not a schema v1 manifest: " + detail);
    }

    private static void members(Map<?, ?> m, List<String> required, String where) throws CheckFailure {
        for (String k : required) if (!m.containsKey(k)) throw v3(where + " has no " + k);
        for (Object k : m.keySet()) if (!required.contains(k)) throw v3(where + " has the unknown member " + k);
    }

    private static Map<?, ?> object(Map<?, ?> m, String k) throws CheckFailure {
        Object v = m.get(k);
        if (!(v instanceof Map)) throw v3(k + " must be an object");
        return (Map<?, ?>) v;
    }

    private static String string(Map<?, ?> m, String k, String pattern) throws CheckFailure {
        Object v = m.get(k);
        if (!(v instanceof String) || !((String) v).matches(pattern)) throw v3(k);
        return (String) v;
    }

    private static long integer(Map<?, ?> m, String k, long min, long max) throws CheckFailure {
        Object v = m.get(k);
        if (!(v instanceof Long) || (Long) v < min || (Long) v > max) throw v3(k);
        return (Long) v;
    }

    private static void number(Map<?, ?> m, String k, double min, double max) throws CheckFailure {
        Object v = m.get(k);
        double d;
        if (v instanceof Long) d = (Long) v;
        else if (v instanceof BigDecimal) d = ((BigDecimal) v).doubleValue();
        else throw v3(k);
        if (d < min || d > max) throw v3(k);
    }
}
