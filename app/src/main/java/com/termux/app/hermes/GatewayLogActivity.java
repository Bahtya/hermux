package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.FileObserver;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import androidx.core.content.ContextCompat;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GatewayLogActivity extends AppCompatActivity {

    private static final String LOG_FILE_PATH =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/logs/gateway.log";
    private static final int MAX_LINES = 500;

    private TextView mLogText;
    private ScrollView mScrollView;
    private LinearLayout mSessionList;
    private TextView mCurrentSessionText;
    private Runnable mUptimeUpdater;
    private FileObserver mFileObserver;
    private Spinner mFilterSpinner;
    private String mCurrentFilter = "ALL";
    private String mFullLogContent = "";
    private boolean mAutoScroll = true;
    private String mCurrentTab = "gateway";
    private TextView mTabGateway;
    private TextView mTabSsh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setTitle(R.string.gateway_log_title);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setBackgroundColor(0xFF1A1A2E);
        int toolbarHeight = (int) (56 * getResources().getDisplayMetrics().density);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight));
        layout.addView(toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // --- Session History Section ---
        TextView sessionHeader = new TextView(this);
        sessionHeader.setText(R.string.gateway_session_history_title);
        sessionHeader.setTypeface(null, Typeface.BOLD);
        sessionHeader.setPadding(dp(12), dp(12), dp(12), dp(4));
        sessionHeader.setTextSize(16);
        layout.addView(sessionHeader);

        mCurrentSessionText = new TextView(this);
        mCurrentSessionText.setPadding(dp(16), dp(4), dp(16), dp(4));
        mCurrentSessionText.setTextSize(13);
        layout.addView(mCurrentSessionText);

        mSessionList = new LinearLayout(this);
        mSessionList.setOrientation(LinearLayout.VERTICAL);
        mSessionList.setPadding(dp(16), dp(0), dp(16), dp(4));
        layout.addView(mSessionList);

        TextView clearHistoryBtn = new TextView(this);
        clearHistoryBtn.setText(R.string.gateway_session_clear_title);
        clearHistoryBtn.setPadding(dp(16), dp(4), dp(16), dp(8));
        clearHistoryBtn.setOnClickListener(v -> showClearHistoryConfirm());
        layout.addView(clearHistoryBtn);

        View separator = new View(this);
        separator.setBackgroundColor(ContextCompat.getColor(this, R.color.hermes_separator));
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sepParams.setMargins(dp(12), dp(4), dp(12), dp(4));
        separator.setLayoutParams(sepParams);
        layout.addView(separator);

        // --- Tab switcher: Gateway | SSH ---
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(12), dp(4), dp(12), dp(0));

        int tabPadV = dp(6);
        int tabPadH = dp(16);
        int tabActiveBg = 0xFF1A1A2E;
        int tabInactiveBg = 0xFFEEEEEE;
        int tabActiveText = 0xFFFFFFFF;
        int tabInactiveText = 0xFF666666;

        mTabGateway = new TextView(this);
        mTabGateway.setText("Gateway");
        mTabGateway.setTextSize(13);
        mTabGateway.setTypeface(null, Typeface.BOLD);
        mTabGateway.setPadding(tabPadH, tabPadV, tabPadH, tabPadV);
        mTabGateway.setTextColor(tabActiveText);
        mTabGateway.setBackgroundColor(tabActiveBg);
        mTabGateway.setOnClickListener(v -> {
            if ("gateway".equals(mCurrentTab)) return;
            mCurrentTab = "gateway";
            mTabGateway.setTextColor(tabActiveText);
            mTabGateway.setBackgroundColor(tabActiveBg);
            mTabSsh.setTextColor(tabInactiveText);
            mTabSsh.setBackgroundColor(tabInactiveBg);
            loadLog();
            loadSessionHistory();
            startFileObserver();
        });

        mTabSsh = new TextView(this);
        mTabSsh.setText("SSH");
        mTabSsh.setTextSize(13);
        mTabSsh.setTypeface(null, Typeface.BOLD);
        mTabSsh.setPadding(tabPadH, tabPadV, tabPadH, tabPadV);
        mTabSsh.setTextColor(tabInactiveText);
        mTabSsh.setBackgroundColor(tabInactiveBg);
        mTabSsh.setOnClickListener(v -> {
            if ("ssh".equals(mCurrentTab)) return;
            mCurrentTab = "ssh";
            mTabSsh.setTextColor(tabActiveText);
            mTabSsh.setBackgroundColor(tabActiveBg);
            mTabGateway.setTextColor(tabInactiveText);
            mTabGateway.setBackgroundColor(tabInactiveBg);
            loadLog();
            stopFileObserver();
        });

        tabBar.addView(mTabGateway);
        tabBar.addView(mTabSsh);
        layout.addView(tabBar);

        // --- Filter bar ---
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        filterBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView filterLabel = new TextView(this);
        filterLabel.setText(R.string.gateway_log_filter_label);
        filterLabel.setPadding(dp(4), dp(0), dp(8), dp(0));
        filterBar.addView(filterLabel);

        mFilterSpinner = new Spinner(this);
        String[] filterOptions = {"ALL", "ERROR", "WARN", "INFO", "DEBUG"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, filterOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFilterSpinner.setAdapter(adapter);
        mFilterSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                mCurrentFilter = filterOptions[pos];
                applyFilter();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        filterBar.addView(mFilterSpinner);
        layout.addView(filterBar);

        // --- Log Section ---
        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (v instanceof ScrollView) {
                ScrollView sv = (ScrollView) v;
                View child = sv.getChildAt(sv.getChildCount() - 1);
                if (child != null) {
                    int diff = child.getBottom() - (sv.getHeight() + scrollY);
                    mAutoScroll = diff < dp(50);
                }
            }
        });

        mLogText = new TextView(this);
        mLogText.setPadding(dp(12), dp(12), dp(12), dp(12));
        mLogText.setTextSize(11);
        mLogText.setTypeface(Typeface.MONOSPACE);
        mLogText.setTextIsSelectable(true);

        mScrollView.addView(mLogText);
        layout.addView(mScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText(R.string.gateway_log_refresh);
        refreshBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        refreshBtn.setOnClickListener(v -> {
            loadLog();
            loadSessionHistory();
        });
        buttonBar.addView(refreshBtn);

        TextView clearBtn = new TextView(this);
        clearBtn.setText(R.string.gateway_log_clear);
        clearBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        clearBtn.setOnClickListener(v -> showClearConfirm());
        buttonBar.addView(clearBtn);

        TextView copyBtn = new TextView(this);
        copyBtn.setText(R.string.gateway_log_copy);
        copyBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        copyBtn.setOnClickListener(v -> copyLog());
        buttonBar.addView(copyBtn);

        TextView shareBtn = new TextView(this);
        shareBtn.setText(R.string.gateway_log_share);
        shareBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        shareBtn.setOnClickListener(v -> shareLog());
        buttonBar.addView(shareBtn);

        layout.addView(buttonBar);
        setContentView(layout);

        loadLog();
        loadSessionHistory();
        startFileObserver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessionHistory();
        startUptimeUpdates();
        startFileObserver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUptimeUpdates();
        stopFileObserver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFileObserver();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================================
    // File observer for real-time updates
    // =========================================================================

    private void startFileObserver() {
        stopFileObserver();
        File logFile = new File(LOG_FILE_PATH);
        File logDir = logFile.getParentFile();
        if (logDir != null && logDir.exists()) {
            int mask = FileObserver.MODIFY | FileObserver.CREATE | FileObserver.CLOSE_WRITE;
            mFileObserver = new FileObserver(logDir, mask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (path != null && path.equals(logFile.getName())) {
                        runOnUiThread(GatewayLogActivity.this::loadLog);
                    }
                }
            };
            mFileObserver.startWatching();
        }
    }

    private void stopFileObserver() {
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
            mFileObserver = null;
        }
    }

    // =========================================================================
    // Session history
    // =========================================================================

    private void loadSessionHistory() {
        HermesConfigManager config = HermesConfigManager.getInstance();
        List<HermesConfigManager.Session> sessions = config.getSessionHistory(this);

        updateCurrentSession();

        mSessionList.removeAllViews();
        if (sessions.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText(R.string.gateway_session_empty);
            emptyText.setTextSize(12);
            emptyText.setPadding(0, dp(4), 0, dp(4));
            mSessionList.addView(emptyText);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        for (int i = sessions.size() - 1; i >= 0; i--) {
            HermesConfigManager.Session session = sessions.get(i);
            TextView sessionView = new TextView(this);
            sessionView.setTextSize(12);
            sessionView.setPadding(0, dp(2), 0, dp(2));

            String startStr = sdf.format(new Date(session.startTime));
            String stopStr = session.stopTime > 0
                    ? sdf.format(new Date(session.stopTime))
                    : getString(R.string.gateway_session_running);
            String durationStr = HermesGatewayService.formatDuration(session.duration);

            sessionView.setText(
                    getString(R.string.gateway_session_start, startStr) + "\n"
                            + getString(R.string.gateway_session_duration, durationStr));
            mSessionList.addView(sessionView);
        }
    }

    private void updateCurrentSession() {
        if (HermesGatewayService.isRunning()) {
            String uptime = HermesGatewayService.getFormattedUptime();
            mCurrentSessionText.setText(
                    getString(R.string.gateway_session_current) + ": "
                            + getString(R.string.gateway_session_running)
                            + " (" + uptime + ")");
            mCurrentSessionText.setVisibility(View.VISIBLE);
        } else {
            mCurrentSessionText.setVisibility(View.GONE);
        }
    }

    private void startUptimeUpdates() {
        stopUptimeUpdates();
        mUptimeUpdater = new Runnable() {
            @Override
            public void run() {
                if (HermesGatewayService.isRunning()) {
                    updateCurrentSession();
                }
                if (!isFinishing()) {
                    getWindow().getDecorView().postDelayed(this, 1000);
                }
            }
        };
        getWindow().getDecorView().postDelayed(mUptimeUpdater, 1000);
    }

    private void stopUptimeUpdates() {
        if (mUptimeUpdater != null) {
            getWindow().getDecorView().removeCallbacks(mUptimeUpdater);
            mUptimeUpdater = null;
        }
    }

    private void showClearHistoryConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gateway_session_clear_confirm_title)
                .setMessage(R.string.gateway_session_clear_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    HermesConfigManager.getInstance().clearSessionHistory(this);
                    loadSessionHistory();
                    Toast.makeText(this, R.string.gateway_session_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // =========================================================================
    // Log loading and filtering
    // =========================================================================

    private void loadLog() {
        if ("ssh".equals(mCurrentTab)) {
            loadSshLog();
        } else {
            loadGatewayLog();
        }
    }

    private void loadGatewayLog() {
        new Thread(() -> {
            mFullLogContent = readLastLines();
            runOnUiThread(() -> {
                applyFilter();
                if (mAutoScroll) {
                    mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }).start();
    }

    private void loadSshLog() {
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            try {
                ProcessBuilder pb = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(MAX_LINES),
                        "-s", "sshd:*", "Sshd:*", "HermesInstaller:*", "GatewayControl:*");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.append(line).append("\n");
                    }
                    p.waitFor();
                } finally {
                    p.destroy();
                }
            } catch (Exception e) {
                log.append(getString(R.string.ssh_log_read_error, e.getMessage()));
            }
            mFullLogContent = log.toString();
            runOnUiThread(() -> {
                applyFilter();
                if (mAutoScroll) {
                    mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }).start();
    }

    private void applyFilter() {
        if (mFullLogContent.isEmpty()) {
            if ("ssh".equals(mCurrentTab)) {
                mLogText.setText(getString(R.string.ssh_log_empty));
            } else if (!HermesGatewayService.isRunning()) {
                mLogText.setText(getString(R.string.gateway_log_not_running_hint));
            } else {
                mLogText.setText(getString(R.string.gateway_log_empty));
            }
            return;
        }

        if ("ALL".equals(mCurrentFilter)) {
            mLogText.setText(mFullLogContent);
            return;
        }

        StringBuilder filtered = new StringBuilder();
        for (String line : mFullLogContent.split("\n")) {
            String upper = line.toUpperCase();
            if (upper.contains(" " + mCurrentFilter + " ") || upper.contains(" " + mCurrentFilter + ":") || upper.contains("[" + mCurrentFilter + "]")) {
                filtered.append(line).append("\n");
            }
        }

        if (filtered.length() == 0) {
            mLogText.setText(getString(R.string.gateway_log_no_matches));
        } else {
            mLogText.setText(filtered.toString());
        }
    }

    private String readLastLines() {
        File file = new File(LOG_FILE_PATH);
        if (!file.exists()) return "";

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            return "Error reading log: " + e.getMessage();
        }

        int start = Math.max(0, lines.size() - MAX_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private void showClearConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gateway_log_clear_confirm_title)
                .setMessage(R.string.gateway_log_clear_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> clearLog())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearLog() {
        if ("ssh".equals(mCurrentTab)) {
            loadSshLog();
            Toast.makeText(this, R.string.gateway_log_refresh, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            File file = new File(LOG_FILE_PATH);
            if (file.exists()) {
                try (FileWriter writer = new FileWriter(file, false)) {
                    writer.write("");
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Failed to clear log: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return;
                }
            }
            runOnUiThread(() -> {
                mLogText.setText(getString(R.string.gateway_log_empty));
                Toast.makeText(this, R.string.gateway_log_cleared, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                String label = "ssh".equals(mCurrentTab) ? "SSH Log" : "Gateway Log";
                ClipData clip = ClipData.newPlainText(label, mLogText.getText());
                cm.setPrimaryClip(clip);
                Toast.makeText(this, R.string.gateway_log_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.copy_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLog() {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, mLogText.getText());
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Hermes Gateway Log");
            startActivity(Intent.createChooser(shareIntent, getString(R.string.gateway_log_share_title)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.share_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
