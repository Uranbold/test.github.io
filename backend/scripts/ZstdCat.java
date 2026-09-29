// NAV-001 (ADR-0003): stream a Photon dump to stdout, decompressing zstd when needed.
// The JDK image has no zstd binary and Photon 1.3.0 cannot read .zst itself, so this pipes
// into `photon import -import-file -`. Pure Java via io.airlift:aircompressor (pinned in .env).
//   java -cp aircompressor.jar ZstdCat.java <dump> [--head N]
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ZstdCat {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: ZstdCat <file> [--head N]"); System.exit(2); }
        int head = args.length >= 3 && "--head".equals(args[1]) ? Integer.parseInt(args[2]) : -1;
        InputStream raw = new BufferedInputStream(new FileInputStream(args[0]), 1 << 20);
        raw.mark(4);
        byte[] m = raw.readNBytes(4);
        raw.reset();
        boolean zstd = m.length == 4 && (m[0] & 0xff) == 0x28 && (m[1] & 0xff) == 0xb5
                && (m[2] & 0xff) == 0x2f && (m[3] & 0xff) == 0xfd;
        InputStream in = zstd ? new BufferedInputStream(new ZstdInputStream(raw), 1 << 20) : raw;
        if (head > 0) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (int i = 0; i < head; i++) {
                String line = r.readLine();
                if (line == null) break;
                System.out.println(line);
            }
            return;
        }
        OutputStream out = System.out;
        in.transferTo(out);
        out.flush();
    }
}
