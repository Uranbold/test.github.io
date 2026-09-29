// Build a Java truststore = JDK cacerts + every certificate in a PEM bundle, in one JVM run
// (one keytool call per certificate takes about 0.4 s each, over a minute for a full bundle).
//   java TrustStore.java <jdk-cacerts> <extra.pem> <out.p12>     (password: changeit)
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

public class TrustStore {
    public static void main(String[] args) throws Exception {
        char[] pw = "changeit".toCharArray();
        KeyStore out = KeyStore.getInstance("PKCS12");
        out.load(null, pw);
        KeyStore jdk = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream in = new FileInputStream(args[0])) { jdk.load(in, null); }
        for (String alias : java.util.Collections.list(jdk.aliases())) {
            out.setCertificateEntry(alias, jdk.getCertificate(alias));
        }
        int n = 0;
        try (FileInputStream in = new FileInputStream(args[1])) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            for (Certificate c : certs) out.setCertificateEntry("extra-ca-" + (++n), c);
        }
        try (FileOutputStream o = new FileOutputStream(args[2])) { out.store(o, pw); }
        System.out.println(n);
    }
}
