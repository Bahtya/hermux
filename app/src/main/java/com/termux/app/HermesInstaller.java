package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.system.Os;

import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.hermes.HermesConfigManager;
import com.termux.app.hermes.HermesInstallHelper;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class HermesInstaller {

    private static final String LOG_TAG = "HermesInstaller";
    private static final String NOTIFICATION_CHANNEL_ID = "hermes_install";
    private static final int NOTIFICATION_ID = 2001;

    static final String ACTION_RETRY_INSTALL = "com.hermux.RETRY_INSTALL";
    static final String EXTRA_IS_RETRY = "is_retry";

    private static final String HERMES_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final String HERMES_BOOT_SCRIPT =
            TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH + "/hermes-gateway";
    private static final String HERMES_BASH_INIT_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-bash-init-deployed";
    private static final String HERMES_APT_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-apt-conf-deployed";
    private static final String HERMES_DPKG_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-dpkg-conf-deployed";
    private static final String HERMES_SHELL_PROFILE_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-shell-profile-deployed";
    private static final String HERMES_PATH_REWRITE_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-path-rewrite-deployed";
    private static final String HERMES_SYMLINK_FIX_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-symlinks-fixed";
    private static final String HERMES_DPKG_DB_FIX_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-dpkg-db-patched";
    private static final String HERMES_SSH_SETUP_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-ssh-setup-deployed";
    private static final String HERMES_BASH_INIT_VERSION = "2";
    private static final String HERMES_APT_CONF_VERSION = "4";
    private static final String HERMES_DPKG_CONF_VERSION = "1";
    private static final String HERMES_SHELL_PROFILE_VERSION = "3";
    private static final String HERMES_PATH_REWRITE_VERSION = "4";
    private static final String HERMES_SYMLINK_FIX_VERSION = "2";
    private static final String HERMES_DPKG_DB_FIX_VERSION = "2";
    private static final String HERMES_SSH_SETUP_VERSION = "4";

    private static final String SSH_BOOT_SCRIPT =
            TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH + "/hermes-sshd";
    private static final String SSHD_CONFIG_PATH =
            TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/ssh/sshd_config";
    private static final int SSH_DEFAULT_PORT = 8022;

    private HermesInstaller() {}

    /**
     * Check critical config files on every app start and auto-heal if tampered.
     * These files are small (total ~3KB), re-deploying is negligible overhead.
     */
    static void runIntegrityCheck() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        int healed = 0;

        // sources.list must contain TUNA mirror
        File sourcesList = new File(prefix, "etc/apt/sources.list");
        if (!fileContains(sourcesList, "mirrors.tuna.tsinghua.edu.cn")) {
            try { deployAptMirror(prefix); healed++; }
            catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to heal sources.list: " + e.getMessage()); }
        }

        // apt.conf must contain DPkg hook directive
        File aptConf = new File(prefix, "etc/apt/apt.conf.d/99hermes-paths.conf");
        if (!fileContains(aptConf, "DPkg::Pre-Install-Pkgs")) {
            try { deployAptConf(); deployAptPreInstallHook(); deployAptPostInvokeHook(); healed++; }
            catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to heal apt.conf: " + e.getMessage()); }
        }

        // hook scripts must exist and be executable
        File hookScript = new File(prefix, "lib/hermes/apt-pre-install-patch");
        if (!hookScript.canExecute()) {
            try { deployAptPreInstallHook(); healed++; }
            catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to heal hook script: " + e.getMessage()); }
        }
        File postInvokeScript = new File(prefix, "lib/hermes/apt-post-invoke-fix-paths");
        if (!postInvokeScript.canExecute()) {
            try { deployAptPostInvokeHook(); healed++; }
            catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to heal post-invoke script: " + e.getMessage()); }
        }

        // dpkg conf must contain admindir
        File dpkgConf = new File(prefix, "etc/dpkg/dpkg.cfg.d/hermes-paths");
        if (!fileContains(dpkgConf, "admindir")) {
            try { deployDpkgConf(); healed++; }
            catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to heal dpkg.conf: " + e.getMessage()); }
        }

        // libpath_rewrite.so must exist (context-dependent deploy handled in runContextMigrations)
        File pathRewrite = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "libpath_rewrite.so");
        if (!pathRewrite.exists()) {
            Logger.logWarn(LOG_TAG, "Integrity: libpath_rewrite.so missing, will be healed in runContextMigrations");
        }

        // pip.conf must not contain com.termux paths
        File pipConf = new File(prefix, "etc/pip.conf");
        if (fileContains(pipConf, "/data/data/com.termux")) {
            try {
                patchTextFileSafe(pipConf, "/data/data/com.termux", "/data/data/com.hermux");
                healed++;
                Logger.logInfo(LOG_TAG, "Integrity: patched com.termux paths in pip.conf");
            } catch (Exception e) { Logger.logWarn(LOG_TAG, "Integrity: failed to patch pip.conf: " + e.getMessage()); }
        }

        if (healed > 0) {
            Logger.logInfo(LOG_TAG, "Integrity check: " + healed + " files healed");
        }
    }

    private static boolean fileContains(File file, String keyword) {
        if (!file.exists()) return false;
        try {
            String content = readFile(file);
            return content.contains(keyword);
        } catch (Exception e) {
            return false;
        }
    }

    static void installIfNeeded(Context context) {
        if (new File(HERMES_MARKER_FILE).exists()) {
            if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "hermes").exists()) {
                Logger.logWarn(LOG_TAG, "hermes-installed marker exists but binary missing, retrying install");
                new File(HERMES_MARKER_FILE).delete();
            } else {
                Logger.logInfo(LOG_TAG, "Hermes already installed, skipping.");
                ensureSshSetup(context);
                return;
            }
        }
        startInstallThread(context, false);
    }

    /**
     * Run upgrade migrations for existing installations. Called on every app start
     * from TermuxApplication so that users who upgrade from a previous version
     * get necessary fixes applied without needing a clean reinstall.
     */
    static void runUpgradeMigrations() {
        runIntegrityCheck();
        // Fix broken symlinks: bootstrap SYMLINKS.txt uses absolute paths with
        // com.termux which don't exist after the package rename. Earlier versions
        // didn't rewrite symlink targets, so existing installs need this fix.
        runMigration("Symlink fix", HERMES_SYMLINK_FIX_VERSION,
                HERMES_SYMLINK_FIX_MARKER_FILE, () ->
                        TermuxInstaller.fixPrefixSymlinks(TermuxConstants.TERMUX_PREFIX_DIR_PATH));

        // Versioned migrations (with target file existence check for apt/dpkg configs)
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        runMigration("Bash init", HERMES_BASH_INIT_VERSION,
                HERMES_BASH_INIT_MARKER_FILE, HermesInstaller::deployBashInit);
        runMigration("Apt conf", HERMES_APT_CONF_VERSION,
                HERMES_APT_CONF_MARKER_FILE, () -> {
                    HermesInstaller.deployAptConf();
                    HermesInstaller.deployAptPreInstallHook();
                    HermesInstaller.deployAptPostInvokeHook();
                },
                prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf");
        runMigration("Dpkg conf", HERMES_DPKG_CONF_VERSION,
                HERMES_DPKG_CONF_MARKER_FILE, HermesInstaller::deployDpkgConf,
                prefix + "/etc/dpkg/dpkg.cfg.d/hermes-paths");
        runMigration("Dpkg db fix", HERMES_DPKG_DB_FIX_VERSION,
                HERMES_DPKG_DB_FIX_MARKER_FILE, () ->
                        patchDpkgDatabase(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        runMigration("Shell profile", HERMES_SHELL_PROFILE_VERSION,
                HERMES_SHELL_PROFILE_MARKER_FILE, HermesInstaller::deployShellProfile,
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.bashrc");
    }

    /**
     * Run migrations that require a Context (e.g., accessing APK native libs).
     * Called from TermuxApplication.onCreate() after runUpgradeMigrations().
     */
    static void runContextMigrations(Context context) {
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        runMigration("Path rewrite", HERMES_PATH_REWRITE_VERSION,
                HERMES_PATH_REWRITE_MARKER_FILE, () -> deployPathRewrite(context),
                TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so");
        runMigration("SSH setup", HERMES_SSH_SETUP_VERSION,
                HERMES_SSH_SETUP_MARKER_FILE, () -> deploySshConfig(nativeLibDir),
                SSHD_CONFIG_PATH, true);
    }

    private static void runMigration(String name, String version,
            String markerPath, ThrowingRunnable deployAction) {
        runMigration(name, version, markerPath, deployAction, null, false);
    }

    private static void runMigration(String name, String version,
            String markerPath, ThrowingRunnable deployAction, String targetFilePath) {
        runMigration(name, version, markerPath, deployAction, targetFilePath, false);
    }

    private static void runMigration(String name, String version,
            String markerPath, ThrowingRunnable deployAction, String targetFilePath,
            boolean deferIsExpected) {
        boolean needsDeploy = true;
        File marker = new File(markerPath);
        if (marker.exists()) {
            try {
                String deployedVersion = readFile(marker).trim();
                if (version.equals(deployedVersion)) {
                    needsDeploy = false;
                    // If target file was deleted (e.g. by apt upgrade), redeploy
                    if (targetFilePath != null && !new File(targetFilePath).exists()) {
                        needsDeploy = true;
                        Logger.logInfo(LOG_TAG, name + " target file missing, redeploying");
                    }
                }
            } catch (Exception e) {
                // Marker file unreadable - re-deploy
            }
        }
        if (needsDeploy) {
            try {
                deployAction.run();
                try (FileOutputStream out = new FileOutputStream(markerPath)) {
                    out.write((version + "\n").getBytes("UTF-8"));
                }
                Logger.logInfo(LOG_TAG, name + " migration complete (v" + version + ")");
            } catch (Exception e) {
                if (deferIsExpected) {
                    Logger.logInfo(LOG_TAG, name + " migration deferred: " + e.getMessage());
                } else {
                    Logger.logErrorExtended(LOG_TAG, "Failed " + name + " migration: " + e.getMessage());
                }
            }
        }
    }

    public static void retryInstall(Context context) {
        startInstallThread(context, true);
    }

    /**
     * Deploy apt.conf, dpkg.conf, LD_PRELOAD library, and patch the dpkg database.
     * Must be called AFTER bootstrap is complete (prefix directory exists).
     * Called via PostBootstrapHook from HermesInstallHelper.executeInstall().
     */
    public static void deployInstallPrerequisites(Context context) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        try { deployAptConf(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt.conf deploy: " + e.getMessage());
        }
        try { deployAptPreInstallHook(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt hook deploy: " + e.getMessage());
        }
        try { deployAptPostInvokeHook(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install post-invoke hook deploy: " + e.getMessage());
        }
        try { deployDpkgConf(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install dpkg.conf deploy: " + e.getMessage());
        }
        try { deployPathRewrite(context); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install path-rewrite deploy: " + e.getMessage());
        }
        try { patchDpkgDatabase(prefix); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install dpkg db patch: " + e.getMessage());
        }
        try { ensureAptDirectories(prefix); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt dirs ensure: " + e.getMessage());
        }
        try { deployAptMirror(prefix); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt mirror: " + e.getMessage());
        }
    }

    private static void startInstallThread(Context context, boolean isRetry) {
        if (HermesInstallHelper.isInstallRunning()) {
            Logger.logInfo(LOG_TAG, "Install already running, skipping duplicate");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                createNotificationChannel(context);
                showProgress(context, "Preparing installation...", 0);

                if (!isRetry) {
                    deployBootScript();
                }

                try {
                    HermesInstallHelper.executeInstall(context, new HermesInstallHelper.ProgressCallback() {
                        @Override
                        public void onStatus(String message) {
                            showProgress(context, message, 30);
                        }
                        @Override
                        public boolean isCancelled() {
                            return Thread.currentThread().isInterrupted();
                        }
                    }, () -> {
                        // Deploy critical configs AFTER bootstrap is ready.
                        // Bootstrap extraction wipes $PREFIX, so configs deployed
                        // earlier (e.g. during app.onCreate()) no longer exist.
                        deployInstallPrerequisites(context);
                    });
                    // Validate hermes binary before marking installed
                    if (!validateHermesBinary()) {
                        throw new RuntimeException("Hermes binary validation failed — hermes --help did not succeed");
                    }
                    markInstalled(context);
                    fixBinaryPermissions();
                    HermesConfigManager.reinitialize();
                    // Setup SSH after Hermes install completes (openssh was installed as part of pkg install)
                    setupSshInBackground(context);
                    showSuccess(context, "Hermes Agent installed successfully");
                    Logger.logInfo(LOG_TAG, "Hermes installation complete.");
                } catch (Exception e) {
                    HermesInstallHelper.setState(context, HermesInstallHelper.InstallState.FAILED);
                    String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    HermesInstallHelper.setLastError(context, errorMsg);
                    showError(context, "Installation failed: " + errorMsg);
                    Logger.logErrorExtended(LOG_TAG, "Hermes installation failed:\n" + errorMsg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Hermes installation failed:\n" + e.getMessage());
                showError(context, "Installation failed: " + e.getMessage());
            }
        }, "HermesInstaller");
        t.setDaemon(true);
        t.start();
    }

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "Hermes Installation",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
    }

    private static void showProgress(Context context, String message, int percent) {
        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Installing Hermes Agent")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notify(context, notification);
    }

    private static void showSuccess(Context context, String message) {
        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Hermes Agent")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notify(context, notification);
    }

    private static void showError(Context context, String message) {
        Intent retryIntent = new Intent(ACTION_RETRY_INSTALL);
        retryIntent.setPackage(context.getPackageName());
        retryIntent.putExtra(EXTRA_IS_RETRY, true);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Hermes Installation Failed")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_service_notification)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .addAction(R.drawable.ic_service_notification, "Retry", pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        notify(context, notification);
    }

    private static void notify(Context context, Notification notification) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static void deployBootScript() throws Exception {
        File bootDir = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR;
        FileUtils.createDirectoryFile(bootDir.getAbsolutePath());

        String script = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n"
                + "# Auto-start Hermes gateway on boot\n"
                + "if command -v hermes >/dev/null 2>&1; then\n"
                + "    hermes gateway run &\n"
                + "fi\n";

        File scriptFile = new File(HERMES_BOOT_SCRIPT);
        try (FileOutputStream out = new FileOutputStream(scriptFile)) {
            out.write(script.getBytes("UTF-8"));
        }
        Os.chmod(scriptFile.getAbsolutePath(), 0700);
        Logger.logInfo(LOG_TAG, "Deployed boot script to " + HERMES_BOOT_SCRIPT);
    }

    private static void markInstalled(Context context) throws Exception {
        try (FileOutputStream out = new FileOutputStream(HERMES_MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
        deployBashInit();
        deployAptConf();
        deployAptPreInstallHook();
        deployAptPostInvokeHook();
        deployDpkgConf();
        deployShellProfile();
        deployPathRewrite(context);
    }

    /**
     * Deploy the .hermes_bash_init file that serves as bash's --rcfile target.
     * This bypasses the compiled-in /data/data/com.termux/.../bash.bashrc path
     * which causes "Permission denied" on forked packages where the package name
     * differs. The init file sources the system bash.bashrc and profile from the
     * correct $PREFIX path, then sources the user's .bashrc.
     */
    private static void deployBashInit() throws Exception {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File initFile = new File(home, ".hermes_bash_init");

        String content = "# Hermes bash init - sourced via --rcfile to bypass compiled-in bash.bashrc path\n"
                + "if [ -f \"$PREFIX/etc/bash.bashrc\" ]; then\n"
                + "    . \"$PREFIX/etc/bash.bashrc\"\n"
                + "fi\n"
                + "if [ -f \"$HOME/.bashrc\" ]; then\n"
                + "    . \"$HOME/.bashrc\"\n"
                + "fi\n";

        try (FileOutputStream out = new FileOutputStream(initFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(initFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed bash init file to " + initFile.getAbsolutePath());
    }

    /**
     * Deploy apt.conf with explicit directory paths to override compiled-in defaults.
     * The upstream apt binary has paths like Dir::Etc compiled in as
     * /data/data/com.termux/files/usr/etc/apt. Binary patching can fail silently
     * when there isn't enough null-byte padding after the old string, so this
     * config file forces apt to use the correct paths regardless of patching result.
     */
    private static void deployAptConf() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File aptConfDir = new File(prefix, "etc/apt/apt.conf.d");
        if (!aptConfDir.exists() && !aptConfDir.mkdirs()) {
            throw new Exception("Failed to create " + aptConfDir.getAbsolutePath());
        }

        File confFile = new File(aptConfDir, "99hermes-paths.conf");

        String hookScript = prefix + "/lib/hermes/apt-pre-install-patch";
        String postInvokeScript = prefix + "/lib/hermes/apt-post-invoke-fix-paths";
        String content = "// Hermes: override compiled-in directory paths for renamed package\n"
                + "Dir \"" + prefix + "\";\n"
                + "Dir::State \"" + prefix + "/var/lib/apt\";\n"
                + "Dir::State::status \"" + prefix + "/var/lib/dpkg/status\";\n"
                + "Dir::Cache \"" + prefix + "/var/cache/apt\";\n"
                + "Dir::Etc \"" + prefix + "/etc/apt\";\n"
                + "Dir::Bin::methods \"" + prefix + "/lib/apt/methods\";\n"
                + "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n"
                + "Dir::Log \"" + prefix + "/var/log/apt\";\n"
                + "Dpkg::Options { \"--admindir=" + prefix + "/var/lib/dpkg\"; \"--force-confold\"; };\n"
                + "DPkg::Pre-Install-Pkgs { \"" + hookScript + "\"; };\n"
                + "DPkg::Post-Invoke { \"" + postInvokeScript + "\"; };\n";

        try (FileOutputStream out = new FileOutputStream(confFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(confFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed apt.conf to " + confFile.getAbsolutePath());
    }

    /**
     * Deploy the apt-pre-install-patch script that patches .deb files between
     * APT hash verification and dpkg installation. This runs as a
     * DPkg::Pre-Install-Pkgs hook, so APT's hash check passes first, then
     * we rewrite maintainer scripts and ELF binary paths from
     * com.termux → com.hermux before dpkg sees the package.
     */
    private static void deployAptPreInstallHook() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File hookDir = new File(prefix, "lib/hermes");
        if (!hookDir.exists() && !hookDir.mkdirs()) {
            throw new Exception("Failed to create " + hookDir.getAbsolutePath());
        }

        File script = new File(hookDir, "apt-pre-install-patch");

        String content = "#!" + prefix + "/bin/sh\n"
                + "# Hermes: DPkg::Pre-Install-Pkgs hook\n"
                + "# Patches .deb files between APT hash verification and dpkg install.\n"
                + "# stdin: one .deb path per line.\n"
                + "\n"
                + "OLD='/data/data/com.termux'\n"
                + "NEW='/data/data/com.hermux'\n"
                + "\n"
                + "patch_deb() {\n"
                + "  deb=\"$1\"\n"
                + "  tmpdir=$(mktemp -d)\n"
                + "  deb_base=$(basename \"$deb\")\n"
                + "  echo \"hermes-hook: patching $deb_base...\" >&2\n"
                + "\n"
                + "  if ! dpkg-deb -R \"$deb\" \"$tmpdir\" >/dev/null 2>&1; then\n"
                + "    echo \"hermes-hook: WARNING: failed to extract $deb_base, skipping\" >&2\n"
                + "    rm -rf \"$tmpdir\"\n"
                + "    return\n"
                + "  fi\n"
                + "\n"
                + "  # Patch maintainer scripts (postinst, prerm, etc.) — the primary\n"
                + "  # source of path issues. ELF binaries are left to LD_PRELOAD.\n"
                + "  patched_file=\"${tmpdir}.patched\"\n"
                + "  : > \"$patched_file\"\n"
                + "  if [ -d \"$tmpdir/DEBIAN\" ]; then\n"
                + "    for f in \"$tmpdir/DEBIAN/\"*; do\n"
                + "      [ -f \"$f\" ] || continue\n"
                + "      case \"$(basename \"$f\")\" in\n"
                + "        control|conffiles|md5sums|shlibs|symbols) continue ;;\n"
                + "      esac\n"
                + "      if grep -q -- \"$OLD\" \"$f\" 2>/dev/null; then\n"
                + "        perms=$(stat -c '%a' \"$f\" 2>/dev/null || echo 755)\n"
                + "        sed -i \"s|$OLD|$NEW|g\" \"$f\"\n"
                + "        chmod \"$perms\" \"$f\"\n"
                + "        echo y >> \"$patched_file\"\n"
                + "      fi\n"
                + "    done\n"
                + "  fi\n"
                + "\n"
                + "  # Patch non-ELF text files (configs, data files).\n"
                + "  grep -rl -- \"$OLD\" \"$tmpdir\"/etc \"$tmpdir\"/share \"$tmpdir\"/var 2>/dev/null | while read -r f; do\n"
                + "    case \"$(dd if=\"$f\" bs=4 count=1 2>/dev/null | od -A n -t x1 | tr -d ' \\n')\" in\n"
                + "      7f454c46) ;; # ELF: skip, handled by path_rewrite.so at runtime\n"
                + "      *)\n"
                + "        sed -i \"s|$OLD|$NEW|g\" \"$f\" 2>/dev/null || true\n"
                + "        echo y >> \"$patched_file\"\n"
                + "        ;;\n"
                + "    esac\n"
                + "  done\n"
                + "\n"
                + "  if [ ! -s \"$patched_file\" ]; then\n"
                + "    rm -f \"$patched_file\"\n"
                + "    rm -rf \"$tmpdir\"\n"
                + "    return\n"
                + "  fi\n"
                + "  rm -f \"$patched_file\"\n"
                + "\n"
                + "  # Ensure DEBIAN scripts have valid permissions\n"
                + "  # (dpkg-deb requires >=0555; sed -i may reset to 644)\n"
                + "  if [ -d \"$tmpdir/DEBIAN\" ]; then\n"
                + "    for f in \"$tmpdir/DEBIAN/\"*; do\n"
                + "      [ -f \"$f\" ] || continue\n"
                + "      case \"$(basename \"$f\")\" in\n"
                + "        control|conffiles|md5sums|shlibs|symbols) ;;\n"
                + "        *) chmod 0755 \"$f\" ;;\n"
                + "      esac\n"
                + "    done\n"
                + "  fi\n"
                + "\n"
                + "  # Repack — keep original .deb if repack fails so LD_PRELOAD can\n"
                + "  # still handle path translation at runtime.\n"
                + "  deb_backup=\"${deb}.hermes-bak\"\n"
                + "  cp \"$deb\" \"$deb_backup\" 2>/dev/null\n"
                + "  repack_err=$(dpkg-deb -b \"$tmpdir\" \"$deb\" 2>&1)\n"
                + "  if [ $? -ne 0 ]; then\n"
                + "    echo \"hermes-hook: ERROR: failed to repack $deb_base: $repack_err\" >&2\n"
                + "    mv \"$deb_backup\" \"$deb\" 2>/dev/null\n"
                + "  fi\n"
                + "  rm -f \"$deb_backup\"\n"
                + "  rm -rf \"$tmpdir\"\n"
                + "}\n"
                + "\n"
                + "while IFS= read -r deb_path; do\n"
                + "  case \"$deb_path\" in\n"
                + "    *.deb) patch_deb \"$deb_path\" ;;\n"
                + "  esac\n"
                + "done\n";

        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(script.getAbsolutePath(), 0755);
        Logger.logInfo(LOG_TAG, "Deployed apt pre-install hook to " + script.getAbsolutePath());
    }

    /**
     * Deploy the apt-post-invoke-fix-paths script that rewrites dpkg's file
     * tracking database (.list files) from com.termux to com.hermux paths.
     * Without this, dpkg can't find old files during upgrades, causing
     * "unable to delete old directory: Directory not empty" warnings.
     */
    private static void deployAptPostInvokeHook() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File script = new File(prefix, "lib/hermes/apt-post-invoke-fix-paths");
        File scriptDir = script.getParentFile();
        if (scriptDir != null && !scriptDir.exists() && !scriptDir.mkdirs()) {
            throw new Exception("Failed to create " + scriptDir.getAbsolutePath());
        }

        String oldPath = "/data/data/com.termux";
        String newPath = "/data/data/com.hermux";
        String infoDir = prefix + "/var/lib/dpkg/info";

        String content = "#!/system/bin/sh\n"
                + "# Hermes: fix dpkg file tracking after install/upgrade\n"
                + "# Rewrite com.termux paths in .list files to com.hermux\n"
                + "cd '" + infoDir + "' 2>/dev/null || exit 0\n"
                + "for _f in *.list; do\n"
                + "  [ -f \"$_f\" ] || continue\n"
                + "  case \"$(grep -c '" + oldPath + "' \"$_f\" 2>/dev/null)\" in\n"
                + "    0|'') continue ;;\n"
                + "  esac\n"
                + "  sed -i 's|" + oldPath + "|" + newPath + "|g' \"$_f\"\n"
                + "done\n";

        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(script.getAbsolutePath(), 0755);
        Logger.logInfo(LOG_TAG, "Deployed apt post-invoke hook to " + script.getAbsolutePath());
    }

    /**
     * Deploy dpkg configuration to override compiled-in admindir path.
     * dpkg has /data/data/com.termux/files/usr/var/lib/dpkg baked in,
     * and binary patching may fail for the same null-padding reasons as apt.
     */
    private static void deployDpkgConf() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File dpkgCfgDir = new File(prefix, "etc/dpkg/dpkg.cfg.d");
        if (!dpkgCfgDir.exists() && !dpkgCfgDir.mkdirs()) {
            throw new Exception("Failed to create " + dpkgCfgDir.getAbsolutePath());
        }

        File confFile = new File(dpkgCfgDir, "hermes-paths");

        String content = "# Hermes: override compiled-in dpkg directory paths\n"
                + "admindir " + prefix + "/var/lib/dpkg\n";

        try (FileOutputStream out = new FileOutputStream(confFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(confFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed dpkg.conf to " + confFile.getAbsolutePath());
    }

    /**
     * Ensure apt's required working directories exist. Bootstrap extraction
     * wipes $PREFIX and the ZIP doesn't include empty cache directories,
     * causing apt to fail with "Archives directory …/partial is missing".
     */
    private static void ensureAptDirectories(String prefix) {
        String[] dirs = {
            "/var/cache/apt/archives/partial",
            "/var/lib/apt/lists/partial",
            "/var/log/apt"
        };
        for (String suffix : dirs) {
            File dir = new File(prefix + suffix);
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Could not create apt directory: " + dir.getAbsolutePath());
            }
        }
    }

    /**
     * Overwrite sources.list with TUNA mirror for faster downloads in China.
     * The bootstrap may contain any arbitrary mirror URL that we can't predict,
     * so we directly write the TUNA source instead of pattern-matching.
     * Packages are identical regardless of mirror — path_rewrite.so handles
     * the com.termux → com.hermux translation at runtime.
     */
    private static void deployAptMirror(String prefix) throws Exception {
        File sourcesList = new File(prefix, "etc/apt/sources.list");
        File sourcesDir = sourcesList.getParentFile();
        if (sourcesDir != null && !sourcesDir.exists() && !sourcesDir.mkdirs()) {
            throw new Exception("Failed to create " + sourcesDir.getAbsolutePath());
        }
        String tunaSource = "deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n";
        try (FileOutputStream out = new FileOutputStream(sourcesList)) {
            out.write(tunaSource.getBytes("UTF-8"));
        }
        Os.chmod(sourcesList.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Set apt source to TUNA mirror");
    }

    /**
     * Patch the dpkg database files to replace /data/data/com.termux paths.
     * The bootstrap ZIP's var/lib/dpkg/ directory contains:
     * - info/*.list files with com.termux file paths
     * - info/*.postinst/prerm scripts with com.termux shebangs and paths
     * - status file with package metadata
     * These were NOT patched by earlier CI scripts, causing dpkg to fail
     * with error code (1) when running maintainer scripts or checking files.
     */
    /**
     * Patch text config files under $PREFIX/etc and $PREFIX/share to replace
     * /data/data/com.termux paths. Called after bootstrap extraction as a safety
     * net in case the CI patch-bootstrap step missed some files.
     */
    static void patchBootstrapTextFiles(String prefixPath) {
        final String oldPrefix = "/data/data/com.termux";
        final String newPrefix = "/data/data/com.hermux";
        int patched = 0;

        for (String dir : new String[]{"etc", "share"}) {
            File root = new File(prefixPath, dir);
            if (!root.isDirectory()) continue;
            patched += patchTextFilesRecursive(root, oldPrefix, newPrefix);
        }

        Logger.logInfo(LOG_TAG, "Bootstrap text file patching: " + patched + " files patched");
    }

    private static final java.util.Set<String> BINARY_EXTENSIONS;
    static {
        java.util.Set<String> exts = new java.util.HashSet<>();
        for (String e : new String[]{
            ".gz", ".xz", ".bz2", ".lz4", ".zst",
            ".pyc", ".pyo",
            ".mo", ".gmo",
            ".a", ".o", ".so",
            ".png", ".jpg", ".ico", ".pdf"
        }) exts.add(e);
        BINARY_EXTENSIONS = java.util.Collections.unmodifiableSet(exts);
    }

    private static int patchTextFilesRecursive(File dir, String oldStr, String newStr) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int patched = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                patched += patchTextFilesRecursive(f, oldStr, newStr);
            } else {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot).toLowerCase())) continue;
                if (patchTextFileSafe(f, oldStr, newStr)) patched++;
            }
        }
        return patched;
    }

    static void patchDpkgDatabase(String prefixPath) {
        final String oldPrefix = "/data/data/com.termux";
        final String newPrefix = "/data/data/com.hermux";
        int patched = 0;

        File dpkgInfo = new File(prefixPath, "var/lib/dpkg/info");
        if (dpkgInfo.isDirectory()) {
            File[] files = dpkgInfo.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                }
            }
        }

        for (String name : new String[]{"status", "available", "statoverride", "diversions"}) {
            File f = new File(prefixPath, "var/lib/dpkg/" + name);
            if (f.exists()) {
                if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
            }
        }

        for (String sub : new String[]{"alternatives", "triggers", "methods"}) {
            File dir = new File(prefixPath, "var/lib/dpkg/" + sub);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                    }
                }
            }
        }

        // Patch apt's own data directories
        File aptListsDir = new File(prefixPath, "var/lib/apt/lists");
        if (aptListsDir.isDirectory()) {
            File[] files = aptListsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                }
            }
        }

        Logger.logInfo(LOG_TAG, "Dpkg database patching: " + patched + " files patched");
    }

    private static boolean patchTextFileSafe(File file, String oldStr, String newStr) {
        try {
            byte[] raw = readFileBytes(file);
            if (raw == null) return false;
            String content = new String(raw, "UTF-8");
            if (!content.contains(oldStr)) return false;
            content = content.replace(oldStr, newStr);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes("UTF-8"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readFileBytes(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fast config-only SSH migration. Called from runContextMigrations() on main thread.
     * Only deploys config files (sshd_config, passwd entry, boot script).
     * Skips if sshd binary is not installed (will be picked up by background setup).
     */
    private static void deploySshConfig(String nativeLibDir) throws Exception {
        File sshd = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sshd");
        if (!sshd.exists()) {
            Logger.logInfo(LOG_TAG, "SSH config: sshd not found, skipping");
            throw new Exception("sshd not installed yet, deferring SSH migration");
        }
        deploySshdConfig();
        deploySshPassword();
        deploySshBootScript(nativeLibDir);
        Logger.logInfo(LOG_TAG, "SSH config deployed (migration)");
    }

    /**
     * Full SSH setup in background thread. Installs openssh if needed, generates host keys,
     * deploys config, sets password, and starts sshd.
     */
    static void setupSshInBackground(Context context) {
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        new Thread(() -> {
            try {
                setupSshInternal(nativeLibDir);
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "SSH background setup failed: " + e.getMessage());
            }
        }, "SSH-Setup").start();
    }

    /**
     * Check if SSH setup is needed for existing installations and run it in background.
     * Called from installIfNeeded() when Hermes is already installed.
     */
    static void ensureSshSetup(Context context) {
        File sshd = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sshd");
        File marker = new File(HERMES_SSH_SETUP_MARKER_FILE);
        if (!sshd.exists() || !marker.exists()) {
            Logger.logInfo(LOG_TAG, "SSH not set up, scheduling background setup");
            setupSshInBackground(context);
        }
    }

    private static void setupSshInternal(String nativeLibDir) throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File sshd = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sshd");

        if (!sshd.exists()) {
            Logger.logInfo(LOG_TAG, "SSH: installing openssh...");
            runShellCommand("pkg install -y openssh");
        }

        // Generate host keys if missing
        File sshHostKey = new File(prefix, "etc/ssh/ssh_host_rsa_key");
        if (!sshHostKey.exists()) {
            Logger.logInfo(LOG_TAG, "SSH: generating host keys...");
            runShellCommand("ssh-keygen -A");
            // Fix permissions on host keys
            File sshDir = new File(prefix, "etc/ssh");
            File[] keyFiles = sshDir.listFiles((dir, name) -> name.startsWith("ssh_host_") && name.endsWith("_key"));
            if (keyFiles != null) {
                for (File kf : keyFiles) {
                    Os.chmod(kf.getAbsolutePath(), 0600);
                }
            }
        }

        deploySshdConfig();
        deploySshPassword();
        deploySshBootScript(nativeLibDir);

        // Start sshd if not already running
        try {
            runShellCommand("pgrep -x sshd >/dev/null 2>&1 || " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "SSH: failed to start sshd (non-fatal): " + e.getMessage());
        }

        // Write marker
        try (FileOutputStream out = new FileOutputStream(HERMES_SSH_SETUP_MARKER_FILE)) {
            out.write((HERMES_SSH_SETUP_VERSION + "\n").getBytes("UTF-8"));
        }
        Logger.logInfo(LOG_TAG, "SSH setup complete (background)");
    }

    private static void deploySshdConfig() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File sshDir = new File(prefix, "etc/ssh");
        if (!sshDir.exists() && !sshDir.mkdirs()) {
            throw new Exception("Failed to create " + sshDir.getAbsolutePath());
        }

        File configFile = new File(sshDir, "sshd_config");
        String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String homePath = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String libPath = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH;
        String pathRewriteLib = libPath + "/libpath_rewrite.so";

        String content = "# Hermes SSH Server Configuration\n"
                + "Port " + SSH_DEFAULT_PORT + "\n"
                + "PasswordAuthentication yes\n"
                + "PrintMotd yes\n"
                + "SetEnv PATH=" + binPath + ":/system/bin:/system/xbin\n"
                + "SetEnv HOME=" + homePath + "\n"
                + "SetEnv PREFIX=" + prefix + "\n"
                + "SetEnv LD_LIBRARY_PATH=" + libPath + "\n"
                + "SetEnv LD_PRELOAD=" + pathRewriteLib + "\n"
                + "SetEnv TERMUX_VERSION=" + com.termux.BuildConfig.VERSION_NAME + "\n"
                + "Subsystem sftp " + prefix + "/libexec/sftp-server\n";

        try (FileOutputStream out = new FileOutputStream(configFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(configFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed sshd_config to " + configFile.getAbsolutePath());
    }

    private static void deploySshPassword() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String passwdFile = prefix + "/etc/passwd";

        // Generate password hash using openssl with explicit salt
        String hash = runShellCommandCapture("openssl passwd -6 -salt hermes hermes");
        if (hash == null || hash.isEmpty() || hash.startsWith("$0$") || hash.contains("error")) {
            // Fallback: try MD5 crypt format
            hash = runShellCommandCapture("openssl passwd -1 -salt hermes hermes");
        }
        if (hash == null || hash.isEmpty()) {
            Logger.logWarn(LOG_TAG, "SSH: failed to generate password hash, using shell passwd");
            // Last resort: set password via shell passwd command
            try {
                runShellCommand("printf 'hermes\\nhermes\\n' | passwd 2>/dev/null || true");
            } catch (Exception ignored) {}
            return;
        }

        // Determine current user
        String user = runShellCommandCapture("whoami 2>/dev/null || id -un 2>/dev/null");
        if (user == null || user.isEmpty()) user = "u0_a" + android.os.Process.myUid();

        String uid = runShellCommandCapture("id -u");
        String gid = runShellCommandCapture("id -g");
        if (uid == null || uid.isEmpty()) uid = String.valueOf(android.os.Process.myUid());
        if (gid == null || gid.isEmpty()) gid = uid;

        String entry = user + ":" + hash.trim() + ":" + uid.trim() + ":" + gid.trim()
                + "::" + TermuxConstants.TERMUX_HOME_DIR_PATH + ":"
                + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n";

        File passwd = new File(passwdFile);
        boolean userExists = false;
        if (passwd.exists()) {
            String content = readFile(passwd);
            if (content.contains(user + ":")) {
                userExists = true;
            }
        }

        if (userExists) {
            // Update password hash for existing user
            runShellCommand("sed -i 's|^" + user + ":[^:]*:|" + user + ":"
                    + hash.trim() + ":|' " + passwdFile);
        } else {
            try (FileOutputStream out = new FileOutputStream(passwdFile, true)) {
                out.write(entry.getBytes("UTF-8"));
            }
        }
        Logger.logInfo(LOG_TAG, "SSH password configured for user " + user);
    }

    private static void deploySshBootScript(String nativeLibDir) throws Exception {
        File bootDir = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR;
        FileUtils.createDirectoryFile(bootDir.getAbsolutePath());

        String pathRewriteLib = nativeLibDir + "/libpath_rewrite.so";

        String script = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n"
                + "# Auto-start SSH daemon on boot\n"
                + "if [ -f " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd ]; then\n"
                + "    export LD_LIBRARY_PATH=" + TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "\n"
                + "    if [ -f " + pathRewriteLib + " ]; then\n"
                + "        export LD_PRELOAD=" + pathRewriteLib + "\n"
                + "    fi\n"
                + "    " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd\n"
                + "fi\n";

        File scriptFile = new File(SSH_BOOT_SCRIPT);
        try (FileOutputStream out = new FileOutputStream(scriptFile)) {
            out.write(script.getBytes("UTF-8"));
        }
        Os.chmod(scriptFile.getAbsolutePath(), 0700);
        Logger.logInfo(LOG_TAG, "Deployed SSH boot script to " + SSH_BOOT_SCRIPT);
    }

    private static void deployShellProfile() throws Exception {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File bashrc = new File(home, ".bashrc");
        // Do NOT source $PREFIX/etc/profile from .bashrc — the profile already
        // sources .bashrc for interactive bash login shells, so doing it here
        // creates infinite recursion → stack overflow → SIGSEGV.
        String hermesBlock = "\n# Hermes Terminal Configuration\n"
                + "export USER=hermes\n"
                + "export LOGNAME=hermes\n"
                + "\n"
                + "export PS1='\\[\\e[1;32m\\]hermes@hermes\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n";

        if (bashrc.exists()) {
            String content = readFile(bashrc);
            boolean modified = false;

            // Remove the profile sourcing that causes infinite recursion.
            String oldProfileSource = "if [ -f \"$PREFIX/etc/profile\" ]; then\n"
                    + "    . \"$PREFIX/etc/profile\"\n"
                    + "fi\n";
            if (content.contains(oldProfileSource)) {
                content = content.replace(oldProfileSource, "");
                modified = true;
                Logger.logInfo(LOG_TAG, "Removed recursive profile source from .bashrc");
            }

            // Remove the old LD_PRELOAD block.
            String oldLdPreloadBlock = "# Ensure path rewrite is always active\n"
                    + "if [ -z \"$LD_PRELOAD\" ] && [ -f \"$PREFIX/lib/libpath_rewrite.so\" ]; then\n"
                    + "    export LD_PRELOAD=\"$PREFIX/lib/libpath_rewrite.so\"\n"
                    + "fi\n";
            if (content.contains(oldLdPreloadBlock)) {
                content = content.replace(oldLdPreloadBlock, "");
                modified = true;
                Logger.logInfo(LOG_TAG, "Removed old LD_PRELOAD block from .bashrc");
            }

            if (modified) {
                try (FileOutputStream out = new FileOutputStream(bashrc)) {
                    out.write(content.getBytes("UTF-8"));
                }
            }

            if (!content.contains("Hermes Terminal Configuration")) {
                try (FileOutputStream out = new FileOutputStream(bashrc, true)) {
                    out.write(hermesBlock.getBytes("UTF-8"));
                }
                Logger.logInfo(LOG_TAG, "Appended Hermes shell profile to .bashrc");
            }
        } else {
            try (FileOutputStream out = new FileOutputStream(bashrc)) {
                out.write(hermesBlock.getBytes("UTF-8"));
            }
            Os.chmod(bashrc.getAbsolutePath(), 0644);
            Logger.logInfo(LOG_TAG, "Created .bashrc with Hermes shell profile");
        }
    }

    /**
     * Deploy the LD_PRELOAD path rewrite library from the APK's native libs
     * to $PREFIX/lib/libpath_rewrite.so. This library intercepts all
     * filesystem calls and rewrites /data/data/com.termux/ paths to
     * /data/data/com.hermux/, fixing ALL binaries with compiled-in
     * old paths at once.
     */
    private static void deployPathRewrite(Context context) throws Exception {
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File srcFile = new File(nativeLibDir, "libpath_rewrite.so");
        File dstFile = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "libpath_rewrite.so");

        if (!srcFile.exists()) {
            throw new Exception("libpath_rewrite.so not found in " + nativeLibDir);
        }

        // Skip if destination already exists with same size — avoid overwriting
        // a library that may be mmap'd by running terminal processes via LD_PRELOAD.
        // In-place overwrite invalidates mmap pages and causes SIGSEGV.
        if (dstFile.exists() && dstFile.length() == srcFile.length()) {
            Logger.logInfo(LOG_TAG, "libpath_rewrite.so already deployed (" + dstFile.length() + " bytes), skipping");
            return;
        }

        File libDir = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new Exception("Failed to create " + libDir.getAbsolutePath());
        }

        // Write to a temp file first, then rename atomically. Overwriting the
        // destination in-place can kill processes that have it loaded via
        // LD_PRELOAD (the kernel invalidates the mmap'd pages).
        File tmpFile = new File(dstFile.getAbsolutePath() + ".tmp");
        tmpFile.delete(); // clean up stale temp from a previous failed deploy
        try (FileInputStream fis = new FileInputStream(srcFile);
             FileOutputStream fos = new FileOutputStream(tmpFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
        }
        Os.chmod(tmpFile.getAbsolutePath(), 0644);
        if (!tmpFile.renameTo(dstFile)) {
            // renameTo may fail across mount points; fall back to direct write.
            // This may kill processes with the library mmap'd, but is better
            // than leaving no library at all.
            Logger.logWarn(LOG_TAG, "Atomic rename failed for libpath_rewrite.so, falling back to direct write");
            tmpFile.delete();
            try (FileInputStream fis = new FileInputStream(srcFile);
                 FileOutputStream fos = new FileOutputStream(dstFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
            }
            Os.chmod(dstFile.getAbsolutePath(), 0644);
        }
        Logger.logInfo(LOG_TAG, "Deployed path rewrite lib to " + dstFile.getAbsolutePath());
    }

    private static String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /** Fix execute permissions on critical Termux binaries after hermes-agent install. */
    private static void fixBinaryPermissions() {
        String binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String[] binaries = {"bash", "sh", "login", "cat", "chmod", "cp", "ls", "mkdir", "rm",
                "hermes", "git", "curl", "python", "pip", "sed", "grep", "tar", "env"};
        for (String name : binaries) {
            File bin = new File(binDir, name);
            if (bin.exists()) {
                try {
                    Os.chmod(bin.getAbsolutePath(), 0755);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Could not chmod " + bin.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
        Logger.logInfo(LOG_TAG, "Binary permissions fixed");
    }

    private static boolean validateHermesBinary() {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            File hermesBin = new File(binPath, "hermes");
            if (!hermesBin.exists()) {
                Logger.logWarn(LOG_TAG, "Validation: hermes binary not found");
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(hermesBin.getAbsolutePath(), "--help");
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", binPath + ":/system/bin");
            pb.environment().put("PREFIX", prefix);
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
            if (new File(pathRewriteLib).exists()) {
                pb.environment().put("LD_PRELOAD", pathRewriteLib);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {}
            }
            int exit = p.waitFor();
            p.destroy();
            if (exit != 0) {
                Logger.logWarn(LOG_TAG, "Validation: hermes --help exited with code " + exit);
                return false;
            }
            Logger.logInfo(LOG_TAG, "Hermes binary validated successfully");
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Validation exception: " + e.getMessage());
            return false;
        }
    }

    public static class RetryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_RETRY_INSTALL.equals(intent.getAction())) {
                // Delete marker to allow re-install
                new File(HERMES_MARKER_FILE).delete();
                retryInstall(context.getApplicationContext());
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Run a shell command in the Termux environment, throwing on non-zero exit. */
    public static void runShellCommand(String command) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) {
            throw new RuntimeException("bash not available");
        }
        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", command);
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (reader.readLine() != null) {}
        }
        int exit = p.waitFor();
        p.destroy();
        if (exit != 0) {
            throw new RuntimeException("Shell command failed (" + exit + "): " + command);
        }
    }

    /** Run a shell command and capture the first line of stdout. */
    static String runShellCommandCapture(String command) {
        try {
            String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            if (!new File(bashPath).exists()) return null;
            ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", command);
            String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            pb.environment().put("PREFIX", prefix);
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
            if (new File(pathRewriteLib).exists()) {
                pb.environment().put("LD_PRELOAD", pathRewriteLib);
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                result = reader.readLine();
            }
            // Drain stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                while (reader.readLine() != null) {}
            }
            p.waitFor();
            p.destroy();
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
