package com.termux.app.hermes;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hermes installation logic.
 * With pre-built venv bundled in the APK, the install flow is:
 * 1. Wait for Termux bootstrap
 * 2. Extract pre-built venv from APK assets (VenvExtractor)
 * 3. Setup hermes command symlink
 * 4. Validate with `hermes --help`
 */
public class HermesInstallHelper {

    private static final String LOG_TAG = "HermesInstallHelper";
    private static final String PREFS_NAME = "hermes_install_state";
    private static final String KEY_INSTALL_STATE = "install_state";
    private static final String KEY_INSTALL_ERROR = "install_error";

    private static final String MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";

    private static final AtomicBoolean sInstallRunning = new AtomicBoolean(false);
    private static final StringBuilder sOutputBuffer = new StringBuilder();
    private static final int MAX_BUFFER_SIZE = 50000;

    public static boolean isInstallRunning() {
        return sInstallRunning.get();
    }

    public static String getOutputBuffer() {
        synchronized (sOutputBuffer) {
            return sOutputBuffer.toString();
        }
    }

    public static void clearOutputBuffer() {
        synchronized (sOutputBuffer) {
            sOutputBuffer.setLength(0);
        }
    }

    public enum InstallState {
        NOT_INSTALLED,
        BOOTSTRAPPING,
        INSTALLING,
        INSTALLED,
        FAILED
    }

    private HermesInstallHelper() {}

    // =========================================================================
    // State persistence
    // =========================================================================

    public static void setState(Context context, InstallState state) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_INSTALL_STATE, state.name()).apply();
        Logger.logInfo(LOG_TAG, "Install state: " + state.name());
    }

    public static InstallState getState(Context context) {
        String name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_INSTALL_STATE, null);
        if (name != null) {
            try { return InstallState.valueOf(name); } catch (IllegalArgumentException ignored) {}
        }
        return getCurrentState(context);
    }

    public static InstallState getCurrentState(Context context) {
        if (new File(MARKER_FILE).exists()) {
            return InstallState.INSTALLED;
        }
        return InstallState.NOT_INSTALLED;
    }

    public static void resetInstall(Context context) {
        new File(MARKER_FILE).delete();
        setState(context, InstallState.NOT_INSTALLED);
        setLastError(context, null);
    }

    public static void setLastError(Context context, String error) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_INSTALL_ERROR, error != null ? error : "").apply();
    }

    public static String getLastError(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_INSTALL_ERROR, "");
    }

    // =========================================================================
    // Progress callback
    // =========================================================================

    public interface ProgressCallback {
        void onStatus(String message);
        boolean isCancelled();
        default void onOutput(String line) {}
    }

    public interface PostBootstrapHook {
        void onBootstrapReady() throws Exception;
    }

    // =========================================================================
    // Install execution
    // =========================================================================

    /**
     * Install hermes: wait for bootstrap, extract pre-built venv, validate.
     */
    public static void executeInstall(Context context,
            ProgressCallback callback, PostBootstrapHook postBootstrap) throws Exception {
        if (!sInstallRunning.compareAndSet(false, true)) {
            Logger.logWarn(LOG_TAG, "Install already running, skipping duplicate call");
            return;
        }
        clearOutputBuffer();
        try {
            executeInstallInternal(context, callback, postBootstrap);
        } finally {
            sInstallRunning.set(false);
        }
    }

    private static void executeInstallInternal(Context context,
            ProgressCallback callback, PostBootstrapHook postBootstrap) throws Exception {

        // Phase 0: wait for Termux bootstrap to finish
        setState(context, InstallState.BOOTSTRAPPING);
        ensureBashReady(context, callback);

        if (postBootstrap != null) {
            postBootstrap.onBootstrapReady();
        }

        // Clean stale apt/dpkg locks left by bootstrap initialization
        cleanStaleLocks();

        // Phase 1: extract pre-built venv from APK assets
        if (!VenvExtractor.hasPrebuiltVenv(context)) {
            String msg = "No pre-built venv found in APK assets";
            Logger.logError(LOG_TAG, msg);
            setLastError(context, msg);
            setState(context, InstallState.FAILED);
            throw new RuntimeException(msg);
        }

        if (callback != null) callback.onStatus("Extracting pre-built environment...");
        Logger.logInfo(LOG_TAG, "Extracting pre-built venv from APK assets");
        if (!VenvExtractor.extractVenv(context)) {
            String msg = "Pre-built venv extraction failed";
            Logger.logError(LOG_TAG, msg);
            setLastError(context, msg);
            setState(context, InstallState.FAILED);
            throw new RuntimeException(msg);
        }

        // Phase 2: setup hermes command and validate
        setState(context, InstallState.INSTALLING);
        if (callback != null && callback.isCancelled()) return;
        if (callback != null) callback.onStatus("Setting up Hermes...");
        Logger.logInfo(LOG_TAG, "Setting up hermes command and validating");
        runShellCommand(buildSetupScript(), callback);

        setLastError(context, null);
        setState(context, InstallState.INSTALLED);
    }

    /**
     * Build the setup script: symlink hermes command, validate with --help.
     */
    static String buildSetupScript() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        return "set -e\n"
            + "\n"
            + "HERMES_DIR=\"$HOME/.hermes/hermes-agent\"\n"
            + "VENV_DIR=\"$HERMES_DIR/venv\"\n"
            + "PREFIX=\"" + prefix + "\"\n"
            + "\n"
            // venv's bin/python is a symlink to $PREFIX/bin/python — install it
            + "echo '=== Update package lists ==='\n"
            + "apt update 2>&1\n"
            + "echo '=== Install Python ==='\n"
            + "apt install -y python 2>&1\n"
            + "\n"
            + "echo '=== Setup hermes command ==='\n"
            + "HERMES_BIN=\"$PREFIX/bin/hermes\"\n"
            + "VENV_HERMES=\"$VENV_DIR/bin/hermes\"\n"
            + "if [ -f \"$VENV_HERMES\" ]; then\n"
            + "  ln -sf \"$VENV_HERMES\" \"$HERMES_BIN\"\n"
            + "elif [ -f \"$VENV_DIR/bin/python\" ]; then\n"
            + "  echo \"#!$VENV_DIR/bin/python\" > \"$HERMES_BIN\"\n"
            + "  echo \"import sys; sys.path.insert(0, '$HERMES_DIR'); from hermes_cli import main; main()\" >> \"$HERMES_BIN\"\n"
            + "  chmod 755 \"$HERMES_BIN\"\n"
            + "fi\n"
            + "\n"
            + "echo '=== Validate hermes ==='\n"
            + "hermes --help > /dev/null 2>&1 || { echo 'FATAL: hermes --help failed'; exit 1; }\n"
            + "echo '=== Hermes installed successfully ==='\n";
    }

    /**
     * Remove stale apt/dpkg lock files left by bootstrap initialization.
     * Non-fatal — only cleans if no apt/dpkg process is running.
     */
    private static void cleanStaleLocks() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) return;

        String cmd = "for _p in /proc/[0-9]*/cmdline; do "
            + "[ -r \"$_p\" ] && cat \"$_p\" 2>/dev/null | tr '\\0' ' ' "
            + "| grep -qE '/apt|/dpkg' && exit 0; "
            + "done; "
            + "rm -f " + prefix + "/var/lib/apt/lists/lock "
            + prefix + "/var/cache/apt/archives/lock "
            + prefix + "/var/lib/dpkg/lock-frontend "
            + prefix + "/var/lib/dpkg/lock";

        try {
            runShellCommand(cmd, null);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Lock cleanup failed (non-fatal): " + e.getMessage());
        }


    }

    /**
     * Wait until bash can actually execute (bootstrap packages fully installed).
     */
    private static void emit(ProgressCallback callback, String msg) {
        Logger.logInfo(LOG_TAG, msg);
        if (callback != null) callback.onOutput(msg);
        synchronized (sOutputBuffer) {
            sOutputBuffer.append(msg).append("\n");
            if (sOutputBuffer.length() > MAX_BUFFER_SIZE) {
                sOutputBuffer.delete(0, sOutputBuffer.length() - MAX_BUFFER_SIZE / 2);
            }
        }
    }

    private static void ensureBashReady(Context context, ProgressCallback callback) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String prefixDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        int maxWaitAttempts = 40;

        emit(callback, "Checking bootstrap: " + bashPath);

        for (int i = 0; i < maxWaitAttempts; i++) {
            if (callback != null && callback.isCancelled()) return;

            File bashFile = new File(bashPath);
            if (!bashFile.exists()) {
                // First attempt: dump diagnostic info
                if (i == 0) {
                    emit(callback, "PREFIX exists: " + new File(prefixDir).exists());
                    emit(callback, "PREFIX/bin exists: " + new File(prefixDir + "/bin").exists());
                    String[] bins = new File(prefixDir + "/bin").list();
                    emit(callback, "PREFIX/bin contents: " + (bins != null ? java.util.Arrays.toString(bins) : "null"));
                }
                emit(callback, "bash not found (" + (i + 1) + "/" + maxWaitAttempts + ")");
            } else {
                try {
                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", "echo ok");
                    pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                    String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                    if (new java.io.File(pathRewriteLib).exists()) {
                        pb.environment().put("LD_PRELOAD", pathRewriteLib);
                    }
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output;
                    try (java.io.InputStream is = p.getInputStream()) {
                        byte[] buf = new byte[256];
                        int n = is.read(buf);
                        output = n > 0 ? new String(buf, 0, n).trim() : "";
                        while (is.read(buf) != -1) {}
                    }
                    int exit = p.waitFor();
                    if (exit == 0) {
                        emit(callback, "Bootstrap ready: bash executed successfully");
                        return;
                    }
                    emit(callback, "bash exited with code " + exit + ", output: [" + output + "]"
                            + " (" + (i + 1) + "/" + maxWaitAttempts + ")");
                } catch (Exception e) {
                    emit(callback, "bash execution failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage()
                            + " (" + (i + 1) + "/" + maxWaitAttempts + ")");
                }
            }

            if (callback != null) {
                callback.onStatus(context.getString(R.string.install_waiting_bootstrap, (i + 1), maxWaitAttempts));
            }
            Thread.sleep(3000);
        }
        String bootstrapError = "Termux bootstrap packages are not ready after " + (maxWaitAttempts * 3) + " seconds";
        setLastError(context, bootstrapError);
        setState(context, InstallState.FAILED);
        throw new RuntimeException(bootstrapError);
    }

    /**
     * Execute a bash command in the Termux environment.
     */
    static void runShellCommand(String bashCommand, ProgressCallback callback) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        if (!new File(bashPath).exists()) {
            throw new RuntimeException("bash not available yet");
        }

        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", bashCommand);
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + ":/system/bin:/system/xbin");
        pb.environment().put("PREFIX", prefix);
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        pb.environment().put("TERMUX_VERSION", com.termux.BuildConfig.VERSION_NAME);
        pb.environment().put("TERMINFO", prefix + "/share/terminfo");

        String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
        if (new File(pathRewriteLib).exists()) {
            pb.environment().put("LD_PRELOAD", pathRewriteLib);
        }

        String aptConfFile = prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf";
        if (new File(aptConfFile).exists()) {
            pb.environment().put("APT_CONFIG", aptConfFile);
        }

        pb.redirectErrorStream(true);

        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (callback != null) callback.onOutput(line);
                synchronized (sOutputBuffer) {
                    sOutputBuffer.append(line).append("\n");
                    if (sOutputBuffer.length() > MAX_BUFFER_SIZE) {
                        sOutputBuffer.delete(0, sOutputBuffer.length() - MAX_BUFFER_SIZE / 2);
                    }
                }
            }
        }

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Install script exited with code " + exit
                    + (output.length() > 0 ? "\n" + output : ""));
        }
    }
}
