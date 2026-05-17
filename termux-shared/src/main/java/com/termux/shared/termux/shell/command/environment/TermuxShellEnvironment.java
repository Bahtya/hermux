package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /**
     * Consecutive Signal 11 (SIGSEGV) crash counter for terminal sessions.
     * Incremented when a terminal session exits with signal 11.
     * Reset to 0 when a session runs for more than 5 seconds (considered successful).
     * LD_PRELOAD is skipped after 1 crash to allow recovery.
     */
    private static volatile int sSignal11CrashCount = 0;

    public static void reportTerminalSignal11() {
        sSignal11CrashCount++;
        Logger.logWarn(LOG_TAG, "Terminal signal 11 crash count: " + sSignal11CrashCount);
    }

    public static void reportTerminalHealthy() {
        if (sSignal11CrashCount != 0) {
            sSignal11CrashCount = 0;
            Logger.logInfo(LOG_TAG, "Terminal healthy, reset signal 11 crash count");
        }
    }

    public static boolean isPathRewriteDisabled() {
        return sSignal11CrashCount >= 1;
    }

    public static int getSignal11CrashCount() {
        return sSignal11CrashCount;
    }

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        environment.put(ENV_HOME, TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put(ENV_PREFIX, TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
            } else {
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            }
            // Set LD_LIBRARY_PATH since bootstrap binaries may have DT_RUNPATH hardcoded
            // to /data/data/com.termux/files/usr/lib. The installer patches binaries on
            // extraction, but this provides an additional safety net.
            environment.put(ENV_LD_LIBRARY_PATH, TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);

            // Set TERMINFO to override ncurses compiled-in default path which points to
            // /data/data/com.termux/files/usr/share/terminfo (the old package path).
            environment.put("TERMINFO", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/terminfo");

            // Set PKG_CONFIG paths to override hardcoded paths in .pc files.
            environment.put("PKG_CONFIG_LIBDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/pkgconfig:" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/pkgconfig");

            // Set APT_CONFIG to override compiled-in Dir paths. apt reads config from
            // the compiled-in Dir::Etc path before applying overrides, causing a crash
            // when that path is inaccessible after package rename.
            String aptConfFile = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/apt.conf.d/99hermes-paths.conf";
            if (new java.io.File(aptConfFile).exists()) {
                environment.put("APT_CONFIG", aptConfFile);
            }

            // Set LD_PRELOAD to load the path rewrite library. This intercepts all
            // filesystem calls and rewrites /data/data/com.termux/ to the correct path,
            // fixing ALL binaries with compiled-in old paths (dpkg, apt, bash, etc.).
            // Use the APK's nativeLibraryDir which is on the linker's allowed search path.
            // The app data directory ($PREFIX/lib) is NOT allowed for LD_PRELOAD on
            // Android 10+ due to linker namespace restrictions.
            // Skip if terminal has crashed with signal 11 to allow recovery.
            if (!isPathRewriteDisabled()) {
                String pathRewriteLib = currentPackageContext.getApplicationInfo().nativeLibraryDir
                        + "/libpath_rewrite.so";
                if (new java.io.File(pathRewriteLib).exists()) {
                    environment.put("LD_PRELOAD", pathRewriteLib);
                }
            } else {
                Logger.logWarn(LOG_TAG, "Skipping LD_PRELOAD due to previous signal 11 crash");
            }
        }

        return environment;
    }


    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

}
