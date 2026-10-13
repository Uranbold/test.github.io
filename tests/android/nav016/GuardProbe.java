// NAV-016 QA probe (task breakdown §3.2, pack-format §8.1): prints the reason code the Gradle guard
// (navmn.buildlogic.VoicePackGuard, compiled in mobile/android/buildSrc/build/libs/buildSrc.jar) gives for each pack dir.
// Run: java -cp mobile/android/buildSrc/build/libs/buildSrc.jar tests/android/nav016/GuardProbe.java <compiledSha> <dir>...
// Policy: debug semantics of verify() (real = false), with the compiled inventory comparison on (V7 as in release).
import java.io.File;

public class GuardProbe {
    public static void main(String[] args) {
        String compiled = args[0];
        for (int i = 1; i < args.length; i++) {
            String code;
            try {
                navmn.buildlogic.VoicePackGuard.verify(new File(args[i]), false, compiled);
                code = "OK";
            } catch (navmn.buildlogic.VoicePackGuard.CheckFailure f) {
                code = f.reason;
            } catch (RuntimeException e) {
                code = "EXCEPTION:" + e.getClass().getSimpleName();
            }
            System.out.println(new File(args[i]).getName() + "\t" + code);
        }
    }
}
