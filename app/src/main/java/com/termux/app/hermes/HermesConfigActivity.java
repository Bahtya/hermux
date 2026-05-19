package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.navigation.NavigationView;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.hermes.update.AppUpdateChecker;
import com.termux.app.hermes.update.AppUpdateConfig;
import com.termux.app.hermes.update.AppUpdateDialog;
import com.termux.app.hermes.update.AppUpdateInfo;
import com.termux.app.hermes.update.AppUpdateService;
import com.termux.shared.termux.TermuxConstants;

import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

public class HermesConfigActivity extends AppCompatActivity {

    public static final String EXTRA_NAV_SECTION = "nav_section";

    private DrawerLayout mDrawerLayout;
    private NavigationView mNavigationView;
    private HermesConfigManager mConfigManager;
    private boolean mDashboardVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_config);
        mConfigManager = HermesConfigManager.getInstance();

        mDrawerLayout = findViewById(R.id.hermes_drawer_layout);
        mNavigationView = findViewById(R.id.hermes_nav_view);

        setSupportActionBar(findViewById(R.id.hermes_config_toolbar));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setTitle(R.string.hermes_config_title);
        }

        // Drawer toggle via toolbar nav icon
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.hermes_config_toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                mDrawerLayout.closeDrawer(GravityCompat.START);
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
            }
        });

        // Handle navigation item clicks
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            handleNavigation(id);
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Update drawer header status
        updateDrawerHeader();

        // Handle incoming navigation request (e.g. from ProfileFragment quick actions)
        if (savedInstanceState == null) {
            int navSection = getIntent().getIntExtra(EXTRA_NAV_SECTION, -1);
            if (navSection != -1) {
                handleNavigation(navSection);
            } else {
                showDashboard();
            }
        }
    }

    private void handleNavigation(int itemId) {
        if (itemId == R.id.nav_dashboard) {
            showDashboard();
        } else if (itemId == R.id.nav_install) {
            startActivity(new Intent(this, HermesInstallActivity.class));
        } else if (itemId == R.id.nav_ai_config) {
            showFragment(new LlmConfigFragment());
        } else if (itemId == R.id.nav_im_setup) {
            showImSetupPage();
        } else if (itemId == R.id.nav_gateway) {
            showFragment(new GatewayControlFragment());
        } else if (itemId == R.id.nav_logs) {
            startActivity(new Intent(this, GatewayLogActivity.class));
        } else if (itemId == R.id.nav_diagnostics) {
            startActivity(new Intent(this, HermesDiagnosticActivity.class));
        } else if (itemId == R.id.nav_setup_wizard) {
            startActivity(new Intent(this, HermesSetupWizardActivity.class));
        } else if (itemId == R.id.nav_help) {
            startActivity(new Intent(this, HermesHelpActivity.class));
        } else if (itemId == R.id.nav_restore_backup) {
            showRestoreBackupConfirmDialog();
        }
    }

    private void showDashboard() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, pad, pad, pad);

        // Install status card
        addInstallStatusCard(layout);

        // Config status card
        HermesConfigManager.ConfigStatus status = mConfigManager.getConfigStatus();
        TextView statusTitle = new TextView(this);
        statusTitle.setText(R.string.hermes_dashboard_status_section);
        statusTitle.setTextSize(18);
        statusTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        statusTitle.setTextColor(0xFF1A1A2E);
        statusTitle.setPadding(0, 0, 0, dp(12));
        layout.addView(statusTitle);

        // Status card background
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dp(16);
        statusCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        TextView statusText = new TextView(this);
        int bgColor;
        switch (status) {
            case READY:
                statusText.setText(R.string.hermes_dashboard_config_ready);
                statusText.setTextColor(0xFF388E3C);
                bgColor = 0xFFE8F5E9;
                break;
            case PARTIAL:
                statusText.setText(R.string.hermes_dashboard_config_partial);
                statusText.setTextColor(0xFFF57C00);
                bgColor = 0xFFFFF3E0;
                break;
            default:
                statusText.setText(R.string.hermes_dashboard_config_empty);
                statusText.setTextColor(0xFFD32F2F);
                bgColor = 0xFFFFEBEE;
                break;
        }
        statusText.setTextSize(15);
        statusText.setPadding(0, 0, 0, dp(8));
        statusCard.addView(statusText);

        // Detail lines
        String provider = mConfigManager.getModelProvider();
        String apiKey = mConfigManager.getApiKey(provider);
        boolean hasLLM = !apiKey.isEmpty() || "ollama".equals(provider);

        addDetailLine(statusCard, getString(R.string.hermes_dashboard_label_ai_provider), hasLLM ? provider + " / " + mConfigManager.getModelName() : getString(R.string.hermes_dashboard_not_configured));
        addDetailLine(statusCard, getString(R.string.hermes_dashboard_label_im), getImStatusSummary());

        // Gateway status
        HermesGatewayStatus.checkAsync((gwStatus, detail) -> {
            runOnUiThread(() -> {
                String gwText;
                switch (gwStatus) {
                    case RUNNING: gwText = getString(R.string.hermes_dashboard_gw_running); break;
                    case NOT_INSTALLED: gwText = getString(R.string.hermes_dashboard_gw_not_installed); break;
                    default: gwText = getString(R.string.hermes_dashboard_gw_stopped); break;
                }
                addDetailLine(statusCard, getString(R.string.hermes_dashboard_label_gateway), gwText);
            });
        });

        statusCard.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(16);
        statusCard.setLayoutParams(cardParams);
        layout.addView(statusCard);

        // Quick actions section
        if (status != HermesConfigManager.ConfigStatus.READY) {
            Button setupBtn = new Button(this);
            setupBtn.setText(R.string.hermes_dashboard_run_setup);
            setupBtn.setOnClickListener(v -> startActivity(new Intent(this, HermesSetupWizardActivity.class)));
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.bottomMargin = dp(12);
            setupBtn.setLayoutParams(btnParams);
            layout.addView(setupBtn);
        }

        // Quick actions
        TextView actionsTitle = new TextView(this);
        actionsTitle.setText(R.string.hermes_dashboard_actions_section);
        actionsTitle.setTextSize(18);
        actionsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        actionsTitle.setTextColor(0xFF1A1A2E);
        actionsTitle.setPadding(0, dp(8), 0, dp(12));
        layout.addView(actionsTitle);

        addQuickAction(layout, R.string.hermes_action_start_gateway, "action_gateway", v -> {
            Intent startIntent = new Intent(this, HermesGatewayService.class);
            startIntent.setAction(HermesGatewayService.ACTION_START);
            startService(startIntent);
            Toast.makeText(this, R.string.gateway_started, Toast.LENGTH_SHORT).show();
        });
        addQuickAction(layout, R.string.hermes_action_ai_settings, "action_ai", v -> showFragment(new LlmConfigFragment()));
        addQuickAction(layout, R.string.hermes_action_im_setup, "action_im", v -> showImSetupPage());
        addQuickAction(layout, R.string.hermes_action_view_logs, "action_logs", v -> startActivity(new Intent(this, GatewayLogActivity.class)));
        addQuickAction(layout, R.string.hermes_action_diagnostics, "action_diag", v -> startActivity(new Intent(this, HermesDiagnosticActivity.class)));
        addQuickAction(layout, R.string.hermes_action_check_update, "action_update", v -> checkForAppUpdate());

        // Reset section
        addSpacer(layout, dp(24));
        TextView resetTitle = new TextView(this);
        resetTitle.setText(R.string.hermes_dashboard_reset_section);
        resetTitle.setTextSize(18);
        resetTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        resetTitle.setTextColor(0xFF1A1A2E);
        resetTitle.setPadding(0, 0, 0, dp(8));
        layout.addView(resetTitle);

        Button resetBtn = new Button(this);
        resetBtn.setText(R.string.hermes_dashboard_reset_all);
        resetBtn.setTextColor(0xFFD32F2F);
        LinearLayout.LayoutParams resetBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetBtn.setLayoutParams(resetBtnParams);
        resetBtn.setOnClickListener(v -> showResetAllConfirmDialog());
        layout.addView(resetBtn);

        scrollView.addView(layout);

        FrameLayout content = findViewById(R.id.hermes_config_content);
        content.removeAllViews();
        content.addView(scrollView);

        mDashboardVisible = true;

        // Check item in nav
        mNavigationView.setCheckedItem(R.id.nav_dashboard);

        // Handle update notification tap
        if (getIntent() != null && "com.hermux.SHOW_UPDATE".equals(getIntent().getAction())) {
            getIntent().setAction(null);
            checkForAppUpdate();
        }
    }

    private void checkForAppUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        AppUpdateChecker.checkForUpdate(this, new AppUpdateChecker.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(AppUpdateInfo info) {
                runOnUiThread(() ->
                    AppUpdateDialog.show(HermesConfigActivity.this, info,
                        new AppUpdateDialog.Callbacks() {
                            @Override
                            public void onUpdateNow(AppUpdateInfo info) {
                                AppUpdateService.setPendingUpdate(info);
                                Intent svc = new Intent(HermesConfigActivity.this,
                                        AppUpdateService.class);
                                svc.setAction(AppUpdateService.ACTION_DOWNLOAD);
                                startForegroundService(svc);
                            }
                            @Override
                            public void onSkip(int versionCode) {
                                AppUpdateConfig.setSkipVersionCode(
                                        HermesConfigActivity.this, versionCode);
                            }
                        }
                    )
                );
            }
            @Override
            public void onNoUpdate() {
                runOnUiThread(() ->
                    Toast.makeText(HermesConfigActivity.this,
                            R.string.update_up_to_date, Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                    Toast.makeText(HermesConfigActivity.this,
                            getString(R.string.update_error, message), Toast.LENGTH_LONG).show());
            }
        });
    }

    private android.os.Handler mInstallPollHandler;
    private Runnable mInstallPollRunnable;
    private TextView mInstallCardText;
    private android.widget.ProgressBar mInstallCardProgress;

    private void addInstallStatusCard(LinearLayout parent) {
        HermesInstallHelper.InstallState state = HermesInstallHelper.getState(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dp(16);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        TextView text = new TextView(this);
        text.setTextSize(15);
        text.setPadding(0, 0, 0, dp(4));
        mInstallCardText = text;

        int bgColor;
        boolean isInstalling = false;
        switch (state) {
            case INSTALLED:
                text.setText(R.string.install_state_installed);
                text.setTextColor(0xFF388E3C);
                bgColor = 0xFFE8F5E9;
                break;
            case BOOTSTRAPPING:
                text.setText(R.string.install_state_bootstrapping);
                text.setTextColor(0xFF1565C0);
                bgColor = 0xFFE3F2FD;
                isInstalling = true;
                break;
            case INSTALLING:
                text.setText(R.string.install_state_installing);
                text.setTextColor(0xFF1565C0);
                bgColor = 0xFFE3F2FD;
                isInstalling = true;
                break;
            case FAILED:
                text.setText(R.string.install_state_failed);
                text.setTextColor(0xFFD32F2F);
                bgColor = 0xFFFFEBEE;
                break;
            default:
                text.setText(R.string.install_state_not_installed);
                text.setTextColor(0xFFD32F2F);
                bgColor = 0xFFFFEBEE;
                break;
        }
        card.addView(text);

        // Indeterminate progress bar during installation
        mInstallCardProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mInstallCardProgress.setIndeterminate(true);
        mInstallCardProgress.setVisibility(isInstalling ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        pbParams.topMargin = dp(4);
        pbParams.bottomMargin = dp(4);
        card.addView(mInstallCardProgress, pbParams);

        // Show error details for FAILED state
        if (state == HermesInstallHelper.InstallState.FAILED) {
            String lastError = HermesInstallHelper.getLastError(this);
            if (lastError != null && !lastError.isEmpty()) {
                TextView errorText = new TextView(this);
                errorText.setText(lastError.length() > 200 ? lastError.substring(0, 200) + "..." : lastError);
                errorText.setTextSize(12);
                errorText.setTextColor(0xFF888888);
                errorText.setPadding(0, 0, 0, dp(4));
                card.addView(errorText);
            }
        }
        card.setBackgroundColor(bgColor);

        // Make card clickable to open install details
        if (state != HermesInstallHelper.InstallState.INSTALLED) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v ->
                    startActivity(new Intent(this, HermesInstallActivity.class)));
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(16);
        card.setLayoutParams(cardParams);
        parent.addView(card);

        // Poll for state changes while install is in progress
        if (isInstalling) {
            startInstallStatePolling();
        }
    }

    private void startInstallStatePolling() {
        if (mInstallPollHandler != null) mInstallPollHandler.removeCallbacks(mInstallPollRunnable);
        mInstallPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mInstallPollRunnable = new Runnable() {
            @Override
            public void run() {
                HermesInstallHelper.InstallState state = HermesInstallHelper.getState(HermesConfigActivity.this);
                if (state == HermesInstallHelper.InstallState.INSTALLED
                        || state == HermesInstallHelper.InstallState.NOT_INSTALLED
                        || state == HermesInstallHelper.InstallState.FAILED) {
                    // Terminal state — refresh dashboard
                    showDashboard();
                    return;
                }
                // Update card text in-place for intermediate states
                if (mInstallCardText != null) {
                    mInstallCardText.setText(getStateString(state));
                }
                mInstallPollHandler.postDelayed(this, 3000);
            }
        };
        mInstallPollHandler.postDelayed(mInstallPollRunnable, 3000);
    }

    private String getStateString(HermesInstallHelper.InstallState state) {
        switch (state) {
            case BOOTSTRAPPING: return getString(R.string.install_state_bootstrapping);
            case INSTALLING: return getString(R.string.install_state_installing);
            case INSTALLED: return getString(R.string.install_state_installed);
            case FAILED: return getString(R.string.install_state_failed);
            default: return getString(R.string.install_state_not_installed);
        }
    }

    private void addQuickAction(LinearLayout parent, int textResId, String tag, View.OnClickListener listener) {
        TextView actionItem = new TextView(this);
        actionItem.setText(getString(R.string.hermes_action_prefix, getString(textResId)));
        actionItem.setTextSize(15);
        actionItem.setTextColor(0xFF1565C0);
        actionItem.setPadding(0, dp(4), 0, dp(4));
        actionItem.setTag(tag);
        actionItem.setOnClickListener(listener);
        parent.addView(actionItem);
    }

    private void addDetailLine(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView labelView = new TextView(this);
        labelView.setText(label + " ");
        labelView.setTextSize(14);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        labelView.setTextColor(0xFF333333);
        row.addView(labelView);
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(0xFF666666);
        row.addView(valueView);
        parent.addView(row);
    }

    private String getImStatusSummary() {
        java.util.List<String> platforms = new java.util.ArrayList<>();
        if (mConfigManager.isFeishuConfigured()) platforms.add("Feishu");
        if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) platforms.add("Telegram");
        if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) platforms.add("Discord");
        if (!mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty()) platforms.add("WhatsApp");
        if (platforms.isEmpty()) return getString(R.string.hermes_dashboard_im_none);
        return android.text.TextUtils.join(", ", platforms);
    }

    private void showImSetupPage() {
        mDashboardVisible = false;
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.hermes_setup_im_title);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1A1A2E);
        title.setPadding(0, 0, 0, dp(16));
        layout.addView(title);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = dp(8);

        Button feishuBtn = new Button(this);
        feishuBtn.setText(R.string.hermes_setup_open_feishu_wizard);
        feishuBtn.setOnClickListener(v -> startActivity(new Intent(this, FeishuSetupActivity.class)));
        feishuBtn.setLayoutParams(btnParams);
        layout.addView(feishuBtn);

        Button telegramBtn = new Button(this);
        telegramBtn.setText(R.string.hermes_telegram_setup_title);
        telegramBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_TELEGRAM);
            startActivity(intent);
        });
        telegramBtn.setLayoutParams(btnParams);
        layout.addView(telegramBtn);

        Button discordBtn = new Button(this);
        discordBtn.setText(R.string.hermes_discord_setup_title);
        discordBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_DISCORD);
            startActivity(intent);
        });
        discordBtn.setLayoutParams(btnParams);
        layout.addView(discordBtn);

        Button whatsappBtn = new Button(this);
        whatsappBtn.setText(R.string.hermes_setup_open_whatsapp_wizard);
        whatsappBtn.setOnClickListener(v -> startActivity(new Intent(this, WhatsAppSetupActivity.class)));
        whatsappBtn.setLayoutParams(btnParams);
        layout.addView(whatsappBtn);

        scrollView.addView(layout);

        FrameLayout content = findViewById(R.id.hermes_config_content);
        content.removeAllViews();
        content.addView(scrollView);

        mNavigationView.setCheckedItem(R.id.nav_im_setup);
    }

    private void showFragment(Fragment fragment) {
        mDashboardVisible = false;
        FrameLayout content = findViewById(R.id.hermes_config_content);
        content.removeAllViews();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.hermes_config_content, fragment)
                .commit();
    }

    private void updateDrawerHeader() {
        TextView statusView = mNavigationView.getHeaderView(0).findViewById(R.id.hermes_drawer_status);
        if (statusView != null) {
            HermesGatewayStatus.checkAsync((status, detail) -> {
                runOnUiThread(() -> {
                    if (status == HermesGatewayStatus.Status.RUNNING) {
                        statusView.setText(R.string.dashboard_gateway_running);
                        statusView.setTextColor(0xFF4CAF50);
                    } else if (status == HermesGatewayStatus.Status.NOT_INSTALLED) {
                        statusView.setText(R.string.dashboard_gateway_not_installed);
                        statusView.setTextColor(0xFFFF9800);
                    } else {
                        statusView.setText(R.string.dashboard_gateway_stopped);
                        statusView.setTextColor(0xFFAAAAAA);
                    }
                });
            });
        }
    }

    private void showRestoreBackupConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hermes_nav_restore_backup)
                .setMessage(R.string.hermes_restore_backup_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    boolean restored = mConfigManager.restoreFromBackup();
                    if (restored) {
                        Toast.makeText(this, R.string.hermes_restore_success, Toast.LENGTH_SHORT).show();
                        showDashboard();
                        updateDrawerHeader();
                    } else {
                        Toast.makeText(this, R.string.hermes_restore_no_backup, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showResetAllConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hermes_reset_all_title)
                .setMessage(R.string.hermes_reset_all_message)
                .setPositiveButton(R.string.hermes_reset_all_confirm, (d, w) -> {
                    // Stop gateway if running
                    if (HermesGatewayService.isRunning()) {
                        Intent stopIntent = new Intent(this, HermesGatewayService.class);
                        stopIntent.setAction(HermesGatewayService.ACTION_STOP);
                        startService(stopIntent);
                    }
                    // Reset config files to defaults
                    mConfigManager.resetToDefaults();
                    // Clear wizard state so first-run triggers again
                    HermesSetupWizardActivity.resetStepTracking(this);
                    // Relaunch to trigger welcome flow
                    Toast.makeText(this, R.string.hermes_reset_all_done, Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(this, TermuxActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mInstallPollHandler != null) {
            mInstallPollHandler.removeCallbacks(mInstallPollRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDrawerHeader();
        if (mDashboardVisible) {
            showDashboard();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void addSpacer(LinearLayout parent, int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        parent.addView(spacer);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            mDrawerLayout.openDrawer(GravityCompat.START);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class LlmConfigFragment extends PreferenceFragmentCompat {

        private HermesConfigManager mConfigManager;
        private boolean mHasUnsavedChanges = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_llm_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            String currentProvider = mConfigManager.getModelProvider();
            updateModelList(currentProvider);
            updateProviderHints(currentProvider);
            setupQuickProviderButtons();

            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref != null) {
                String currentKey = mConfigManager.getApiKey(currentProvider);
                if (currentKey != null && !currentKey.isEmpty()) {
                    apiKeyPref.setSummary(maskApiKey(currentKey));
                }
                apiKeyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setApiKey(mConfigManager.getModelProvider(), (String) newVal);
                    p.setSummary(maskApiKey((String) newVal));
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            Preference providerPref = findPreference("llm_provider");
            if (providerPref != null) {
                providerPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String provider = (String) newVal;
                    mConfigManager.setModelProvider(provider);
                    updateModelList(provider);
                    updateProviderHints(provider);

                    String key = mConfigManager.getApiKey(provider);
                    Preference akp = findPreference("llm_api_key");
                    if (akp != null) {
                        akp.setSummary(key != null ? maskApiKey(key) : "");
                    }

                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            Preference modelPref = findPreference("llm_model");
            if (modelPref != null) {
                modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelName((String) newVal);
                    updateCostEstimate((String) newVal);
                    updateModelInfo((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
                // Show initial cost estimate and model info
                String currentModel = mConfigManager.getModelName();
                if (!currentModel.isEmpty()) {
                    updateCostEstimate(currentModel);
                    updateModelInfo(currentModel);
                }
            }

            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                baseUrlPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("OPENAI_BASE_URL", (String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Preset selection
            ListPreference presetPref = findPreference("llm_preset");
            if (presetPref != null) {
                updatePresetDescription(presetPref.getValue());
                presetPref.setOnPreferenceChangeListener((p, newVal) -> {
                    applyPreset((String) newVal);
                    updatePresetDescription((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Temperature preference
            Preference tempPref = findPreference("llm_temperature");
            if (tempPref != null) {
                tempPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelTemperature(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Max tokens preference
            Preference maxTokensPref = findPreference("llm_max_tokens");
            if (maxTokensPref != null) {
                maxTokensPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelMaxTokens(Integer.parseInt((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Top P preference
            Preference topPPref = findPreference("llm_top_p");
            if (topPPref != null) {
                topPPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelTopP(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Frequency penalty preference
            Preference freqPenaltyPref = findPreference("llm_frequency_penalty");
            if (freqPenaltyPref != null) {
                freqPenaltyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelFrequencyPenalty(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Presence penalty preference
            Preference presPenaltyPref = findPreference("llm_presence_penalty");
            if (presPenaltyPref != null) {
                presPenaltyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelPresencePenalty(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // System prompt preference
            Preference sysPromptPref = findPreference("llm_system_prompt");
            if (sysPromptPref != null) {
                sysPromptPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setSystemPrompt((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Persona template selector
            ListPreference personaPref = findPreference("llm_persona_template");
            if (personaPref != null) {
                personaPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String prompt = getPersonaPrompt((String) newVal);
                    if (prompt != null) {
                        mConfigManager.setSystemPrompt(prompt);
                        EditTextPreference sysPref = findPreference("llm_system_prompt");
                        if (sysPref != null) sysPref.setText(prompt);
                        mHasUnsavedChanges = true;
                    }
                    return true;
                });
            }

            // System prompt templates click handler
            Preference templatesPref = findPreference("llm_system_prompt_templates");
            if (templatesPref != null) {
                templatesPref.setOnPreferenceClickListener(p -> {
                    showTemplatePicker();
                    return true;
                });
            }

            // Model routing mode
            ListPreference routingModePref = findPreference("llm_routing_mode");
            if (routingModePref != null) {
                String mode = mConfigManager.getModelRoutingMode();
                routingModePref.setValue(mode);
                updateRoutingVisibility(mode);
                routingModePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelRoutingMode((String) newVal);
                    updateRoutingVisibility((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Routing fast model
            EditTextPreference fastModelPref = findPreference("llm_routing_fast_model");
            if (fastModelPref != null) {
                String fastModel = mConfigManager.getModelRoutingFastModel();
                if (!fastModel.isEmpty()) fastModelPref.setText(fastModel);
                fastModelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelRoutingFastModel((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Routing threshold
            EditTextPreference thresholdPref = findPreference("llm_routing_threshold");
            if (thresholdPref != null) {
                float threshold = mConfigManager.getModelRoutingThreshold();
                thresholdPref.setText(String.valueOf(threshold));
                thresholdPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelRoutingThreshold(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            requireActivity().getOnBackPressedDispatcher().addCallback(
                    getViewLifecycleOwner(),
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            if (mHasUnsavedChanges) {
                                showUnsavedChangesDialog();
                            } else {
                                setEnabled(false);
                                requireActivity().onBackPressed();
                            }
                        }
                    });
        }

        private void showUnsavedChangesDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.config_unsaved_title)
                    .setMessage(R.string.config_unsaved_message)
                    .setPositiveButton(R.string.config_unsaved_save, (d, w) -> {
                        mHasUnsavedChanges = false;
                        Toast.makeText(requireContext(), R.string.config_changes_saved,
                                Toast.LENGTH_SHORT).show();
                        requireActivity().getSupportFragmentManager().popBackStack();
                    })
                    .setNegativeButton(R.string.config_unsaved_discard, (d, w) -> {
                        mHasUnsavedChanges = false;
                        reloadConfig();
                        requireActivity().getSupportFragmentManager().popBackStack();
                    })
                    .setNeutralButton(R.string.config_unsaved_cancel, null)
                    .show();
        }

        private void reloadConfig() {
            HermesConfigManager.reinitialize();
        }

        private void setupQuickProviderButtons() {
            String[][] providers = {
                {"llm_quick_openai",      "openai",     "gpt-4o"},
                {"llm_quick_anthropic",   "anthropic",  "claude-sonnet-4-6"},
                {"llm_quick_deepseek",    "deepseek",   "deepseek-chat"},
                {"llm_quick_google",      "google",     "gemini-2.5-flash"},
                {"llm_quick_ollama",      "ollama",     "llama3"},
                {"llm_quick_openrouter",  "openrouter", "anthropic/claude-sonnet-4-6"},
            };
            for (String[] entry : providers) {
                Preference pref = findPreference(entry[0]);
                if (pref == null) continue;
                String provider = entry[1];
                String model = entry[2];
                pref.setOnPreferenceClickListener(p -> {
                    mConfigManager.setModelProvider(provider);
                    mConfigManager.setModelName(model);
                    if ("ollama".equals(provider)) {
                        mConfigManager.setEnvVar("OPENAI_BASE_URL", "http://localhost:11434/v1");
                    }
                    ListPreference providerList = findPreference("llm_provider");
                    if (providerList != null) providerList.setValue(provider);
                    updateModelList(provider);
                    updateProviderHints(provider);
                    updateCostEstimate(model);
                    updateModelInfo(model);
                    String key = mConfigManager.getApiKey(provider);
                    Preference akp = findPreference("llm_api_key");
                    if (akp != null) akp.setSummary(key != null && !key.isEmpty() ? maskApiKey(key) : "");
                    EditTextPreference baseUrlPref = findPreference("llm_base_url");
                    if (baseUrlPref != null && "ollama".equals(provider)) {
                        baseUrlPref.setText("http://localhost:11434/v1");
                        baseUrlPref.setVisible(true);
                    }
                    mHasUnsavedChanges = true;
                    if (getActivity() != null) {
                        Toast.makeText(getActivity(), getString(R.string.llm_provider_switched, provider), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }
        }

        private void updateProviderHints(String provider) {
            // Update API key hint
            Preference apiKeyHint = findPreference("llm_api_key_hint");
            if (apiKeyHint != null) {
                apiKeyHint.setSummary(getApiKeyHint(provider));
            }

            // Update base URL hint
            Preference baseUrlHint = findPreference("llm_base_url_hint");
            if (baseUrlHint != null) {
                baseUrlHint.setSummary(getBaseUrlHint(provider));
            }

            // Update provider info
            Preference providerInfo = findPreference("llm_provider_info");
            if (providerInfo != null) {
                providerInfo.setSummary(getProviderInfo(provider));
            }

            // Update cost estimate
            Preference costPref = findPreference("llm_cost_estimate");
            if (costPref != null) {
                costPref.setSummary(getCostEstimate(provider));
            }

            // Show/hide base URL based on provider
            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                boolean needsUrl = "ollama".equals(provider) || "custom".equals(provider);
                baseUrlPref.setVisible(needsUrl);
            }

            // Show/hide custom model input
            Preference customModel = findPreference("llm_custom_model");
            if (customModel != null) {
                customModel.setVisible("custom".equals(provider));
            }
        }

        private String getApiKeyHint(String provider) {
            switch (provider) {
                case "openai":     return "OpenAI keys start with sk-";
                case "anthropic":  return "Anthropic keys start with sk-ant-";
                case "google":     return "Get your key from Google AI Studio";
                case "deepseek":   return "DeepSeek keys from platform.deepseek.com";
                case "openrouter": return "OpenRouter keys from openrouter.ai/keys";
                case "xai":        return "xAI keys from console.x.ai";
                case "alibaba":    return "DashScope keys from Aliyun console";
                case "mistral":    return "Mistral keys from console.mistral.ai";
                case "nvidia":     return "NVIDIA API key from build.nvidia.com";
                case "ollama":     return "No API key needed for local Ollama";
                default:           return "Enter your API key";
            }
        }

        private String getBaseUrlHint(String provider) {
            switch (provider) {
                case "openai":     return "https://api.openai.com/v1";
                case "anthropic":  return "https://api.anthropic.com/v1";
                case "google":     return "https://generativelanguage.googleapis.com/v1beta";
                case "deepseek":   return "https://api.deepseek.com/v1";
                case "openrouter": return "https://openrouter.ai/api/v1";
                case "xai":        return "https://api.x.ai/v1";
                case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1";
                case "mistral":    return "https://api.mistral.ai/v1";
                case "nvidia":     return "https://integrate.api.nvidia.com/v1";
                case "ollama":     return "http://localhost:11434/v1";
                default:           return "Leave empty for default provider URL";
            }
        }

        private String getProviderInfo(String provider) {
            switch (provider) {
                case "openai":     return "Recommended: gpt-4o (best quality) or gpt-4o-mini (fast & cheap)";
                case "anthropic":  return "Recommended: claude-sonnet-4-6 (balanced) or claude-haiku-4-5 (fast)";
                case "google":     return "Recommended: gemini-2.5-flash (fast) or gemini-2.5-pro (advanced)";
                case "deepseek":   return "Recommended: deepseek-chat (general) or deepseek-reasoner (math/code)";
                case "openrouter": return "Recommended: anthropic/claude-sonnet-4-6 or openai/gpt-4o";
                case "xai":        return "Recommended: grok-3 (general) or grok-3-mini (fast)";
                case "alibaba":    return "Recommended: qwen-max (best) or qwen-plus (balanced)";
                case "mistral":    return "Recommended: mistral-large-latest or codestral-latest (code)";
                case "nvidia":     return "Recommended: meta/llama-3.3-70b-instruct or deepseek-ai/deepseek-r1";
                case "ollama":     return "Run models locally. Try llama3, mistral, or codellama";
                default:           return "Enter any OpenAI-compatible model name in the Model field above.";
            }
        }

        private String getCostEstimate(String provider) {
            switch (provider) {
                case "openai":     return "~$2.50/1M input tokens (gpt-4o)";
                case "anthropic":  return "~$3.00/1M input tokens (claude-sonnet-4-6)";
                case "google":     return "~$1.25/1M input tokens (gemini-2.5-flash)";
                case "deepseek":   return "~$0.27/1M input tokens (deepseek-chat)";
                case "openrouter": return "Varies by model. Check openrouter.ai";
                case "xai":        return "~$3.00/1M input tokens (grok-3)";
                case "alibaba":    return "~$0.40/1M input tokens (qwen-max)";
                case "mistral":    return "~$2.00/1M input tokens (mistral-large)";
                case "nvidia":     return "Free tier available. Pay-per-use.";
                case "ollama":     return "Free (runs on your device)";
                default:           return "Depends on your provider pricing.";
            }
        }

        private void updateModelList(String provider) {
            ListPreference modelPref = findPreference("llm_model");
            if (modelPref == null) return;

            int arrayResId = getModelArrayResId(provider);
            if (arrayResId != 0) {
                CharSequence[] models = getResources().getTextArray(arrayResId);
                modelPref.setEntries(models);
                modelPref.setEntryValues(models);
                if (models.length > 0) {
                    modelPref.setValue(models[0].toString());
                    mConfigManager.setModelName(models[0].toString());
                }
            }
        }

        private int getModelArrayResId(String provider) {
            switch (provider) {
                case "openai": return R.array.llm_models_openai;
                case "anthropic": return R.array.llm_models_anthropic;
                case "google": return R.array.llm_models_google;
                case "deepseek": return R.array.llm_models_deepseek;
                case "openrouter": return R.array.llm_models_openrouter;
                case "xai": return R.array.llm_models_xai;
                case "alibaba": return R.array.llm_models_alibaba;
                case "mistral": return R.array.llm_models_mistral;
                case "nvidia": return R.array.llm_models_nvidia;
                case "ollama": return R.array.llm_models_ollama;
                default: return 0;
            }
        }

        private void applyPreset(String preset) {
            float temperature;
            int maxTokens;
            float topP;
            String systemPrompt;

            switch (preset) {
                case "creative":
                    temperature = 0.9f;
                    maxTokens = 2048;
                    topP = 0.95f;
                    systemPrompt = "You are a creative and imaginative AI assistant. Think outside the box, offer unique perspectives, and express ideas vividly.";
                    break;
                case "precise":
                    temperature = 0.2f;
                    maxTokens = 4096;
                    topP = 0.8f;
                    systemPrompt = "You are a precise and analytical AI assistant. Provide accurate, well-structured answers. Be concise and factual.";
                    break;
                case "code":
                    temperature = 0.2f;
                    maxTokens = 4096;
                    topP = 0.9f;
                    systemPrompt = "You are an expert software engineer. Write clean, efficient code. Explain your reasoning. Follow best practices and design patterns.";
                    break;
                case "balanced":
                default:
                    temperature = 0.7f;
                    maxTokens = 2048;
                    topP = 1.0f;
                    systemPrompt = "You are a helpful AI assistant. Be friendly, clear, and thorough in your responses.";
                    break;
            }

            mConfigManager.setModelTemperature(temperature);
            mConfigManager.setModelMaxTokens(maxTokens);
            mConfigManager.setModelTopP(topP);
            mConfigManager.setSystemPrompt(systemPrompt);

            // Update UI fields
            androidx.preference.EditTextPreference tempPref = findPreference("llm_temperature");
            if (tempPref != null) {
                tempPref.setText(String.valueOf(temperature));
            }
            androidx.preference.EditTextPreference maxTokPref = findPreference("llm_max_tokens");
            if (maxTokPref != null) {
                maxTokPref.setText(String.valueOf(maxTokens));
            }
            androidx.preference.EditTextPreference topPPref = findPreference("llm_top_p");
            if (topPPref != null) {
                topPPref.setText(String.valueOf(topP));
            }
            androidx.preference.EditTextPreference sysPref = findPreference("llm_system_prompt");
            if (sysPref != null) {
                sysPref.setText(systemPrompt);
            }
        }

        private void updatePresetDescription(String preset) {
            Preference presetInfo = findPreference("llm_preset_info");
            if (presetInfo == null) return;
            int descResId;
            switch (preset != null ? preset : "balanced") {
                case "creative": descResId = R.string.llm_preset_desc_creative; break;
                case "precise": descResId = R.string.llm_preset_desc_precise; break;
                case "code": descResId = R.string.llm_preset_desc_code; break;
                case "custom": descResId = R.string.llm_preset_desc_custom; break;
                default: descResId = R.string.llm_preset_desc_balanced; break;
            }
            presetInfo.setSummary(getString(descResId));
        }

        private String maskApiKey(String key) {
            if (key == null || key.length() < 8) return "****";
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }

        private String getPersonaPrompt(String persona) {
            int resId = 0;
            switch (persona) {
                case "professional": resId = R.string.persona_prompt_professional; break;
                case "friendly": resId = R.string.persona_prompt_friendly; break;
                case "creative": resId = R.string.persona_prompt_creative; break;
                case "technical": resId = R.string.persona_prompt_technical; break;
                case "concise": resId = R.string.persona_prompt_concise; break;
                case "tutor": resId = R.string.persona_prompt_tutor; break;
                case "translator": resId = R.string.persona_prompt_translator; break;
                case "coder": resId = R.string.persona_prompt_coder; break;
                case "data_analyst": resId = R.string.persona_prompt_data_analyst; break;
                default: return null;
            }
            return getString(resId);
        }

        private void showTemplatePicker() {
            String[] names = getResources().getStringArray(R.array.llm_persona_names);
            String[] values = getResources().getStringArray(R.array.llm_persona_values);
            String[] previews = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                String prompt = getPersonaPrompt(values[i]);
                previews[i] = names[i] + "\n\n" + (prompt != null ? prompt.substring(0, Math.min(80, prompt.length())) + "…" : "");
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.llm_templates_title)
                    .setItems(names, (dialog, which) -> {
                        String prompt = getPersonaPrompt(values[which]);
                        if (prompt != null) {
                            mConfigManager.setSystemPrompt(prompt);
                            EditTextPreference sysPref = findPreference("llm_system_prompt");
                            if (sysPref != null) sysPref.setText(prompt);
                            ListPreference personaPref = findPreference("llm_persona_template");
                            if (personaPref != null) personaPref.setValue(values[which]);
                            mHasUnsavedChanges = true;
                            Toast.makeText(requireContext(),
                                    names[which] + " applied", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if ("llm_test_connection".equals(key)) {
                testConnection(preference);
                return true;
            }
            if ("llm_get_api_key".equals(key)) {
                showProviderSetupGuide();
                return true;
            }
            if ("llm_docs".equals(key)) {
                openProviderUrl(getDocsUrl());
                return true;
            }
            if ("llm_export_qr".equals(key)) {
                showQrExportDialog();
                return true;
            }
            if ("llm_import_qr".equals(key)) {
                showQrImportDialog();
                return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void updateRoutingVisibility(String mode) {
                Preference fastModelPref = findPreference("llm_routing_fast_model");
                Preference thresholdPref = findPreference("llm_routing_threshold");
                boolean visible = !"off".equals(mode);
                if (fastModelPref != null) fastModelPref.setVisible(visible);
                if (thresholdPref != null) thresholdPref.setVisible(visible);
            }

        private void openProviderUrl(String url) {
            if (url == null) return;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                startActivity(intent);
            } catch (Exception ignored) {}
        }

        private void showOllamaSetupGuide() {
            float density = getResources().getDisplayMetrics().density;
            ScrollView scrollView = new ScrollView(requireContext());
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * density);
            layout.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(requireContext());
            title.setText(R.string.ollama_setup_guide_title);
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(title);

            TextView intro = new TextView(requireContext());
            intro.setText(R.string.ollama_setup_intro);
            intro.setTextSize(14);
            intro.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(intro);

            TextView stepsTitle = new TextView(requireContext());
            stepsTitle.setText(R.string.ollama_setup_steps_title);
            stepsTitle.setTextSize(16);
            stepsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            stepsTitle.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
            layout.addView(stepsTitle);

            String[] steps = getString(R.string.ollama_setup_steps).split("\n");
            for (String step : steps) {
                TextView stepView = new TextView(requireContext());
                stepView.setText(step);
                stepView.setTextSize(13);
                stepView.setPadding((int) (8 * density), (int) (4 * density), 0, (int) (4 * density));
                layout.addView(stepView);
            }

            TextView popularModels = new TextView(requireContext());
            popularModels.setText(R.string.ollama_setup_models);
            popularModels.setTextSize(13);
            popularModels.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
            layout.addView(popularModels);

            scrollView.addView(layout);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ollama_setup_dialog_title)
                    .setView(scrollView)
                    .setPositiveButton(R.string.ollama_setup_open_terminal, (d, w) -> {
                        // Switch to bash tab
                        requireActivity().setResult(RESULT_FIRST_USER);
                        requireActivity().finish();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private String getApiKeyUrl() {
            String provider = mConfigManager.getModelProvider();
            switch (provider) {
                case "openai":     return "https://platform.openai.com/api-keys";
                case "anthropic":  return "https://console.anthropic.com/settings/keys";
                case "google":     return "https://aistudio.google.com/apikey";
                case "deepseek":   return "https://platform.deepseek.com/api_keys";
                case "openrouter": return "https://openrouter.ai/keys";
                case "xai":        return "https://console.x.ai/";
                case "alibaba":    return "https://dashscope.console.aliyun.com/apiKey";
                case "mistral":    return "https://console.mistral.ai/api-keys/";
                case "nvidia":     return "https://build.nvidia.com/";
                case "ollama":     return "http://localhost:11434";
                default:           return null;
            }
        }

        private String getDocsUrl() {
            String provider = mConfigManager.getModelProvider();
            switch (provider) {
                case "openai":     return "https://platform.openai.com/docs";
                case "anthropic":  return "https://docs.anthropic.com/en/docs";
                case "google":     return "https://ai.google.dev/gemini-api/docs";
                case "deepseek":   return "https://api-docs.deepseek.com/";
                case "openrouter": return "https://openrouter.ai/docs";
                case "xai":        return "https://docs.x.ai/";
                case "alibaba":    return "https://help.aliyun.com/document_detail/2712195.html";
                case "mistral":    return "https://docs.mistral.ai/";
                case "nvidia":     return "https://build.nvidia.com/explore/discover";
                case "ollama":     return "https://github.com/ollama/ollama";
                default:           return null;
            }
        }

        private void showProviderSetupGuide() {
            String provider = mConfigManager.getModelProvider();
            if ("ollama".equals(provider)) {
                showOllamaSetupGuide();
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            ScrollView scrollView = new ScrollView(requireContext());
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * density);
            layout.setPadding(pad, pad, pad, pad);

            // Title
            String providerDisplayName = getProviderDisplayName(provider);
            TextView title = new TextView(requireContext());
            title.setText(getString(R.string.provider_setup_guide_title, providerDisplayName));
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(title);

            // Intro
            TextView intro = new TextView(requireContext());
            intro.setText(getProviderIntroResId(provider));
            intro.setTextSize(14);
            intro.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(intro);

            // Pricing badge
            TextView pricing = new TextView(requireContext());
            pricing.setText(getString(R.string.provider_setup_pricing_title) + ": " + getProviderPricingText(provider));
            pricing.setTextSize(13);
            pricing.setTypeface(null, android.graphics.Typeface.ITALIC);
            pricing.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(pricing);

            // Steps title
            TextView stepsTitle = new TextView(requireContext());
            stepsTitle.setText(R.string.provider_setup_step_title);
            stepsTitle.setTextSize(16);
            stepsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            stepsTitle.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
            layout.addView(stepsTitle);

            // Steps
            String[] steps = getString(getProviderStepsResId(provider)).split("\n");
            for (String step : steps) {
                TextView stepView = new TextView(requireContext());
                stepView.setText(step);
                stepView.setTextSize(13);
                stepView.setPadding((int) (8 * density), (int) (4 * density), 0, (int) (4 * density));
                layout.addView(stepView);
            }

            scrollView.addView(layout);

            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.provider_setup_guide_title, providerDisplayName))
                    .setView(scrollView)
                    .setPositiveButton(R.string.provider_setup_open_dashboard, (d, w) -> {
                        openProviderUrl(getApiKeyUrl());
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private String getProviderDisplayName(String provider) {
            switch (provider) {
                case "openai": return "OpenAI";
                case "anthropic": return "Anthropic";
                case "google": return "Google AI";
                case "deepseek": return "DeepSeek";
                case "openrouter": return "OpenRouter";
                case "xai": return "xAI";
                case "alibaba": return "Alibaba Cloud";
                case "mistral": return "Mistral AI";
                case "nvidia": return "NVIDIA";
                case "ollama": return "Ollama";
                default: return provider;
            }
        }

        private int getProviderIntroResId(String provider) {
            switch (provider) {
                case "openai": return R.string.provider_setup_openai_intro;
                case "anthropic": return R.string.provider_setup_anthropic_intro;
                case "google": return R.string.provider_setup_google_intro;
                case "deepseek": return R.string.provider_setup_deepseek_intro;
                case "openrouter": return R.string.provider_setup_openrouter_intro;
                case "xai": return R.string.provider_setup_xai_intro;
                case "alibaba": return R.string.provider_setup_alibaba_intro;
                case "mistral": return R.string.provider_setup_mistral_intro;
                case "nvidia": return R.string.provider_setup_nvidia_intro;
                default: return R.string.provider_setup_custom_intro;
            }
        }

        private int getProviderStepsResId(String provider) {
            switch (provider) {
                case "openai": return R.string.provider_setup_openai_steps;
                case "anthropic": return R.string.provider_setup_anthropic_steps;
                case "google": return R.string.provider_setup_google_steps;
                case "deepseek": return R.string.provider_setup_deepseek_steps;
                case "openrouter": return R.string.provider_setup_openrouter_steps;
                case "xai": return R.string.provider_setup_xai_steps;
                case "alibaba": return R.string.provider_setup_alibaba_steps;
                case "mistral": return R.string.provider_setup_mistral_steps;
                case "nvidia": return R.string.provider_setup_nvidia_steps;
                default: return R.string.provider_setup_custom_steps;
            }
        }

        private String getProviderPricingText(String provider) {
            switch (provider) {
                case "ollama": return getString(R.string.provider_setup_local_free);
                case "google":
                case "openrouter":
                    return getString(R.string.provider_setup_free_tier);
                default:
                    return getString(R.string.provider_setup_paid);
            }
        }

        private void testConnection(Preference testPref) {
            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            String model = mConfigManager.getModelName();

            if ("ollama".equals(provider)) {
                apiKey = "ollama";
            } else if (apiKey.isEmpty()) {
                testPref.setSummary(getString(R.string.llm_test_no_key));
                return;
            }

            testPref.setSummary(getString(R.string.llm_test_running));
            String finalApiKey = apiKey;

            new Thread(() -> {
                // Phase 1: Basic connectivity check (existing validation)
                String[] result = performConnectionTest(provider, finalApiKey, model);
                if (!result[0].equals("success")) {
                    requireActivity().runOnUiThread(() -> {
                        if (result[0].equals("auth")) {
                            testPref.setSummary(getString(R.string.llm_test_fail_auth));
                        } else if (result[0].equals("network")) {
                            testPref.setSummary(getString(R.string.llm_test_fail_network));
                        } else {
                            testPref.setSummary(getString(R.string.llm_test_fail_generic, result[1]));
                        }
                    });
                    return;
                }

                // Phase 2: Model response test (streaming test)
                requireActivity().runOnUiThread(() ->
                        testPref.setSummary(getString(R.string.llm_test_streaming)));
                String[] streamResult = performModelResponseTest(provider, finalApiKey, model);
                requireActivity().runOnUiThread(() -> {
                    if (streamResult[0].equals("success_fast")) {
                        testPref.setSummary(getString(R.string.llm_test_success_fast,
                                streamResult[1], Integer.parseInt(streamResult[2])));
                    } else if (streamResult[0].equals("success_slow")) {
                        testPref.setSummary(getString(R.string.llm_test_success_slow,
                                Integer.parseInt(streamResult[2])));
                    } else if (streamResult[0].equals("no_response")) {
                        testPref.setSummary(getString(R.string.llm_test_fail_no_response));
                    } else {
                        // Fallback: basic validation succeeded, show basic success
                        if ("ollama".equals(provider)) {
                            testPref.setSummary(getString(R.string.llm_test_success_no_key, provider));
                        } else {
                            testPref.setSummary(getString(R.string.llm_test_success, model));
                        }
                    }
                });
            }).start();
        }

        private String[] performConnectionTest(String provider, String apiKey, String model) {
            try {
                String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String curlPath = binPath + "/curl";

                if (!new File(curlPath).exists()) {
                    return new String[]{"generic", "curl not available"};
                }

                String url = getProviderTestUrl(provider);
                if (url == null) {
                    return new String[]{"generic", "Unknown provider: " + provider};
                }

                ProcessBuilder pb;
                if ("ollama".equals(provider)) {
                    pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                            "--connect-timeout", "5", url);
                } else if ("google".equals(provider)) {
                    pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                            "--connect-timeout", "10", url + apiKey);
                } else {
                    pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                            "--connect-timeout", "10", "-H", "Authorization: Bearer " + apiKey, url);
                }

                pb.environment().put("PATH", binPath + ":/system/bin");
                pb.redirectErrorStream(true);

                Process p = pb.start();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String output = reader.readLine();
                p.waitFor();

                int httpCode = 0;
                try {
                    httpCode = Integer.parseInt(output != null ? output.trim() : "0");
                } catch (NumberFormatException ignored) {}

                if (httpCode == 200) {
                    return new String[]{"success", model};
                } else if (httpCode == 401 || httpCode == 403) {
                    return new String[]{"auth", "" + httpCode};
                } else if (httpCode == 0) {
                    return new String[]{"network", "no response"};
                } else {
                    return new String[]{"generic", "HTTP " + httpCode};
                }
            } catch (Exception e) {
                return new String[]{"network", e.getMessage()};
            }
        }

        private String getProviderTestUrl(String provider) {
            String baseUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
            switch (provider) {
                case "openai":     return "https://api.openai.com/v1/models";
                case "anthropic":  return "https://api.anthropic.com/v1/models";
                case "google":     return "https://generativelanguage.googleapis.com/v1beta/models?key=";
                case "deepseek":   return "https://api.deepseek.com/models";
                case "openrouter": return "https://openrouter.ai/api/v1/models";
                case "xai":        return "https://api.x.ai/v1/models";
                case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1/models";
                case "mistral":    return "https://api.mistral.ai/v1/models";
                case "nvidia":     return "https://integrate.api.nvidia.com/v1/models";
                case "ollama":     return baseUrl.isEmpty() ? "http://localhost:11434/api/tags" : baseUrl + "/api/tags";
                case "custom":     return baseUrl.isEmpty() ? null : baseUrl + "/models";
                default:           return null;
            }
        }

        /**
         * Phase 2: Send a minimal chat completion request to verify the model can actually respond.
         * Returns String[] with:
         *   [0] = "success_fast" | "success_slow" | "no_response" | "error"
         *   [1] = model name (on success)
         *   [2] = response time in ms (on success)
         */
        private String[] performModelResponseTest(String provider, String apiKey, String model) {
            try {
                String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String curlPath = binPath + "/curl";

                if (!new File(curlPath).exists()) {
                    return new String[]{"error", "curl not available", "0"};
                }

                String baseUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
                String url = getProviderChatUrl(provider, model, baseUrl);
                if (url == null) {
                    return new String[]{"error", "Unknown provider for chat test", "0"};
                }

                // Build the curl command based on provider
                ProcessBuilder pb;
                if ("ollama".equals(provider)) {
                    String jsonBody = "{\"model\":\"" + model + "\",\"prompt\":\"Say OK\",\"stream\":false}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-d", jsonBody);
                } else if ("google".equals(provider)) {
                    String jsonBody = "{\"contents\":[{\"parts\":[{\"text\":\"Say OK\"}]}],\"generationConfig\":{\"maxOutputTokens\":10}}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-d", jsonBody);
                } else {
                    // OpenAI-compatible providers
                    String jsonBody = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":10,\"stream\":false}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-H", "Authorization: Bearer " + apiKey,
                            "-d", jsonBody);
                }

                pb.environment().put("PATH", binPath + ":/system/bin");
                pb.redirectErrorStream(true);

                long startTime = System.currentTimeMillis();
                Process p = pb.start();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                p.waitFor();
                long elapsed = System.currentTimeMillis() - startTime;

                String fullOutput = output.toString().trim();
                // Last line is the HTTP code from -w flag
                String[] lines = fullOutput.split("\n");
                String lastLine = lines.length > 0 ? lines[lines.length - 1].trim() : "";
                int httpCode = 0;
                try {
                    httpCode = Integer.parseInt(lastLine);
                } catch (NumberFormatException ignored) {}

                // Reconstruct the response body (everything except the last line)
                String responseBody = "";
                if (lines.length > 1) {
                    StringBuilder bodyBuilder = new StringBuilder();
                    for (int i = 0; i < lines.length - 1; i++) {
                        bodyBuilder.append(lines[i]);
                    }
                    responseBody = bodyBuilder.toString();
                }

                if (httpCode == 200 && !responseBody.isEmpty()) {
                    // Check that the response contains actual content
                    boolean hasContent = responseBody.contains("choices")
                            || responseBody.contains("content")
                            || responseBody.contains("response")
                            || responseBody.contains("candidates")
                            || responseBody.contains("OK");
                    if (hasContent) {
                        if (elapsed < 10000) {
                            return new String[]{"success_fast", model, String.valueOf(elapsed)};
                        } else {
                            return new String[]{"success_slow", model, String.valueOf(elapsed)};
                        }
                    } else {
                        return new String[]{"no_response", "", "0"};
                    }
                } else if (httpCode == 401 || httpCode == 403) {
                    return new String[]{"error", "Auth failed in model test", "0"};
                } else if (httpCode == 404) {
                    return new String[]{"no_response", "Model not found", "0"};
                } else if (httpCode == 0) {
                    return new String[]{"error", "No response from server", "0"};
                } else {
                    return new String[]{"no_response", "HTTP " + httpCode, "0"};
                }
            } catch (Exception e) {
                return new String[]{"error", e.getMessage() != null ? e.getMessage() : "exception", "0"};
            }
        }

        /**
         * Get the chat completion endpoint URL for a given provider.
         */
        private String getProviderChatUrl(String provider, String model, String baseUrl) {
            switch (provider) {
                case "openai":     return "https://api.openai.com/v1/chat/completions";
                case "anthropic":  return "https://api.anthropic.com/v1/chat/completions";
                case "deepseek":   return "https://api.deepseek.com/v1/chat/completions";
                case "openrouter": return "https://openrouter.ai/api/v1/chat/completions";
                case "xai":        return "https://api.x.ai/v1/chat/completions";
                case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                case "mistral":    return "https://api.mistral.ai/v1/chat/completions";
                case "nvidia":     return "https://integrate.api.nvidia.com/v1/chat/completions";
                case "google":     return "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
                case "ollama":     return baseUrl.isEmpty() ? "http://localhost:11434/api/generate" : baseUrl + "/api/generate";
                case "custom":     return baseUrl.isEmpty() ? null : baseUrl + "/chat/completions";
                default:           return null;
            }
        }

        private void updateCostEstimate(String model) {
            Preference costPref = findPreference("llm_cost_estimate");
            if (costPref == null) return;

            String provider = mConfigManager.getModelProvider();
            if ("ollama".equals(provider)) {
                costPref.setSummary(getString(R.string.llm_cost_free));
                costPref.setVisible(true);
                return;
            }
            if ("custom".equals(provider)) {
                costPref.setVisible(false);
                return;
            }

            String cost = getModelCostEstimate(model);
            if (cost != null) {
                costPref.setSummary(getString(R.string.llm_cost_estimate_format, cost));
                costPref.setVisible(true);
            } else {
                costPref.setSummary(getString(R.string.llm_cost_estimate_unknown));
                costPref.setVisible(true);
            }
        }

        private String getModelCostEstimate(String model) {
            if (model == null) return null;
            switch (model) {
                // OpenAI
                case "gpt-4o": return "$2.50 / $10.00 per 1M tokens";
                case "gpt-4o-mini": return "$0.15 / $0.60 per 1M tokens";
                case "o1": return "$15.00 / $60.00 per 1M tokens";
                case "o1-mini": return "$3.00 / $12.00 per 1M tokens";
                case "o3-mini": return "$1.10 / $4.40 per 1M tokens";
                // Anthropic
                case "claude-sonnet-4-6": return "$3.00 / $15.00 per 1M tokens";
                case "claude-opus-4-7": return "$15.00 / $75.00 per 1M tokens";
                case "claude-haiku-4-5": return "$0.80 / $4.00 per 1M tokens";
                // Google
                case "gemini-2.5-pro": return "$1.25 / $10.00 per 1M tokens";
                case "gemini-2.5-flash": return "$0.15 / $0.60 per 1M tokens";
                // DeepSeek
                case "deepseek-chat": return "$0.14 / $0.28 per 1M tokens";
                case "deepseek-reasoner": return "$0.55 / $2.19 per 1M tokens";
                // xAI
                case "grok-3": return "$3.00 / $15.00 per 1M tokens";
                case "grok-3-mini": return "$0.35 / $0.50 per 1M tokens";
                // Mistral
                case "mistral-large-latest": return "$2.00 / $6.00 per 1M tokens";
                case "mistral-small-latest": return "$0.10 / $0.30 per 1M tokens";
                default: return null;
            }
        }

        private void updateModelInfo(String model) {
            Preference modelInfoPref = findPreference("llm_model_info");
            if (modelInfoPref == null) return;
            int descResId = getModelDescResId(model);
            if (descResId != 0) {
                modelInfoPref.setSummary(getString(descResId));
                modelInfoPref.setVisible(true);
            } else {
                modelInfoPref.setVisible(false);
            }
        }

        private int getModelDescResId(String model) {
            if (model == null) return 0;
            switch (model) {
                // OpenAI
                case "gpt-4o": return R.string.model_desc_gpt_4o;
                case "gpt-4o-mini": return R.string.model_desc_gpt_4o_mini;
                case "o1": return R.string.model_desc_o1;
                case "o1-mini": return R.string.model_desc_o1_mini;
                case "o3-mini": return R.string.model_desc_o3_mini;
                case "gpt-4-turbo": return R.string.model_desc_gpt_4_turbo;
                // Anthropic
                case "claude-sonnet-4-6": return R.string.model_desc_claude_sonnet;
                case "claude-opus-4-7": return R.string.model_desc_claude_opus;
                case "claude-haiku-4-5": return R.string.model_desc_claude_haiku;
                // Google
                case "gemini-2.5-pro": return R.string.model_desc_gemini_pro;
                case "gemini-2.5-flash": return R.string.model_desc_gemini_flash;
                case "gemini-2.0-flash": return R.string.model_desc_gemini_flash2;
                // DeepSeek
                case "deepseek-chat": return R.string.model_desc_deepseek_chat;
                case "deepseek-reasoner": return R.string.model_desc_deepseek_reasoner;
                // xAI
                case "grok-3": return R.string.model_desc_grok_3;
                case "grok-3-mini": return R.string.model_desc_grok_3_mini;
                // Alibaba
                case "qwen-max": return R.string.model_desc_qwen_max;
                case "qwen-plus": return R.string.model_desc_qwen_plus;
                case "qwen-turbo": return R.string.model_desc_qwen_turbo;
                // Mistral
                case "mistral-large-latest": return R.string.model_desc_mistral_large;
                case "mistral-medium-latest": return R.string.model_desc_mistral_medium;
                case "codestral-latest": return R.string.model_desc_codestral;
                // NVIDIA
                case "meta/llama-3.3-70b-instruct": return R.string.model_desc_llama_70b;
                case "deepseek-ai/deepseek-r1": return R.string.model_desc_deepseek_r1_nvidia;
                case "google/gemma-2-27b-it": return R.string.model_desc_gemma_27b;
                case "nvidia/llama-3.1-nemotron-70b-instruct": return R.string.model_desc_nemotron_70b;
                // Ollama
                case "llama3": return R.string.model_desc_llama3_local;
                case "qwen2.5": return R.string.model_desc_qwen25_local;
                case "deepseek-r1": return R.string.model_desc_deepseek_r1_local;
                case "gemma2": return R.string.model_desc_gemma2_local;
                default: return 0;
            }
        }

        private void showQrExportDialog() {
            String provider = mConfigManager.getModelProvider();
            String model = mConfigManager.getModelName();
            String apiKey = mConfigManager.getApiKey(provider);
            // Mask API key for sharing
            String maskedKey = (apiKey != null && apiKey.length() > 8)
                    ? apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4)
                    : (apiKey != null && !apiKey.isEmpty() ? "****" : "");

            String qrData = "hermes-llm://config?"
                    + "provider=" + provider
                    + "&model=" + model
                    + "&temp=" + mConfigManager.getModelTemperature()
                    + "&max_tokens=" + mConfigManager.getModelMaxTokens()
                    + (maskedKey.isEmpty() ? "" : "&key_hint=" + maskedKey);

            try {
                com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
                com.google.zxing.common.BitMatrix matrix = writer.encode(qrData,
                        com.google.zxing.BarcodeFormat.QR_CODE, 512, 512);

                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(512, 512,
                        android.graphics.Bitmap.Config.RGB_565);
                for (int x = 0; x < 512; x++) {
                    for (int y = 0; y < 512; y++) {
                        bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                    }
                }

                ImageView qrView = new ImageView(requireContext());
                qrView.setImageBitmap(bitmap);
                qrView.setPadding(0, dp(16), 0, dp(8));

                LinearLayout container = new LinearLayout(requireContext());
                container.setOrientation(LinearLayout.VERTICAL);
                container.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

                TextView info = new TextView(requireContext());
                info.setText(getString(R.string.llm_qr_export_info, provider, model));
                info.setTextSize(13);
                info.setPadding(0, dp(8), 0, 0);

                container.addView(qrView);
                container.addView(info);

                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.llm_export_qr_title)
                        .setView(container)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.llm_qr_copy, (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Hermes Config", qrData));
                            Toast.makeText(requireContext(), R.string.llm_qr_copied, Toast.LENGTH_SHORT).show();
                        })
                        .show();
            } catch (Exception e) {
                Toast.makeText(requireContext(), getString(R.string.llm_qr_error, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void showQrImportDialog() {
            EditText input = new EditText(requireContext());
            input.setHint(R.string.llm_qr_paste_hint);
            input.setPadding(dp(16), dp(8), dp(16), dp(8));

            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.llm_import_qr_title)
                    .setMessage(R.string.llm_qr_import_instructions)
                    .setView(input)
                    .setPositiveButton(R.string.llm_qr_import_apply, (d, w) -> {
                        String data = input.getText().toString().trim();
                        if (!data.startsWith("hermes-llm://config?")) {
                            Toast.makeText(requireContext(), R.string.llm_qr_invalid, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyQrConfig(data);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void applyQrConfig(String data) {
            String params = data.substring("hermes-llm://config?".length());
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (String pair : params.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }

            if (map.containsKey("provider")) {
                String provider = map.get("provider");
                mConfigManager.setModelProvider(provider);
                ListPreference providerPref = findPreference("llm_provider");
                if (providerPref != null) providerPref.setValue(provider);
            }
            if (map.containsKey("model")) {
                String model = map.get("model");
                mConfigManager.setModelName(model);
                ListPreference modelPref = findPreference("llm_model");
                if (modelPref != null) modelPref.setValue(model);
            }
            if (map.containsKey("temp")) {
                try {
                    float temp = Float.parseFloat(map.get("temp"));
                    mConfigManager.setModelTemperature(temp);
                    Preference tempPref = findPreference("llm_temperature");
                    if (tempPref instanceof EditTextPreference) ((EditTextPreference) tempPref).setText(String.valueOf(temp));
                } catch (NumberFormatException ignored) {}
            }
            if (map.containsKey("max_tokens")) {
                try {
                    int maxTokens = Integer.parseInt(map.get("max_tokens"));
                    mConfigManager.setModelMaxTokens(maxTokens);
                    Preference mtPref = findPreference("llm_max_tokens");
                    if (mtPref instanceof EditTextPreference) ((EditTextPreference) mtPref).setText(String.valueOf(maxTokens));
                } catch (NumberFormatException ignored) {}
            }

            mHasUnsavedChanges = true;
            Toast.makeText(requireContext(), R.string.llm_qr_imported, Toast.LENGTH_SHORT).show();
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density);
        }
    }

    public static class GatewayControlFragment extends PreferenceFragmentCompat {
        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_gateway_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // Stats display
            updateStatsDisplay();

            // Auto-restart toggle
            Preference autoRestartPref = findPreference("gateway_auto_restart");
            if (autoRestartPref != null) {
                autoRestartPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_AUTO_RESTART", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Max restarts
            Preference maxRestartsPref = findPreference("gateway_max_restarts");
            if (maxRestartsPref != null) {
                maxRestartsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_MAX_RESTARTS", (String) newVal);
                    return true;
                });
            }

            // Restart delay
            Preference restartDelayPref = findPreference("gateway_restart_delay");
            if (restartDelayPref != null) {
                restartDelayPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_RESTART_DELAY", (String) newVal);
                    return true;
                });
            }

            // SSH status display
            updateSshStatus();

            // SSH port change
            EditTextPreference sshPortPref = findPreference("ssh_port");
            if (sshPortPref != null) {
                sshPortPref.setOnPreferenceChangeListener((p, newVal) -> {
                    updateSshdConfigPort((String) newVal);
                    return true;
                });
            }

            // SSH password change
            EditTextPreference sshPasswordPref = findPreference("ssh_password");
            if (sshPasswordPref != null) {
                sshPasswordPref.setOnPreferenceChangeListener((p, newVal) -> {
                    updateSshPassword((String) newVal);
                    return true;
                });
            }

        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if (key == null) return super.onPreferenceTreeClick(preference);

            switch (key) {
                case "gateway_start":
                    showPreLaunchChecks();
                    return true;
                case "gateway_stop":
                    runGatewayCommand("stop");
                    return true;
                case "gateway_restart":
                    runGatewayCommand("restart");
                    return true;
                case "ssh_start":
                    startSshd();
                    return true;
                case "ssh_stop":
                    stopSshd();
                    return true;
                case "ssh_info":
                    showSshInfo();
                    return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void runGatewayCommand(String action) {
            Context ctx = requireContext();
            switch (action) {
                case "start":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_START));
                    Toast.makeText(ctx, R.string.gateway_started, Toast.LENGTH_SHORT).show();
                    break;
                case "stop":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_STOP));
                    Toast.makeText(ctx, R.string.gateway_stopped, Toast.LENGTH_SHORT).show();
                    break;
                case "restart":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_STOP));
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        ctx.startService(new Intent(ctx, HermesGatewayService.class)
                                .setAction(HermesGatewayService.ACTION_START));
                    }, 1500);
                    Toast.makeText(ctx, R.string.gateway_restarted, Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateStatsDisplay();
            updateSshStatus();
        }

        private void updateStatsDisplay() {
            boolean running = HermesGatewayService.isRunning();
            String uptime = HermesGatewayService.getFormattedUptime();

            Preference statsPref = findPreference("gateway_stats");
            if (statsPref != null) {
                if (running && !uptime.isEmpty()) {
                    statsPref.setSummary(getString(R.string.gateway_stats_running, uptime));
                } else {
                    statsPref.setSummary(getString(R.string.gateway_stats_stopped));
                }
            }
        }

        private void updateSshStatus() {
            Preference sshStatusPref = findPreference("ssh_status");
            if (sshStatusPref == null) return;

            File sshd = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "/sshd");
            if (!sshd.exists()) {
                sshStatusPref.setSummary(getString(R.string.ssh_status_not_installed));
                return;
            }

            // Check if sshd is running
            new Thread(() -> {
                try {
                    String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", "pgrep -x sshd >/dev/null 2>&1 && echo running || echo stopped");
                    pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String result = reader.readLine();
                    p.waitFor();

                    if (getActivity() != null) {
                        String port = getSshPort();
                        int portNum;
                        try { portNum = Integer.parseInt(port); } catch (NumberFormatException e) { portNum = 8022; }
                        int finalPortNum = portNum;
                        getActivity().runOnUiThread(() -> {
                            if ("running".equals(result)) {
                                sshStatusPref.setSummary(getString(R.string.ssh_status_running, finalPortNum));
                            } else {
                                sshStatusPref.setSummary(getString(R.string.ssh_status_stopped));
                            }
                        });
                    }
                } catch (Exception e) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> sshStatusPref.setSummary(getString(R.string.ssh_status_stopped)));
                    }
                }
            }).start();
        }

        private String getSshPort() {
            try {
                java.io.File configFile = new java.io.File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/ssh/sshd_config");
                if (configFile.exists()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(configFile));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Port ")) {
                            return line.substring(5).trim();
                        }
                    }
                }
            } catch (Exception ignored) {}
            return "8022";
        }

        private void startSshd() {
            new Thread(() -> {
                try {
                    String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                    String sshdPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd";
                    String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;

                    StringBuilder diag = new StringBuilder();

                    // Auto-install openssh if sshd binary is missing
                    if (!new File(sshdPath).exists()) {
                        diag.append("sshd not found, auto-installing openssh…\n");
                        // Ensure mirror is configured before installing
                        runPkgCommand(bashPath, prefix,
                                "grep -q tuna " + prefix + "/etc/apt/sources.list 2>/dev/null"
                                + " || echo 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main' > " + prefix + "/etc/apt/sources.list",
                                5_000);
                        runPkgCommand(bashPath, prefix, "apt-get update -y 2>&1", 60_000);
                        String pkgOutput = runPkgCommand(bashPath, prefix,
                                "apt-get install -y openssh 2>&1", 120_000);
                        diag.append(truncateOutput(pkgOutput, 25)).append("\n");
                        if (!new File(sshdPath).exists()) {
                            diag.append("openssh install failed — sshd still not found at ").append(sshdPath);
                            showErrorDialog(diag.toString());
                            return;
                        }
                    }

                    // Deploy sshd_config if missing (after openssh install)
                    String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                    File sshdConfig = new File(prefix, "etc/ssh/sshd_config");
                    if (!sshdConfig.exists()) {
                        diag.append("sshd_config missing, deploying default config…\n");
                        String setEnv = new File(pathRewriteLib).exists()
                                ? "SetEnv LD_PRELOAD=" + pathRewriteLib : "";
                        String cfgDeploy = runPkgCommand(bashPath, prefix,
                                "mkdir -p " + prefix + "/etc/ssh && "
                                + "printf 'Port 8022\\nPasswordAuthentication yes\\nPrintMotd yes\\n"
                                + setEnv + "\\n"
                                + "Subsystem sftp " + prefix + "/libexec/sftp-server\\n' > "
                                + prefix + "/etc/ssh/sshd_config 2>&1 && echo __CFG_OK__", 10_000);
                        if (!cfgDeploy.contains("__CFG_OK__")) {
                            diag.append(cfgDeploy).append("\n");
                        }
                    }

                    // Auto-generate host keys if missing
                    File sshDir = new File(prefix, "etc/ssh");
                    boolean hasHostKey = false;
                    if (sshDir.isDirectory()) {
                        File[] keys = sshDir.listFiles((d, n) -> n.startsWith("ssh_host_") && n.endsWith("_key"));
                        hasHostKey = keys != null && keys.length > 0;
                    }
                    if (!hasHostKey) {
                        diag.append("Host keys missing, generating…\n");
                        String kgOutput = runPkgCommand(bashPath, prefix,
                                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/ssh-keygen -A 2>&1", 30_000);
                        diag.append(truncateOutput(kgOutput, 10)).append("\n");
                        runPkgCommand(bashPath, prefix,
                                "chmod 600 " + prefix + "/etc/ssh/ssh_host_*_key 2>/dev/null", 5_000);
                    }

                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c",
                            sshdPath + " 2>&1; pgrep -x sshd >/dev/null 2>&1 && echo __OK__ || echo __FAIL__");
                    pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                    pb.environment().put("PREFIX", prefix);
                    pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                    pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                    pb.environment().put("TERMUX_VERSION", com.termux.BuildConfig.VERSION_NAME);
                    pb.environment().put("TERMINFO", prefix + "/share/terminfo");
                    String pathRewrite = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                    if (new File(pathRewrite).exists()) {
                        pb.environment().put("LD_PRELOAD", pathRewrite);
                    }
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    StringBuilder output = new StringBuilder();
                    try {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                        if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                            output.append("[sshd start timed out]");
                        }
                    } finally {
                        p.destroy();
                    }

                    String outputStr = output.toString().trim();
                    boolean success = outputStr.endsWith("__OK__");
                    String cleanOutput = outputStr.replace("__OK__", "").replace("__FAIL__", "").trim();

                    if (!success) {
                        String logMsg = "sshd start failed. Output: " + cleanOutput;
                        String diagStr = diag.toString().trim();
                        if (!diagStr.isEmpty()) logMsg += "\nDiagnostics:\n" + diagStr;
                        com.termux.shared.logger.Logger.logErrorExtended("GatewayControl", logMsg);
                    }

                    if (getActivity() != null) {
                        String finalDiag = diag.toString().trim();
                        String finalOutput = cleanOutput;
                        getActivity().runOnUiThread(() -> {
                            if (success) {
                                Toast.makeText(requireContext(), R.string.ssh_started, Toast.LENGTH_SHORT).show();
                            } else {
                                String errorMsg = finalOutput;
                                if (!finalDiag.isEmpty()) {
                                    errorMsg = finalDiag + "\n\n" + errorMsg;
                                }
                                if (errorMsg.trim().isEmpty()) {
                                    errorMsg = getString(R.string.ssh_no_output_hint);
                                }
                                showErrorDialog(errorMsg);
                            }
                            updateSshStatus();
                        });
                    }
                } catch (Exception e) {
                    com.termux.shared.logger.Logger.logErrorExtended("GatewayControl",
                            "startSshd exception: " + e.getMessage());
                    showErrorDialog(e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
            }).start();
        }

        private String runPkgCommand(String bashPath, String prefix, String command, long timeoutMs) {
            try {
                ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", command);
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                pb.environment().put("PREFIX", prefix);
                pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                pb.environment().put("TERMUX_VERSION", com.termux.BuildConfig.VERSION_NAME);
                pb.environment().put("TERMINFO", prefix + "/share/terminfo");
                String pathRewrite = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                if (new File(pathRewrite).exists()) {
                    pb.environment().put("LD_PRELOAD", pathRewrite);
                }
                String aptConfFile = prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf";
                if (new File(aptConfFile).exists()) {
                    pb.environment().put("APT_CONFIG", aptConfFile);
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                        return sb.toString().trim() + "\n[TIMEOUT after " + (timeoutMs / 1000) + "s]";
                    }
                } finally {
                    p.destroy();
                }
                return sb.toString().trim();
            } catch (Exception e) {
                return "Command failed: " + e.getMessage();
            }
        }

        private String truncateOutput(String output, int maxLines) {
            if (output == null || output.isEmpty()) return output;
            String[] lines = output.split("\n");
            if (lines.length <= maxLines) return output;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append("\n");
            sb.append("... (").append(lines.length - maxLines).append(" more lines)");
            return sb.toString();
        }

        private void showErrorDialog(String message) {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.ssh_start_failed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.copy, (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) requireContext()
                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("SSH Error", message));
                                Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            });
        }

        private void stopSshd() {
            new Thread(() -> {
                try {
                    String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", "pkill -x sshd 2>/dev/null; echo done");
                    pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    while (p.getInputStream().read() != -1) {}
                    p.waitFor();

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), R.string.ssh_stopped, Toast.LENGTH_SHORT).show();
                            updateSshStatus();
                        });
                    }
                } catch (Exception e) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), R.string.ssh_stopped, Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            }).start();
        }

        private void updateSshdConfigPort(String port) {
            // Validate port is numeric and in valid range
            try {
                int portNum = Integer.parseInt(port);
                if (portNum < 1 || portNum > 65535) {
                    Toast.makeText(requireContext(), "Port must be 1-65535", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid port number", Toast.LENGTH_SHORT).show();
                return;
            }

            new Thread(() -> {
                try {
                    com.termux.app.HermesInstaller.runShellCommand(
                            "sed -i 's/^Port .*/Port " + port + "/' "
                                    + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/ssh/sshd_config");
                    // Restart sshd if running to apply new port
                    com.termux.app.HermesInstaller.runShellCommand(
                            "pgrep -x sshd >/dev/null 2>&1 && (pkill sshd; sleep 1; "
                                    + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd) || true");
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateSshStatus());
                    }
                } catch (Exception ignored) {}
            }).start();
        }

        private void updateSshPassword(String newPassword) {
            new Thread(() -> {
                try {
                    String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                    String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

                    // Read password from stdin to avoid shell injection
                    String cmd = "read -r PWD_IN; "
                            + "HASH=$(" + prefix + "/bin/openssl passwd -6 -stdin <<< \"$PWD_IN\" 2>/dev/null); "
                            + "USER=$(whoami 2>/dev/null); "
                            + "if [ -n \"$HASH\" ] && [ -n \"$USER\" ]; then "
                            + "  if grep -q \"^${USER}:\" " + prefix + "/etc/passwd 2>/dev/null; then "
                            + "    sed -i \"s|^${USER}:[^:]*:|${USER}:${HASH}:|\" " + prefix + "/etc/passwd; "
                            + "  fi; "
                            + "fi; echo ok";

                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", cmd);
                    pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
                    pb.environment().put("PREFIX", prefix);
                    pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                    String pathRewrite = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                    if (new File(pathRewrite).exists()) {
                        pb.environment().put("LD_PRELOAD", pathRewrite);
                    }
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    // Pass password via stdin
                    p.getOutputStream().write((newPassword + "\n").getBytes("UTF-8"));
                    p.getOutputStream().flush();
                    p.getOutputStream().close();
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String lastLine = null;
                    String line;
                    while ((line = reader.readLine()) != null) lastLine = line;
                    p.waitFor();

                    boolean success = "ok".equals(lastLine);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(),
                                    success ? R.string.ssh_password_changed : R.string.ssh_password_change_failed,
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                } catch (Exception e) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), R.string.ssh_password_change_failed, Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            }).start();
        }

        private void showSshInfo() {
            try {
                String port = getSshPort();
                int portNum;
                try { portNum = Integer.parseInt(port); } catch (NumberFormatException e) { portNum = 8022; }
                String user = "hermes";
                // Try to get actual username
                File passwd = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/passwd");
                if (passwd.exists()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(passwd));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(":") && !line.startsWith("#")) {
                            user = line.substring(0, line.indexOf(":"));
                            break;
                        }
                    }
                }
                String message = getString(R.string.ssh_info_dialog_message, portNum, user);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.ssh_info_dialog_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.ssh_start_failed, Toast.LENGTH_SHORT).show();
            }
        }

        private void showPreLaunchChecks() {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
            builder.setTitle(R.string.prelaunch_title);

            LinearLayout checklist = new LinearLayout(requireContext());
            checklist.setOrientation(LinearLayout.VERTICAL);
            checklist.setPadding(dp(24), dp(16), dp(24), dp(8));

            java.util.List<String> errors = new java.util.ArrayList<>();
            java.util.List<String> warnings = new java.util.ArrayList<>();

            // Check 1: Hermes installation
            boolean installed = new java.io.File(
                    com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH + "/hermes-installed").exists();
            addCheckItem(checklist, getString(R.string.prelaunch_check_install),
                    installed, getString(R.string.prelaunch_fix_install));
            if (!installed) errors.add(getString(R.string.prelaunch_check_install));

            // Check 2: LLM API key
            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            boolean hasApiKey = !apiKey.isEmpty() || "ollama".equals(provider);
            addCheckItem(checklist, getString(R.string.prelaunch_check_api_key),
                    hasApiKey, getString(R.string.prelaunch_fix_api_key));
            if (!hasApiKey) errors.add(getString(R.string.prelaunch_check_api_key));

            // Check 3: LLM model selected
            String model = mConfigManager.getModelName();
            boolean hasModel = !model.isEmpty();
            addCheckItem(checklist, getString(R.string.prelaunch_check_model),
                    hasModel, getString(R.string.prelaunch_fix_model));
            if (!hasModel) errors.add(getString(R.string.prelaunch_check_model));

            // Check 4: At least one IM platform
            boolean hasIm = mConfigManager.isFeishuConfigured()
                    || !mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()
                    || !mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()
                    || !mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty();
            addCheckItem(checklist, getString(R.string.prelaunch_check_im),
                    hasIm, getString(R.string.prelaunch_fix_im));
            if (!hasIm) warnings.add(getString(R.string.prelaunch_check_im));

            // Check 5: System prompt
            String prompt = mConfigManager.getSystemPrompt();
            boolean hasPrompt = prompt != null && !prompt.isEmpty();
            addCheckItem(checklist, getString(R.string.prelaunch_check_prompt),
                    hasPrompt, getString(R.string.prelaunch_fix_prompt));
            if (!hasPrompt) warnings.add(getString(R.string.prelaunch_check_prompt));

            // Summary
            TextView summary = new TextView(requireContext());
            summary.setPadding(0, dp(12), 0, 0);
            if (errors.isEmpty() && warnings.isEmpty()) {
                summary.setText(R.string.prelaunch_all_pass);
                summary.setTextColor(0xFF388E3C);
            } else if (errors.isEmpty()) {
                summary.setText(getString(R.string.prelaunch_warnings, warnings.size()));
                summary.setTextColor(0xFFF57C00);
            } else {
                summary.setText(getString(R.string.prelaunch_errors, errors.size()));
                summary.setTextColor(0xFFD32F2F);
            }
            summary.setTextSize(14);
            checklist.addView(summary);

            builder.setView(checklist);

            if (errors.isEmpty()) {
                builder.setPositiveButton(R.string.gateway_start_title, (d, w) -> runGatewayCommand("start"));
            }
            builder.setNegativeButton(android.R.string.cancel, null);

            if (!errors.isEmpty()) {
                builder.setPositiveButton(R.string.prelaunch_fix, (d, w) -> {
                    // Navigate to the first error's fix location
                    Intent intent = new Intent(requireContext(), HermesConfigActivity.class);
                    intent.putExtra("tab", "llm");
                    startActivity(intent);
                });
            }

            builder.show();
        }

        private void addCheckItem(LinearLayout parent, String label, boolean pass, String fixHint) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView icon = new TextView(requireContext());
            icon.setText(pass ? "✔" : "✘");
            icon.setTextColor(pass ? 0xFF388E3C : 0xFFD32F2F);
            icon.setTextSize(18);
            icon.setPadding(0, 0, dp(12), 0);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView labelView = new TextView(requireContext());
            labelView.setText(label);
            labelView.setTextSize(14);
            textCol.addView(labelView);

            if (!pass) {
                TextView hint = new TextView(requireContext());
                hint.setText(fixHint);
                hint.setTextSize(12);
                hint.setTextColor(0xFF888888);
                textCol.addView(hint);
            }

            row.addView(textCol);
            parent.addView(row);
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density);
        }
    }

    public static class AgentSettingsFragment extends PreferenceFragmentCompat {
        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_agent_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // Context compression
            SwitchPreferenceCompat compressionPref = findPreference("agent_compression_enabled");
            if (compressionPref != null) {
                String val = mConfigManager.getYamlValue("compression.enabled", "true");
                compressionPref.setChecked(!"false".equals(val));
                compressionPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("compression.enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Compression threshold
            EditTextPreference thresholdPref = findPreference("agent_compression_threshold");
            if (thresholdPref != null) {
                String threshold = mConfigManager.getYamlValue("compression.threshold", "");
                if (!threshold.isEmpty()) thresholdPref.setText(threshold);
                thresholdPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("compression.threshold", (String) newVal);
                    return true;
                });
            }

            // Context length
            EditTextPreference contextLenPref = findPreference("agent_context_length");
            if (contextLenPref != null) {
                String ctxLen = mConfigManager.getYamlValue("context_length", "");
                if (!ctxLen.isEmpty()) contextLenPref.setText(ctxLen);
                contextLenPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("context_length", (String) newVal);
                    return true;
                });
            }

            // Max turns
            EditTextPreference maxTurnsPref = findPreference("agent_max_turns");
            if (maxTurnsPref != null) {
                String maxTurns = mConfigManager.getYamlValue("agent.max_turns", "");
                if (!maxTurns.isEmpty()) maxTurnsPref.setText(maxTurns);
                maxTurnsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.max_turns", (String) newVal);
                    return true;
                });
            }

            // Gateway timeout
            EditTextPreference timeoutPref = findPreference("agent_gateway_timeout");
            if (timeoutPref != null) {
                String timeout = mConfigManager.getYamlValue("agent.gateway_timeout", "");
                if (!timeout.isEmpty()) timeoutPref.setText(timeout);
                timeoutPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.gateway_timeout", (String) newVal);
                    return true;
                });
            }

            // Verbose
            SwitchPreferenceCompat verbosePref = findPreference("agent_verbose");
            if (verbosePref != null) {
                String val = mConfigManager.getYamlValue("agent.verbose", "false");
                verbosePref.setChecked("true".equals(val));
                verbosePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.verbose", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Memory enabled
            SwitchPreferenceCompat memoryPref = findPreference("agent_memory_enabled");
            if (memoryPref != null) {
                String val = mConfigManager.getYamlValue("memory.memory_enabled", "true");
                memoryPref.setChecked(!"false".equals(val));
                memoryPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.memory_enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // User profile
            SwitchPreferenceCompat profilePref = findPreference("agent_user_profile_enabled");
            if (profilePref != null) {
                String val = mConfigManager.getYamlValue("memory.user_profile_enabled", "true");
                profilePref.setChecked(!"false".equals(val));
                profilePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.user_profile_enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Memory char limit
            EditTextPreference memLimitPref = findPreference("agent_memory_char_limit");
            if (memLimitPref != null) {
                String limit = mConfigManager.getYamlValue("memory.memory_char_limit", "");
                if (!limit.isEmpty()) memLimitPref.setText(limit);
                memLimitPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.memory_char_limit", (String) newVal);
                    return true;
                });
            }

            // Session reset mode
            ListPreference resetModePref = findPreference("agent_session_reset_mode");
            if (resetModePref != null) {
                String mode = mConfigManager.getYamlValue("session_reset.mode", "none");
                resetModePref.setValue(mode);
                resetModePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.mode", (String) newVal);
                    return true;
                });
            }

            // Session idle minutes
            EditTextPreference idlePref = findPreference("agent_session_idle_minutes");
            if (idlePref != null) {
                String idle = mConfigManager.getYamlValue("session_reset.idle_minutes", "");
                if (!idle.isEmpty()) idlePref.setText(idle);
                idlePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.idle_minutes", (String) newVal);
                    return true;
                });
            }

            // Session reset hour
            EditTextPreference hourPref = findPreference("agent_session_reset_hour");
            if (hourPref != null) {
                String hour = mConfigManager.getYamlValue("session_reset.at_hour", "");
                if (!hour.isEmpty()) hourPref.setText(hour);
                hourPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.at_hour", (String) newVal);
                    return true;
                });
            }

            // Tool management toggles
            String[][] toolKeys = {
                    {"agent_tool_terminal", "tools.terminal"},
                    {"agent_tool_web_search", "tools.web_search"},
                    {"agent_tool_file_ops", "tools.file_operations"},
                    {"agent_tool_browser", "tools.browser"},
                    {"agent_tool_code_exec", "tools.code_execution"}
            };
            for (String[] tool : toolKeys) {
                SwitchPreferenceCompat toolPref = findPreference(tool[0]);
                if (toolPref != null) {
                    String val = mConfigManager.getYamlValue(tool[1], "true");
                    toolPref.setChecked(!"false".equals(val));
                    toolPref.setOnPreferenceChangeListener((p, newVal) -> {
                        mConfigManager.setYamlValue(tool[1], (Boolean) newVal ? "true" : "false");
                        return true;
                    });
                }
            }

            // Browser tool configuration
            EditTextPreference browserTimeoutPref = findPreference("browser_inactivity_timeout");
            if (browserTimeoutPref != null) {
                String timeout = mConfigManager.getYamlValue("browser.inactivity_timeout", "");
                if (!timeout.isEmpty()) browserTimeoutPref.setText(timeout);
                browserTimeoutPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("browser.inactivity_timeout", (String) newVal);
                    return true;
                });
            }

            EditTextPreference browserUaPref = findPreference("browser_user_agent");
            if (browserUaPref != null) {
                String ua = mConfigManager.getYamlValue("browser.user_agent", "");
                if (!ua.isEmpty()) browserUaPref.setText(ua);
                browserUaPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("browser.user_agent", (String) newVal);
                    return true;
                });
            }

            SwitchPreferenceCompat browserHeadlessPref = findPreference("browser_headless");
            if (browserHeadlessPref != null) {
                String val = mConfigManager.getYamlValue("browser.headless", "true");
                browserHeadlessPref.setChecked(!"false".equals(val));
                browserHeadlessPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("browser.headless", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            EditTextPreference browserMaxTabsPref = findPreference("browser_max_tabs");
            if (browserMaxTabsPref != null) {
                String maxTabs = mConfigManager.getYamlValue("browser.max_tabs", "");
                if (!maxTabs.isEmpty()) browserMaxTabsPref.setText(maxTabs);
                browserMaxTabsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("browser.max_tabs", (String) newVal);
                    return true;
                });
            }

            // Voice transcription configuration
            ListPreference voiceProviderPref = findPreference("voice_provider");
            if (voiceProviderPref != null) {
                String provider = mConfigManager.getVoiceProvider();
                voiceProviderPref.setValue(provider);
                voiceProviderPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setVoiceProvider((String) newVal);
                    return true;
                });
            }

            // Rate limiting configuration
            EditTextPreference rateUserPref = findPreference("rate_limit_user_per_min");
            if (rateUserPref != null) {
                int limit = mConfigManager.getRateLimitUserPerMinute();
                rateUserPref.setText(String.valueOf(limit));
                rateUserPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setRateLimitUserPerMinute(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            EditTextPreference voiceLangPref = findPreference("voice_language");
            if (voiceLangPref != null) {
                String lang = mConfigManager.getVoiceLanguage();
                if (!lang.isEmpty()) voiceLangPref.setText(lang);
                voiceLangPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setVoiceLanguage((String) newVal);
                    return true;
                });
            }

            EditTextPreference rateGlobalPref = findPreference("rate_limit_global_per_min");
            if (rateGlobalPref != null) {
                int limit = mConfigManager.getRateLimitGlobalPerMinute();
                rateGlobalPref.setText(String.valueOf(limit));
                rateGlobalPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setRateLimitGlobalPerMinute(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            ListPreference voiceModelPref = findPreference("voice_local_model");
            if (voiceModelPref != null) {
                String model = mConfigManager.getVoiceLocalModel();
                voiceModelPref.setValue(model);
                voiceModelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setVoiceLocalModel((String) newVal);
                    return true;
                });
            }

            EditTextPreference rateCooldownPref = findPreference("rate_limit_cooldown");
            if (rateCooldownPref != null) {
                int cooldown = mConfigManager.getRateLimitCooldown();
                rateCooldownPref.setText(String.valueOf(cooldown));
                rateCooldownPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setRateLimitCooldown(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            EditTextPreference voiceEndpointPref = findPreference("voice_custom_endpoint");
            if (voiceEndpointPref != null) {
                String endpoint = mConfigManager.getVoiceCustomEndpoint();
                if (!endpoint.isEmpty()) voiceEndpointPref.setText(endpoint);
                voiceEndpointPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setVoiceCustomEndpoint((String) newVal);
                    return true;
                });
            }

            EditTextPreference rateConcPref = findPreference("rate_limit_concurrent");
            if (rateConcPref != null) {
                int concurrent = mConfigManager.getRateLimitConcurrent();
                rateConcPref.setText(String.valueOf(concurrent));
                rateConcPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setRateLimitConcurrent(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            ListPreference rateQueuePref = findPreference("rate_limit_queue_mode");
            if (rateQueuePref != null) {
                String mode = mConfigManager.getRateLimitQueueMode();
                rateQueuePref.setValue(mode);
                rateQueuePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setRateLimitQueueMode((String) newVal);
                    return true;
                });
            }

            // Logging configuration
            ListPreference logLevelPref = findPreference("logging_level");
            if (logLevelPref != null) {
                logLevelPref.setValue(mConfigManager.getLoggingLevel());
                logLevelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setLoggingLevel((String) newVal);
                    return true;
                });
            }

            SwitchPreferenceCompat logFilePref = findPreference("logging_to_file");
            if (logFilePref != null) {
                logFilePref.setChecked(mConfigManager.isLoggingToFile());
                logFilePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setLoggingToFile((Boolean) newVal);
                    return true;
                });
            }

            EditTextPreference logSizePref = findPreference("logging_max_file_size");
            if (logSizePref != null) {
                logSizePref.setText(String.valueOf(mConfigManager.getLoggingMaxFileSize()));
                logSizePref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setLoggingMaxFileSize(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            EditTextPreference logFilesPref = findPreference("logging_max_files");
            if (logFilesPref != null) {
                logFilesPref.setText(String.valueOf(mConfigManager.getLoggingMaxFiles()));
                logFilesPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try { mConfigManager.setLoggingMaxFiles(Integer.parseInt((String) newVal)); } catch (NumberFormatException ignored) {}
                    return true;
                });
            }
        }
    }
}
