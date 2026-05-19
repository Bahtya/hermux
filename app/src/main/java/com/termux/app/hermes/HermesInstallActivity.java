package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.HermesInstaller;
import com.termux.app.TermuxInstaller;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

public class HermesInstallActivity extends AppCompatActivity {

    private static final String MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[0-9;]*[a-zA-Z]");

    private TextView mStatusText;
    private TextView mTerminalOutput;
    private ScrollView mTerminalScroll;
    private ProgressBar mProgressBar;
    private Button mRetryButton;
    private Button mSkipButton;
    private Button mCopyButton;
    private LinearLayout mStepContainer;
    private final StringBuilder mTerminalBuffer = new StringBuilder();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mSuccess = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // --- Toolbar ---
        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setTitle(R.string.install_title);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setBackgroundColor(0xFF1A1A2E);
        int toolbarHeight = dp(56);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight));
        root.addView(toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, 0);

        // --- Step indicators (horizontal) ---
        mStepContainer = new LinearLayout(this);
        mStepContainer.setOrientation(LinearLayout.HORIZONTAL);
        mStepContainer.setGravity(Gravity.CENTER_VERTICAL);
        mStepContainer.setPadding(0, 0, 0, dp(12));
        content.addView(mStepContainer);

        // --- Progress bar ---
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        content.addView(mProgressBar, pbParams);

        // --- Status text ---
        mStatusText = new TextView(this);
        mStatusText.setText(R.string.install_checking);
        mStatusText.setTextSize(13);
        mStatusText.setPadding(0, dp(8), 0, dp(8));
        mStatusText.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(mStatusText);

        root.addView(content);

        // --- Terminal panel ---
        mTerminalScroll = new ScrollView(this);
        mTerminalScroll.setBackgroundColor(0xFF1A1A2E);
        mTerminalScroll.setFillViewport(true);
        mTerminalScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setFillViewport(true);

        mTerminalOutput = new TextView(this);
        mTerminalOutput.setTypeface(Typeface.MONOSPACE);
        mTerminalOutput.setTextSize(12);
        mTerminalOutput.setTextColor(0xFF4AF626);
        mTerminalOutput.setPadding(dp(12), dp(12), dp(12), dp(12));
        mTerminalOutput.setLineSpacing(dp(2), 1f);
        mTerminalOutput.setText("$ Waiting for installation...\n");
        mTerminalBuffer.append("$ Waiting for installation...\n");

        hScroll.addView(mTerminalOutput);
        mTerminalScroll.addView(hScroll);
        root.addView(mTerminalScroll);

        // --- Button bar ---
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setGravity(Gravity.CENTER_HORIZONTAL);
        buttonBar.setPadding(pad, dp(8), pad, dp(16));

        mCopyButton = new Button(this);
        mCopyButton.setText(R.string.install_copy_log);
        mCopyButton.setVisibility(View.VISIBLE);
        mCopyButton.setOnClickListener(v -> copyTerminalContent());
        buttonBar.addView(mCopyButton);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                dp(8), LinearLayout.LayoutParams.WRAP_CONTENT);

        mRetryButton = new Button(this);
        mRetryButton.setText(R.string.install_retry);
        mRetryButton.setVisibility(View.GONE);
        mRetryButton.setOnClickListener(v -> startInstallation());
        buttonBar.addView(mRetryButton, btnParams);

        mSkipButton = new Button(this);
        mSkipButton.setText(R.string.install_skip);
        mSkipButton.setVisibility(View.VISIBLE);
        mSkipButton.setOnClickListener(v -> proceedToNext());
        buttonBar.addView(mSkipButton);

        root.addView(buttonBar);

        setContentView(root);

        if (new File(MARKER_FILE).exists()) {
            mSuccess = true;
            showAlreadyInstalled();
            return;
        }

        startInstallation();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void appendTerminal(String text) {
        appendTerminal(text, true);
    }

    private void appendTerminal(String text, boolean newLine) {
        String clean = stripAnsi(text);
        mTerminalBuffer.append(clean);
        if (newLine) mTerminalBuffer.append("\n");
        mHandler.post(() -> {
            mTerminalOutput.setText(mTerminalBuffer.toString());
            mTerminalScroll.post(() -> mTerminalScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void copyTerminalContent() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Hermes Install Log", mTerminalBuffer.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.install_copied, Toast.LENGTH_SHORT).show();
    }

    private static String stripAnsi(String text) {
        if (text == null) return "";
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    private void startInstallation() {
        // If an install is already running in background (e.g. auto-install),
        // attach to it instead of starting a new one.
        if (HermesInstallHelper.isInstallRunning()) {
            showOngoingInstall();
            return;
        }

        mRetryButton.setVisibility(View.GONE);
        mProgressBar.setProgress(0);
        mTerminalBuffer.setLength(0);
        mHandler.post(() -> mTerminalOutput.setText(""));

        appendTerminal("$ Starting Hermes installation...\n", false);
        appendTerminal("", false);
        updateStepIndicators(1);
        mStatusText.setText(R.string.install_checking);

        new Thread(() -> {
            try {
                // Step 0: Ensure Termux bootstrap is extracted before anything else.
                // This activity may be opened before TermuxActivity has finished
                // bootstrap extraction (e.g. from config or profile screen).
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                if (!new File(bashPath).exists()) {
                    appendTerminal("Bootstrap not found, triggering extraction...");
                    final boolean[] bootstrapDone = {false};
                    runOnUiThread(() -> {
                        TermuxInstaller.setupBootstrapIfNeeded(HermesInstallActivity.this, () -> {
                            synchronized (bootstrapDone) {
                                bootstrapDone[0] = true;
                                bootstrapDone.notifyAll();
                            }
                        });
                    });
                    synchronized (bootstrapDone) {
                        while (!bootstrapDone[0]) {
                            bootstrapDone.wait();
                        }
                    }
                    if (!new File(bashPath).exists()) {
                        throw new RuntimeException("Bootstrap extraction completed but bash still missing");
                    }
                    appendTerminal("Bootstrap extraction complete");
                }

                // Step 1: Check prerequisites
                mHandler.post(() -> {
                    mStatusText.setText(R.string.install_checking);
                    mProgressBar.setProgress(10);
                });

                // Step 2: Download and install
                mHandler.post(() -> {
                    updateStepIndicators(2);
                    mStatusText.setText(R.string.install_downloading);
                    mProgressBar.setProgress(30);
                });

                HermesInstallHelper.executeInstall(HermesInstallActivity.this, new HermesInstallHelper.ProgressCallback() {
                    @Override
                    public void onStatus(String message) {
                        mHandler.post(() -> mStatusText.setText(message));
                    }

                    @Override
                    public void onOutput(String line) {
                        appendTerminal(line);
                    }

                    @Override
                    public boolean isCancelled() {
                        return isFinishing() || isDestroyed();
                    }
                }, () -> {
                    appendTerminal("Deploying apt/dpkg configurations...");
                    HermesInstaller.deployInstallPrerequisites(HermesInstallActivity.this);
                    appendTerminal("Configuration deployed OK");
                });

                // Step 3: Validate before marking installed
                mHandler.post(() -> {
                    updateStepIndicators(3);
                    mStatusText.setText(R.string.install_validating);
                    mProgressBar.setProgress(80);
                });

                boolean validated = validateInstallation();
                if (!validated) {
                    throw new RuntimeException("Hermes binary validation failed — hermes --help did not succeed");
                }
                appendTerminal("Hermes binary validated successfully");

                // Step 4: Mark installed and finish
                mHandler.post(() -> mProgressBar.setProgress(90));

                markInstalled();
                HermesConfigManager.reinitialize();

                mHandler.post(() -> {
                    updateStepIndicators(4);
                    mStatusText.setText(R.string.install_complete);
                    mProgressBar.setProgress(100);
                });

                appendTerminal("\nInstallation complete!");

                Thread.sleep(800);

                mSuccess = true;
                mHandler.post(this::showInstallSuccess);

            } catch (Exception e) {
                String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                appendTerminal("\nFATAL: " + error);
                mHandler.post(() -> {
                    updateStepIndicators(-1);
                    mStatusText.setText(getString(R.string.install_failed, "see log above"));
                    mRetryButton.setVisibility(View.VISIBLE);
                    mProgressBar.setProgress(0);
                });
            }
        }, "HermesInstaller").start();
    }

    /**
     * Attach to an already-running background install.
     * Shows buffered output and polls until the install completes.
     */
    private int mLastBufferPos = 0;

    private void showOngoingInstall() {
        mRetryButton.setVisibility(View.GONE);
        mProgressBar.setProgress(30);
        mTerminalBuffer.setLength(0);
        mLastBufferPos = 0;

        appendTerminal("$ Installation already in progress, attaching...\n", false);
        appendTerminal("", false);
        updateStepIndicators(2);
        mStatusText.setText(R.string.install_downloading);

        // Load output accumulated so far
        String buffered = HermesInstallHelper.getOutputBuffer();
        if (!buffered.isEmpty()) {
            mTerminalBuffer.append(stripAnsi(buffered));
            mLastBufferPos = buffered.length();
            mHandler.post(() -> {
                mTerminalOutput.setText(mTerminalBuffer.toString());
                mTerminalScroll.post(() -> mTerminalScroll.fullScroll(View.FOCUS_DOWN));
            });
        }

        // Poll until install finishes
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;

                // Append any new output using length-based offset
                String current = HermesInstallHelper.getOutputBuffer();
                int pos = Math.min(mLastBufferPos, current.length());
                if (current.length() > pos) {
                    String newOutput = current.substring(pos);
                    mTerminalBuffer.append(stripAnsi(newOutput));
                    mLastBufferPos = current.length();
                    mHandler.post(() -> {
                        mTerminalOutput.setText(mTerminalBuffer.toString());
                        mTerminalScroll.post(() -> mTerminalScroll.fullScroll(View.FOCUS_DOWN));
                    });
                }

                if (!HermesInstallHelper.isInstallRunning()) {
                    // Install finished — run validation in background
                    new Thread(() -> {
                        HermesInstallHelper.InstallState state = HermesInstallHelper.getState(HermesInstallActivity.this);
                        if (state == HermesInstallHelper.InstallState.INSTALLED) {
                            mHandler.post(() -> {
                                updateStepIndicators(3);
                                mStatusText.setText(R.string.install_validating);
                                mProgressBar.setProgress(80);
                            });
                            boolean validated = validateInstallation();
                            if (validated) {
                                appendTerminal("Hermes binary validated successfully");
                                mSuccess = true;
                                mHandler.post(() -> {
                                    updateStepIndicators(4);
                                    mStatusText.setText(R.string.install_complete);
                                    mProgressBar.setProgress(100);
                                    showInstallSuccess();
                                });
                            } else {
                                mHandler.post(() -> {
                                    updateStepIndicators(-1);
                                    mStatusText.setText(getString(R.string.install_failed, "validation failed"));
                                    mRetryButton.setVisibility(View.VISIBLE);
                                    mProgressBar.setProgress(0);
                                });
                            }
                        } else {
                            String error = HermesInstallHelper.getLastError(HermesInstallActivity.this);
                            appendTerminal("\nFATAL: " + (error.isEmpty() ? "Install failed" : error));
                            mHandler.post(() -> {
                                updateStepIndicators(-1);
                                mStatusText.setText(getString(R.string.install_failed, "see log above"));
                                mRetryButton.setVisibility(View.VISIBLE);
                                mProgressBar.setProgress(0);
                            });
                        }
                    }, "HermesValidator").start();
                    return;
                }
                mHandler.postDelayed(this, 2000);
            }
        };
        mHandler.postDelayed(poll, 2000);
    }

    private void updateStepIndicators(int currentStep) {
        mStepContainer.removeAllViews();

        String[] steps = {
                getString(R.string.install_step_check),
                getString(R.string.install_step_download),
                getString(R.string.install_step_configure),
                getString(R.string.install_step_done)
        };

        for (int i = 0; i < steps.length; i++) {
            TextView stepTv = new TextView(this);
            stepTv.setTextSize(13);

            String prefix;
            int color;
            if (currentStep == -1) {
                prefix = "";
                color = 0xFF999999;
            } else if (i < currentStep - 1) {
                prefix = "✓ ";
                color = 0xFF4CAF50;
            } else if (i == currentStep - 1) {
                prefix = "● ";
                color = 0xFF2196F3;
            } else {
                prefix = "○ ";
                color = 0xFF999999;
            }

            stepTv.setText(prefix + steps[i]);
            stepTv.setTextColor(color);

            mStepContainer.addView(stepTv);

            if (i < steps.length - 1) {
                TextView arrow = new TextView(this);
                arrow.setText("  ›  ");
                arrow.setTextSize(13);
                arrow.setTextColor(0xFF666666);
                mStepContainer.addView(arrow);
            }
        }
    }

    private void markInstalled() throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
    }

    private boolean validateInstallation() {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            File hermesBin = new File(binPath, "hermes");
            if (!hermesBin.exists()) {
                appendTerminal("Validation: hermes binary not found at " + hermesBin.getAbsolutePath());
                return false;
            }
            File configDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes");
            if (!configDir.exists() || !configDir.isDirectory()) {
                appendTerminal("Validation: .hermes config directory not found");
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
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                appendTerminal("Validation: hermes --help exited with code " + exit);
                return false;
            }
            if (output.length() == 0) {
                appendTerminal("Validation: hermes --help produced no output");
                return false;
            }
            return true;
        } catch (Exception e) {
            appendTerminal("Validation exception: " + e.getMessage());
            return false;
        }
    }

    private void showAlreadyInstalled() {
        updateStepIndicators(4);
        mStatusText.setText(R.string.install_state_installed);
        mStatusText.setTextColor(0xFF388E3C);
        mProgressBar.setProgress(100);
        mSkipButton.setVisibility(View.GONE);
        mRetryButton.setText(R.string.install_action_reinstall);
        mRetryButton.setVisibility(View.VISIBLE);
        mRetryButton.setOnClickListener(v -> {
            mSuccess = false;
            if (HermesGatewayService.isRunning()) {
                Intent stopIntent = new Intent(this, HermesGatewayService.class);
                stopIntent.setAction(HermesGatewayService.ACTION_STOP);
                startService(stopIntent);
            }
            HermesInstallHelper.resetInstall(this);
            mSkipButton.setVisibility(View.VISIBLE);
            mRetryButton.setText(R.string.install_retry);
            mRetryButton.setVisibility(View.GONE);
            startInstallation();
        });
        mTerminalBuffer.setLength(0);
        mTerminalBuffer.append("Hermes Agent is already installed.\n");
        mTerminalOutput.setText(mTerminalBuffer.toString());
    }

    private void showInstallSuccess() {
        mSkipButton.setVisibility(View.GONE);
        mRetryButton.setText(R.string.install_action_reinstall);
        mRetryButton.setVisibility(View.VISIBLE);
        mRetryButton.setOnClickListener(v -> {
            mSuccess = false;
            if (HermesGatewayService.isRunning()) {
                Intent stopIntent = new Intent(this, HermesGatewayService.class);
                stopIntent.setAction(HermesGatewayService.ACTION_STOP);
                startService(stopIntent);
            }
            HermesInstallHelper.resetInstall(this);
            mSkipButton.setVisibility(View.VISIBLE);
            mRetryButton.setText(R.string.install_retry);
            mRetryButton.setVisibility(View.GONE);
            startInstallation();
        });
    }

    private void proceedToNext() {
        if (!HermesSetupWizardActivity.isWizardCompleted(this)) {
            startActivity(new Intent(this, HermesSetupWizardActivity.class));
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
