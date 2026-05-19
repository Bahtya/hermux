package com.termux.app.hermes;

import android.content.Context;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Extracts a pre-built Python venv from APK assets to the hermes-agent directory.
 * The venv is built in CI using Termux Docker and bundled as venv-aarch64.tar.gz.
 */
public class VenvExtractor {
    private static final String LOG_TAG = "VenvExtractor";
    private static final String ASSET_NAME = "venv-aarch64.tar";

    private static void emit(HermesInstallHelper.ProgressCallback callback, String msg) {
        Logger.logInfo(LOG_TAG, msg);
        if (callback != null) callback.onOutput(msg);
        synchronized (HermesInstallHelper.sOutputBuffer) {
            HermesInstallHelper.sOutputBuffer.append(msg).append("\n");
            if (HermesInstallHelper.sOutputBuffer.length() > 50000) {
                HermesInstallHelper.sOutputBuffer.delete(0, 25000);
            }
        }
    }

    public static boolean hasPrebuiltVenv(Context context) {
        try {
            context.getAssets().open(ASSET_NAME).close();
            return true;
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Asset '" + ASSET_NAME + "' not found: " + e.getMessage());
            // List available assets for debugging
            try {
                String[] assets = context.getAssets().list("");
                if (assets != null) {
                    StringBuilder sb = new StringBuilder("Available assets: ");
                    for (String a : assets) sb.append(a).append(", ");
                    Logger.logInfo(LOG_TAG, sb.toString());
                }
            } catch (IOException ignored) {}
            return false;
        }
    }

    /**
     * Extract the pre-built venv from APK assets to ~/.hermes/hermes-agent/venv.
     * After extraction, fixes com.termux paths to com.hermux and sets execute permissions.
     *
     * @return true if extraction succeeded, false otherwise
     */
    public static boolean extractVenv(Context context) {
        return extractVenv(context, null);
    }

    public static boolean extractVenv(Context context, HermesInstallHelper.ProgressCallback callback) {
        String hermesDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/hermes-agent";
        String venvDir = hermesDir + "/venv";
        String tmpFile = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/venv-aarch64.tar";

        // Check if venv already exists and is valid
        if (new File(venvDir + "/bin/python").exists()) {
            emit(callback, "Venv already exists, skipping extraction");
            Logger.logInfo(LOG_TAG, "Venv already exists at " + venvDir + ", skipping extraction");
            return true;
        }

        // Copy tar from assets to tmp
        emit(callback, "Copying venv from APK to temp...");
        Logger.logInfo(LOG_TAG, "Copying " + ASSET_NAME + " from APK assets to " + tmpFile);
        try {
            copyAsset(context, ASSET_NAME, tmpFile);
        } catch (IOException e) {
            emit(callback, "Failed to copy venv asset: " + e.getMessage());
            Logger.logError(LOG_TAG, "Failed to copy venv asset: " + e.getMessage());
            return false;
        }
        long tarSize = new File(tmpFile).length();
        emit(callback, "Copied " + (tarSize / 1024 / 1024) + " MB, extracting...");

        // Extract using tar
        Logger.logInfo(LOG_TAG, "Extracting venv to " + hermesDir);
        new File(hermesDir).mkdirs();

        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) {
            Logger.logError(LOG_TAG, "bash not available for venv extraction");
            return false;
        }

        String extractCmd =
            "cd \"" + hermesDir + "\" && "
            + "tar xzf \"" + tmpFile + "\" && "
            + "rm -f \"" + tmpFile + "\" && "
            // Fix symlinks pointing to com.termux absolute paths
            + "find \"" + venvDir + "\" -type l -exec sh -c "
            + "'readlink \"$1\" | grep -q com.termux && { tgt=$(readlink \"$1\" | sed \"s|com.termux|com.hermux|g\"); rm \"$1\"; ln -sf \"$tgt\" \"$1\"; }' _ {} \\; && "
            // Fix com.termux → com.hermux paths in all text files
            + "find \"" + venvDir + "\" -type f -exec grep -l 'com\\.termux' {} + 2>/dev/null | "
            + "  while IFS= read -r f; do sed -i 's|/data/data/com\\.termux|/data/data/com.hermux|g' \"$f\"; done && "
            // Set execute permissions on bin/*
            + "chmod 755 \"" + venvDir + "/bin/\"* 2>/dev/null; "
            + "echo 'venv extraction complete'";

        try {
            ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", extractCmd);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":/system/bin:/system/xbin");
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    Logger.logInfo(LOG_TAG, "extract: " + line);
                    if (callback != null) callback.onOutput(line);
                    synchronized (HermesInstallHelper.sOutputBuffer) {
                        HermesInstallHelper.sOutputBuffer.append(line).append("\n");
                    }
                }
            }

            int exit = p.waitFor();
            if (exit != 0) {
                emit(callback, "Venv extraction failed (exit " + exit + "): " + output);
                Logger.logError(LOG_TAG, "Venv extraction failed (exit " + exit + "): " + output);
                return false;
            }
        } catch (Exception e) {
            emit(callback, "Venv extraction error: " + e.getMessage());
            Logger.logError(LOG_TAG, "Venv extraction error: " + e.getMessage());
            return false;
        }

        // Verify — check bin/ directory has content; python may be a dangling symlink
        // (target $PREFIX/bin/python doesn't exist until apt install python runs later)
        File binDir = new File(venvDir + "/bin");
        String[] binContents = binDir.list();
        if (binContents == null || binContents.length == 0) {
            emit(callback, "venv/bin/ is empty — extraction may have failed");
            Logger.logError(LOG_TAG, "venv/bin/ is empty — extraction may have failed");
            return false;
        }
        boolean hasPython = false;
        for (String name : binContents) {
            if (name.equals("python")) { hasPython = true; break; }
        }
        if (!hasPython) {
            emit(callback, "venv/bin/python not found after extraction");
            Logger.logError(LOG_TAG, "venv/bin/python not found after extraction");
            return false;
        }

        Logger.logInfo(LOG_TAG, "Venv extracted successfully to " + venvDir);
        return true;
    }

    private static void copyAsset(Context context, String assetName, String destPath) throws IOException {
        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }
}
