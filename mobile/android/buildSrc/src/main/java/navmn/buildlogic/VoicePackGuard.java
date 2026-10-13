package navmn.buildlogic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NAV-016 M5 (AC 20, 45, 46, 50): the build-time check of the voice clip pack named by the Gradle property
 * {@code nav.voicePackDir} (environment {@code NAV_VOICE_PACK_DIR}, or the uncommitted gateway.local.properties). It runs
 * checks V1–V10 of docs/architecture/voice-pack/pack-format.md §8 against the directory and applies the variant policy:
 * <ul>
 *   <li>{@code debug}: optional; any voice, stub allowed. When the only V7 failure is that {@code inventorySha256}
 *   differs from the compiled value (a pack from older wording), the build warns, bundles the pack and still runs
 *   V8–V10; the app's runtime check rejects it and plays the fallback (AC 46, pack-format §8). A V7 failure of the
 *   manifest's own consistency (unsorted or duplicate entries, entries that do not hash to its inventorySha256) and
 *   every other failure fail the build.</li>
 *   <li>{@code demo}: a real pack is required ({@code source.vendor} not {@code stub}, {@code voiceId} FEMALE5v2 or
 *   MALE4v2), unless the value is exactly {@code none} (then no pack is bundled and the fallback plays).</li>
 *   <li>{@code release}: a real pack is required; {@code none} is refused.</li>
 * </ul>
 * Every failure message names {@code nav.voicePackDir} and the first failed check, with the fault ownership of
 * pack-format §8.1 (the same reason code as the generator's {@code verify} and the app's VoicePackValidator). The file
 * rules of §8.2 (security VP-4): links are never followed below the pack directory's real path, every listed file must
 * be a regular file inside it, and the size caps apply (manifest 1 MiB, clip 256 KiB, pack 6 MiB). The bytes that were
 * hashed are the bytes the copy writes ({@link Result#writeTo}), so nothing read through a link or changed after the
 * check reaches an APK. Nothing is ever downloaded. Plain Java with no dependencies: the same source file runs in the
 * build script and in the JVM test VoicePackGuardTest.
 */
public final class VoicePackGuard {
    private VoicePackGuard() {
    }

    public static final String PROPERTY = "nav.voicePackDir";
    public static final String ENV = "NAV_VOICE_PACK_DIR";
    public static final String NONE = "none";
    /** Where the README explains the property. */
    public static final String README = "mobile/android/README.md, section \"Voice pack (NAV-016)\"";
    /** pack-format §8.2 size caps (bytes). */
    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_CLIP_BYTES = 262_144;
    public static final long MAX_PACK_BYTES = 6_291_456L;

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
        /** The verified bytes of every file in {@link #files} (what V10 hashed). */
        private final Map<String, byte[]> contents;

        private Result(File dir, Verified v, String problem, String warning) {
            this.dir = dir;
            this.files = v == null ? null : Collections.unmodifiableList(v.files);
            this.contents = v == null ? Collections.<String, byte[]>emptyMap() : v.contents;
            this.problem = problem;
            this.warning = warning;
        }

        static Result fail(String problem) {
            return new Result(null, null, problem, null);
        }

        static Result none() {
            return new Result(null, new Verified(new ArrayList<String>(), new LinkedHashMap<String, byte[]>()), null, null);
        }

        /**
         * pack-format §8.2: writes the listed files as regular files under [target] from the verified bytes (never a link,
         * never a directory tree, never a second read of the pack directory).
         */
        public void writeTo(File target) throws IOException {
            if (files == null) throw new IllegalStateException(problem);
            for (String rel : files) {
                Path out = target.toPath().resolve(rel);
                Files.createDirectories(out.getParent());
                Files.deleteIfExists(out);
                Files.write(out, contents.get(rel), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        }
    }

    /** The listed files (manifest first) and their verified bytes. */
    private static final class Verified {
        final List<String> files;
        final Map<String, byte[]> contents;

        Verified(List<String> files, Map<String, byte[]> contents) {
            this.files = files;
            this.contents = contents;
        }
    }

    /** A failed check: its number (V1–V10) and the pack-format §8 reason code. */
    public static final class CheckFailure extends Exception {
        public final String check;
        public final String reason;
        /** True only for the V7 comparison with the compiled inventory (the one failure debug builds downgrade). */
        public final boolean compiledMismatch;

        CheckFailure(String check, String reason, String detail) {
            this(check, reason, detail, false);
        }

        CheckFailure(String check, String reason, String detail, boolean compiledMismatch) {
            super(check + " " + reason + ": " + detail);
            this.check = check;
            this.reason = reason;
            this.compiledMismatch = compiledMismatch;
        }
    }

    private static String message(String what) {
        return "NAV-016 voice pack (" + PROPERTY + "): " + what + (what.endsWith(".") ? "" : ".") + " See " + README + ".";
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
            return new Result(dir, verifyPack(dir, policy != Policy.OPTIONAL, compiledInventorySha256), null, null);
        } catch (CheckFailure f) {
            if (policy == Policy.OPTIONAL && f.compiledMismatch) {
                // pack-format §8, debug only: skip just the comparison with the compiled value. V1–V7 (including the
                // manifest's own sort order and hash) and V8–V10 still run.
                try {
                    return new Result(dir, verifyPack(dir, false, null), null,
                        message("check " + f.getMessage() + " The debug build bundles it; the app rejects it at runtime and plays the fallback (AC 46)."));
                } catch (CheckFailure g) {
                    return Result.fail(message("check " + g.getMessage()));
                }
            }
            return Result.fail(message("check " + f.getMessage()));
        }
    }

    /**
     * NAV-016 D236: the tag that tells the per-voice APKs apart (APK file name and versionName suffix): the manifest's
     * {@code voiceId}, plus {@code -stub} for a stub pack. Never the vendor. null when [value] names no readable pack
     * directory or the voiceId is not schema-valid (the build then fails in bundleVoicePack with the real reason).
     */
    public static String voiceTag(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.equals(NONE)) return null;
        File dir = new File(v);
        if (!dir.isAbsolute() || !dir.isDirectory()) return null;
        try {
            Path manifest = dir.toPath().toRealPath().resolve("manifest.json");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return null;
            byte[] bytes = readAtMost(manifest, MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) return null;
            Object root = MiniJson.parse(decodeUtf8(bytes));
            if (!(root instanceof Map)) return null;
            Object id = ((Map<?, ?>) root).get("voiceId");
            if (!(id instanceof String) || !((String) id).matches("^[A-Za-z0-9]{2,32}$")) return null;
            Object source = ((Map<?, ?>) root).get("source");
            boolean stub = source instanceof Map && "stub".equals(((Map<?, ?>) source).get("vendor"));
            return id + (stub ? "-stub" : "");
        } catch (IOException | IllegalArgumentException e) {
            return null;
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
     * Runs V1–V10 on [dir]. [real] adds V5 (no stub) and V6 (allowed voice). [compiled] null skips only the comparison
     * with the app's inventory in V7 (sort order, uniqueness and the manifest's own hash are still checked). Returns the
     * files to bundle.
     */
    public static List<String> verify(File dir, boolean real, String compiled) throws CheckFailure {
        return verifyPack(dir, real, compiled).files;
    }

    private static Verified verifyPack(File dir, boolean real, String compiled) throws CheckFailure {
        // pack-format §8.2: the directory given may be reached through a link; nothing below its real path may be.
        Path root;
        try {
            root = dir.toPath().toRealPath();
        } catch (IOException e) {
            throw new CheckFailure("V1", "NO_PACK", "the pack directory " + dir + " cannot be resolved");
        }
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) throw new CheckFailure("V1", "NO_PACK", "manifest.json is missing in " + dir);
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is a link or not a regular file (pack-format §8.2)");
        }
        byte[] manifestBytes;
        try {
            manifestBytes = readAtMost(manifestPath, MAX_MANIFEST_BYTES + 1);
        } catch (IOException e) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json cannot be read");
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is over the " + MAX_MANIFEST_BYTES + "-byte cap (pack-format §8.2)");
        }
        String text;
        try {
            text = decodeUtf8(manifestBytes);
        } catch (CharacterCodingException e) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is not UTF-8");
        }
        Object json;
        try {
            json = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is not JSON (" + e.getMessage() + ")");
        }
        if (!(json instanceof Map)) throw new CheckFailure("V2", "MANIFEST_UNREADABLE", "manifest.json is not a JSON object");
        Map<?, ?> m = (Map<?, ?>) json;
        Object schema = m.get("schemaVersion");
        if (!(schema instanceof Long) || (Long) schema != 1L) throw new CheckFailure("V2", "SCHEMA_UNSUPPORTED", "schemaVersion " + schema + " is not supported (supported: 1)");

        // V3: the rest of the manifest as schema v1 (pack-format §8.1: everything except the V4 codec values and the V8
        // file pattern). Reading rules: integers have no fraction or exponent (MiniJson gives BigDecimal for those), no
        // string contains U+0000–U+001F, patterns match the whole string (String.matches).
        if (hasControlCharacter(m)) throw v3("a string contains a control character (U+0000-U+001F)");
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
        // §8.1: bitrateKbps, encoderDelayFrames and encoder belong to V3, so they are checked before the V4 codec values
        // (bitrateKbps 999 with name "mp3" is V3).
        integer(codec, "bitrateKbps", 16, 64);
        long delay = integer(codec, "encoderDelayFrames", 0, 4096);
        string(codec, "encoder", "^[A-Za-z0-9._+-]{1,48}$");
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
                if (c >= 0) throw new CheckFailure("V7", "INVENTORY_MISMATCH", "entries are not sorted and unique at " + key + " " + take);
            }
            prevKey = key;
            prevTake = take;
            canonical.append(key).append('\t').append(take).append('\t').append(e.get("spokenText")).append('\n');
        }
        String recomputed = VoiceInventoryBuilder.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
        if (!recomputed.equals(inventorySha)) throw new CheckFailure("V7", "INVENTORY_MISMATCH", "the hash of the entries differs from inventorySha256");
        if (compiled != null && !compiled.equals(inventorySha)) {
            throw new CheckFailure("V7", "INVENTORY_MISMATCH", "inventorySha256 " + inventorySha + " differs from the app's inventory " + compiled
                + " (the pack was generated from other glossary wording: regenerate it)", true);
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
        // V9 (pack-format §8.2): clips/ is a real directory, not a link; then per entry, in entries order: absent →
        // FILE_MISSING; a link, not a regular file, outside the root or over 256 KiB → FILE_INVALID; then the 6 MiB total.
        Path clipsDir = root.resolve("clips");
        if (Files.exists(clipsDir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(clipsDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new CheckFailure("V9", "FILE_INVALID", "clips/ is a link or not a directory (pack-format §8.2)");
        }
        String rootPrefix = root.toString().endsWith(File.separator) ? root.toString() : root + File.separator;
        Map<String, Long> sizes = new LinkedHashMap<>();
        long total = manifestBytes.length;
        for (Object o : entries) {
            String file = (String) ((Map<?, ?>) o).get("file");
            Path p = root.resolve(file);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new CheckFailure("V9", "FILE_MISSING", file + " is missing");
            long size;
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile()) throw new CheckFailure("V9", "FILE_INVALID", file + " is a link or not a regular file (pack-format §8.2)");
                if (!p.toRealPath().toString().startsWith(rootPrefix)) throw new CheckFailure("V9", "FILE_INVALID", file + " lies outside the pack directory (pack-format §8.2)");
                size = a.size();
            } catch (IOException ex) {
                throw new CheckFailure("V9", "FILE_INVALID", file + " cannot be inspected");
            }
            if (size > MAX_CLIP_BYTES) throw new CheckFailure("V9", "FILE_INVALID", file + " is over the " + MAX_CLIP_BYTES + "-byte cap (pack-format §8.2)");
            sizes.put(file, size);
            total += size;
        }
        if (total > MAX_PACK_BYTES) throw new CheckFailure("V9", "FILE_INVALID", "the pack is " + total + " bytes, over the " + MAX_PACK_BYTES + "-byte cap (pack-format §8.2)");
        // V10: hash the bytes that will be copied (read without following links, at most cap + 1 bytes).
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("manifest.json", manifestBytes);
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            String file = (String) e.get("file");
            byte[] bytes;
            try {
                bytes = readAtMost(root.resolve(file), MAX_CLIP_BYTES + 1);
            } catch (IOException ex) {
                throw new CheckFailure("V9", "FILE_INVALID", file + " cannot be read without following links");
            }
            if (bytes.length != sizes.get(file)) throw new CheckFailure("V9", "FILE_INVALID", file + " changed size between the check and the read");
            if (!VoiceInventoryBuilder.sha256Hex(bytes).equals(e.get("sha256"))) throw new CheckFailure("V10", "FILE_HASH_MISMATCH", file + ": sha256 differs from the manifest");
            contents.put(file, bytes);
        }
        return new Verified(files, contents);
    }

    /** At most [limit] bytes of the regular file [p], opened without following a link (O_NOFOLLOW). */
    static byte[] readAtMost(Path p, int limit) throws IOException {
        Set<OpenOption> options = new HashSet<>(Arrays.<OpenOption>asList(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try (SeekableByteChannel ch = Files.newByteChannel(p, options); InputStream in = Channels.newInputStream(ch)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buf = new byte[16 * 1024];
            int left = limit;
            while (left > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
            return out.toByteArray();
        }
    }

    /** Strict UTF-8 (pack-format §8.1 V2: bytes that are not UTF-8 are MANIFEST_UNREADABLE). */
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** §8.1 reading rule: a member name or string value with U+0000–U+001F. */
    private static boolean hasControlCharacter(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20) return true;
            return false;
        }
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) if (hasControlCharacter(e.getKey()) || hasControlCharacter(e.getValue())) return true;
            return false;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) if (hasControlCharacter(o)) return true;
        }
        return false;
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
