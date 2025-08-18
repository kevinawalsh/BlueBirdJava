package birdbrain;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;

public class BlueBirdLauncher {

    /**
     * Check if the launcher is available. Currently, only available on Linux,
     * and only if BLUEBIRD_CONNECTOR environment variable is set to non-blank
     * string.
     */
    public static boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) // only supports Linux, for now
            return false;
        String prog = System.getenv("BLUEBIRD_CONNECTOR");
        if (prog == null || prog.trim().isBlank()) {
            return false;
        }
        return true;
    }

    /**
     * Launch the BlueBird connector, detached from this process and terminal.
     * - Program name:
     *     1) $BLUEBIRD_CONNECTOR environment variable (if set)
     *     2) otherwise "bluebirdconnector"
     * - PATH is used when the program name has no '/'.
     * - stdio is redirected to /dev/null.
     * - Returns true if the spawn succeeded, false otherwise.
     */
    public static boolean launch() {
        if (!isAvailable()) return false;

        String prog = System.getenv("BLUEBIRD_CONNECTOR");
        // if (prog == null)
        //     prog = "bluebirdconnector";

        // Build the base command (no args).
        // If prog contains '/', it's treated as a path; otherwise PATH will resolve it.
        String[] base = new String[] { prog };

        // Try 'setsid <prog>' first (fully detaches into a new session).
        if (trySpawn(new String[] { "setsid", base[0] })) {
            return true;
        }
        // Fallback to 'nohup <prog>' (ignores SIGHUP when terminal closes).
        if (trySpawn(new String[] { "nohup", base[0] })) {
            return true;
        }
        // As a last resort, launch directly (may receive SIGHUP on terminal close).
        return trySpawn(base);
    }

    private static boolean trySpawn(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            // Redirect all stdio to /dev/null (Java 9+: Redirect.DISCARD).
            // If you're on Java 8, replace with new File("/dev/null") for each redirect.
            pb.redirectInput(Redirect.from(devNull()));
            pb.redirectOutput(Redirect.to(devNull()));
            pb.redirectError(Redirect.to(devNull()));

            // Inherit environment and PATH as-is. No working directory change.
            pb.start(); // Non-blocking; child continues independently.
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Helper to get /dev/null file once.
    private static File devNullFile;
    private static synchronized File devNull() {
        if (devNullFile == null) devNullFile = new File("/dev/null");
        return devNullFile;
    }
}

