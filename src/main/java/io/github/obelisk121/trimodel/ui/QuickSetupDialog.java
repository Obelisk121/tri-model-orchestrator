package io.github.obelisk121.trimodel.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.JBUI;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;
import io.github.obelisk121.trimodel.provider.CliModelClient;
import io.github.obelisk121.trimodel.provider.EnvironmentAutoDetector;
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelClientFactory;
import io.github.obelisk121.trimodel.provider.ModelDiscoveryService;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelConfigurable;
import io.github.obelisk121.trimodel.settings.TriModelSettings;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple one-screen setup dialog so users can authorize Claude, ChatGPT, and Gemini
 * in two clicks via Browser Sign-In (official CLI — zero Client ID required) or by pasting an API key,
 * and pick live available models fetched directly from each provider's API or CLI.
 */
public final class QuickSetupDialog extends DialogWrapper {

    private static final String MODE_BROWSER = "Browser Sign-In";
    private static final String MODE_API_KEY = "API Key";

    private final Project project;
    private final Map<ModelProviderType, ProviderRow> rows = new EnumMap<>(ModelProviderType.class);

    public QuickSetupDialog(Project project) {
        super(project, true);
        this.project = project;
        setTitle("Sign In & Setup — Claude, ChatGPT, Gemini");
        setOKButtonText("Save && Done");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setPreferredSize(new Dimension(820, 560));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        JBLabel intro = new JBLabel(
                "<html>Connect all three models on one screen: choose <b>Browser Sign-In</b> (uses official "
                        + "<code>claude</code>, <code>codex</code>, and Google Antigravity <code>agy</code> CLIs — no Client ID needed) "
                        + "or paste an <b>API Key</b>. Available models are fetched live from each provider.</html>");
        top.add(intro, BorderLayout.CENTER);

        JPanel quickBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton autoDetectBtn = new JButton("Auto-Detect All");
        JButton refreshAllModelsBtn = new JButton("Refresh All Models");
        JButton advancedBtn = new JButton("Advanced Settings...");
        JBLabel osBadgeLabel = new JBLabel(EnvironmentAutoDetector.formatOsLabel(
                System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
        osBadgeLabel.setForeground(JBColor.GRAY);

        autoDetectBtn.addActionListener(e -> runAutoDetectAll(autoDetectBtn, osBadgeLabel));
        refreshAllModelsBtn.addActionListener(e -> {
            for (ProviderRow row : rows.values()) {
                row.refreshModelsAsync(true);
            }
        });
        advancedBtn.addActionListener(e -> {
            saveChoices();
            close(OK_EXIT_CODE);
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TriModelConfigurable.class);
        });
        quickBar.add(autoDetectBtn);
        quickBar.add(refreshAllModelsBtn);
        quickBar.add(advancedBtn);
        quickBar.add(osBadgeLabel);
        top.add(quickBar, BorderLayout.SOUTH);

        JPanel cardsContainer = new JPanel();
        cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
        cardsContainer.setBorder(JBUI.Borders.empty(4, 0));

        TriModelSettings settings = TriModelSettings.getInstance();
        rows.clear();
        for (ModelProviderType type : ModelProviderType.values()) {
            ProviderRow row = new ProviderRow(type, settings.getProviderConfig(type), SecretStorageService.getStoredApiKey(type));
            rows.put(type, row);
            cardsContainer.add(row.panel);
            cardsContainer.add(Box.createVerticalStrut(8));
        }

        root.add(top, BorderLayout.NORTH);
        root.add(cardsContainer, BorderLayout.CENTER);
        if (!settings.isAutoDetectedOnce()) {
            runAutoDetectAll(autoDetectBtn, osBadgeLabel);
        }
        return root;
    }

    private void runAutoDetectAll(JButton triggerBtn, JBLabel statusLbl) {
        triggerBtn.setEnabled(false);
        statusLbl.setForeground(JBColor.foreground());
        statusLbl.setText("Detecting CLIs, env keys & live models...");

        Map<ModelProviderType, ProviderConfig> currentConfigs = new EnumMap<>(ModelProviderType.class);
        Map<ModelProviderType, String> apiKeys = new EnumMap<>(ModelProviderType.class);
        for (Map.Entry<ModelProviderType, ProviderRow> entry : rows.entrySet()) {
            currentConfigs.put(entry.getKey(), entry.getValue().buildUpdatedConfig());
            apiKeys.put(entry.getKey(), entry.getValue().getApiKey());
        }

        Runnable task = () -> {
            EnvironmentAutoDetector.EnvironmentReport report = new EnvironmentAutoDetector()
                    .detectAll(currentConfigs, apiKeys, true, () -> false);
            SwingUtilities.invokeLater(() -> {
                triggerBtn.setEnabled(true);
                statusLbl.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                statusLbl.setText(report.summary());
                for (Map.Entry<ModelProviderType, EnvironmentAutoDetector.ProviderDetection> e : report.providers().entrySet()) {
                    ProviderRow row = rows.get(e.getKey());
                    if (row != null) {
                        row.applyAutoDetection(e.getValue());
                    }
                }
            });
        };
        if (ApplicationManager.getApplication() != null) {
            ApplicationManager.getApplication().executeOnPooledThread(task);
        } else {
            new Thread(task, "TriModel-QuickAutoDetect").start();
        }
    }

    @Override
    protected void doOKAction() {
        saveChoices();
        super.doOKAction();
    }

    private void saveChoices() {
        TriModelSettings settings = TriModelSettings.getInstance();
        for (ProviderRow row : rows.values()) {
            ProviderConfig updated = row.buildUpdatedConfig();
            settings.setProviderConfig(row.type, updated);
            if (updated.connectionMode == ConnectionMode.REST_API) {
                SecretStorageService.saveApiKey(row.type, row.getApiKey());
            }
        }
        settings.notifySettingsChanged();
    }

    private static final class ProviderRow {
        final ModelProviderType type;
        ProviderConfig existingConfig;
        final JBCheckBox enabledCheck;
        boolean suppressingModeEvents = false;
        final ComboBox<String> simpleModeCombo = new ComboBox<>(new String[]{MODE_BROWSER, MODE_API_KEY});
        final CardLayout cardLayout = new CardLayout();
        final JPanel modeCards = new JPanel(cardLayout);

        // Browser Sign-In card
        final JBLabel cliStatusLabel = new JBLabel();
        final JButton signInBrowserBtn = new JButton("Sign In in Browser");
        final JButton signOutCliBtn = new JButton("Sign Out");
        final JButton copyInstallBtn = new JButton("Copy Install Cmd");

        // API Key card
        final JBPasswordField apiKeyField = new JBPasswordField();
        final JButton getKeyBtn = new JButton("Get Key ↗");

        // Live API/CLI Model selection row
        final ComboBox<String> modelCombo = new ComboBox<>();
        final JButton refreshModelsBtn = new JButton("Refresh");
        final JBLabel modelSourceLabel = new JBLabel(" ");
        final AtomicInteger modelFetchGen = new AtomicInteger(0);

        // Shared feedback
        final JButton testBtn = new JButton("Test");
        final JBLabel feedbackLabel = new JBLabel(" ");
        final JPanel panel;

        ProviderRow(ModelProviderType type, ProviderConfig cfg, String storedKey) {
            this.type = type;
            this.existingConfig = cfg.copy();
            this.enabledCheck = new JBCheckBox(type.getFullName(), cfg.enabled);
            this.enabledCheck.setFont(enabledCheck.getFont().deriveFont(Font.BOLD, 13f));
            this.enabledCheck.setForeground(new JBColor(
                    Color.decode(type.getLightAccentHex()), Color.decode(type.getDarkAccentHex())));

            // Prevent "Browser Sign-In" from clipping in the combo box
            this.simpleModeCombo.setPrototypeDisplayValue("Browser Sign-In   ");
            this.modelCombo.setEditable(true);
            this.modelCombo.setPrototypeDisplayValue("gemini-3.5-flash-preview-0000");

            String initialModel = cfg.effectiveModelId(type);
            if (!initialModel.isBlank()) {
                this.modelCombo.addItem(initialModel);
                this.modelCombo.setSelectedItem(initialModel);
            }

            // Browser Sign-In card
            JPanel browserCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            browserCard.add(signInBrowserBtn);
            browserCard.add(signOutCliBtn);
            browserCard.add(copyInstallBtn);
            browserCard.add(cliStatusLabel);

            // API Key card
            JPanel apiCard = new JPanel(new BorderLayout(6, 0));
            apiKeyField.setText(storedKey == null ? "" : storedKey);
            apiKeyField.getEmptyText().setText("Paste " + type.getShortName() + " API key (stored in Keychain)");
            apiCard.add(apiKeyField, BorderLayout.CENTER);
            apiCard.add(getKeyBtn, BorderLayout.EAST);

            modeCards.add(browserCard, MODE_BROWSER);
            modeCards.add(apiCard, MODE_API_KEY);

            // Live model selection bar
            JPanel modelRow = new JPanel(new BorderLayout(6, 0));
            modelRow.setBorder(JBUI.Borders.emptyTop(2));
            JPanel modelLeft = new JPanel(new BorderLayout(6, 0));
            modelLeft.add(new JBLabel("Model:"), BorderLayout.WEST);
            modelLeft.add(modelCombo, BorderLayout.CENTER);
            JPanel modelRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            modelSourceLabel.setForeground(JBColor.GRAY);
            modelSourceLabel.setFont(modelSourceLabel.getFont().deriveFont(11f));
            modelRight.add(refreshModelsBtn);
            modelRight.add(modelSourceLabel);
            modelRow.add(modelLeft, BorderLayout.CENTER);
            modelRow.add(modelRight, BorderLayout.EAST);

            JPanel centerBody = new JPanel(new BorderLayout(0, 4));
            centerBody.add(modeCards, BorderLayout.NORTH);
            centerBody.add(modelRow, BorderLayout.SOUTH);

            // Top row inside card
            JPanel topRow = new JPanel(new BorderLayout(8, 0));
            topRow.add(enabledCheck, BorderLayout.WEST);

            JPanel modeAndTest = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            modeAndTest.add(simpleModeCombo);
            modeAndTest.add(testBtn);
            topRow.add(modeAndTest, BorderLayout.EAST);

            feedbackLabel.setForeground(JBColor.GRAY);

            panel = new JPanel(new BorderLayout(0, 6));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(8, 10)
            ));
            panel.add(topRow, BorderLayout.NORTH);
            panel.add(centerBody, BorderLayout.CENTER);
            panel.add(feedbackLabel, BorderLayout.SOUTH);

            // Smart initial selection:
            // If already configured as LOCAL_CLI, or no API key saved and CLI is already installed -> Browser Sign-In!
            boolean cliInstalled = CliModelClient.isCliInstalled(type, cfg.effectiveCliExecutable(type));
            boolean hasKey = (storedKey != null && !storedKey.isBlank()) || SecretStorageService.hasEnvironmentKey(type);
            if (cfg.connectionMode == ConnectionMode.LOCAL_CLI || (!hasKey && cliInstalled && cfg.connectionMode != ConnectionMode.OAUTH_BROWSER)) {
                simpleModeCombo.setSelectedItem(MODE_BROWSER);
            } else {
                simpleModeCombo.setSelectedItem(MODE_API_KEY);
            }

            refreshCliDetection();
            switchCard();
            refreshModelsAsync(false);

            simpleModeCombo.addActionListener(e -> {
                switchCard();
                if (!suppressingModeEvents) {
                    refreshModelsAsync(false);
                }
            });
            getKeyBtn.addActionListener(e -> BrowserUtil.browse(type.getApiKeyConsoleUrl()));
            copyInstallBtn.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(type.getCliInstallHint()), null);
                setFeedback(JBColor.foreground(), "Copied install command: " + type.getCliInstallHint());
            });
            signInBrowserBtn.addActionListener(e -> runCliLogin());
            signOutCliBtn.addActionListener(e -> runCliLogout());
            refreshModelsBtn.addActionListener(e -> refreshModelsAsync(true));
            testBtn.addActionListener(e -> runQuickTest());
        }

        private void switchCard() {
            String selected = (String) simpleModeCombo.getSelectedItem();
            cardLayout.show(modeCards, selected == null ? MODE_BROWSER : selected);
        }

        private void refreshCliDetection() {
            String exe = existingConfig.effectiveCliExecutable(type);
            String resolved = CliModelClient.resolveExecutable(exe);
            if (resolved != null) {
                cliStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                cliStatusLabel.setText("✓ " + exe + " CLI ready");
                signInBrowserBtn.setEnabled(true);
                signOutCliBtn.setVisible(CliModelClient.supportsCliLogout(type, exe));
                copyInstallBtn.setVisible(false);

                // Asynchronously probe actual signed-in status without blocking EDT
                runAsync(() -> {
                    CliModelClient.CliAuthStatus status = CliModelClient.checkCliAuthStatus(type, exe);
                    SwingUtilities.invokeLater(() -> {
                        if (!status.installed()) return;
                        switch (status.state()) {
                            case SIGNED_IN -> {
                                cliStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                                cliStatusLabel.setText("✓ " + status.detail());
                            }
                            case SIGNED_OUT -> {
                                cliStatusLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                                cliStatusLabel.setText("⚠ " + status.detail());
                            }
                            default -> {
                                cliStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                                cliStatusLabel.setText("✓ " + status.detail());
                            }
                        }
                        cliStatusLabel.setToolTipText(status.resolvedPath());
                    });
                }, "TriModel-CliStatus-" + type.name());
            } else {
                cliStatusLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                cliStatusLabel.setText(exe + " CLI not installed");
                signInBrowserBtn.setEnabled(false);
                signOutCliBtn.setVisible(false);
                copyInstallBtn.setVisible(true);
            }
        }

        private void refreshModelsAsync(boolean forceRefresh) {
            int gen = modelFetchGen.incrementAndGet();
            ProviderConfig cfg = buildUpdatedConfig();
            String key = getApiKey();

            if (cfg.connectionMode == ConnectionMode.LOCAL_CLI
                    && !CliModelClient.isCliInstalled(type, cfg.effectiveCliExecutable(type))) {
                modelSourceLabel.setForeground(JBColor.GRAY);
                modelSourceLabel.setText("CLI not installed");
                return;
            }
            if (cfg.connectionMode == ConnectionMode.REST_API
                    && key.isBlank() && !SecretStorageService.hasEnvironmentKey(type)) {
                modelSourceLabel.setForeground(JBColor.GRAY);
                modelSourceLabel.setText("Paste API key, then Refresh");
                return;
            }

            refreshModelsBtn.setEnabled(false);
            modelSourceLabel.setForeground(JBColor.GRAY);
            modelSourceLabel.setText("Loading...");

            runAsync(() -> {
                ModelDiscoveryService discovery = new ModelDiscoveryService();
                ModelDiscoveryService.DiscoveryResult result = discovery.fetchAvailableModels(
                        type, cfg, key, forceRefresh, () -> modelFetchGen.get() != gen);
                SwingUtilities.invokeLater(() -> {
                    if (modelFetchGen.get() != gen) return;
                    refreshModelsBtn.setEnabled(true);
                    if (result.success() && !result.models().isEmpty()) {
                        applyDiscoveredModels(result);
                    } else {
                        modelSourceLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                        String err = result.errorMessage();
                        modelSourceLabel.setToolTipText(err);
                        modelSourceLabel.setText(truncate(err.isBlank() ? "Unavailable" : err, 34));
                    }
                });
            }, "TriModel-Discover-" + type.name());
        }

        private void applyDiscoveredModels(ModelDiscoveryService.DiscoveryResult result) {
            String previous = getSelectedModelId();
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (ModelDiscoveryService.DiscoveredModel m : result.models()) {
                model.addElement(m.id());
            }
            modelCombo.setModel(model);

            if (!previous.isBlank() && result.containsModelId(previous)) {
                modelCombo.setSelectedItem(previous);
            } else if (!result.models().isEmpty()) {
                // Always select an actually available model returned by the live API/CLI
                modelCombo.setSelectedItem(result.models().get(0).id());
            }

            modelSourceLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
            String summary = result.models().size() + " live (" + result.source() + ")";
            modelSourceLabel.setText(truncate(summary, 36));
            modelSourceLabel.setToolTipText(summary);
        }

        void applyAutoDetection(EnvironmentAutoDetector.ProviderDetection det) {
            if (det == null) return;
            ProviderConfig suggested = det.suggestedConfig();
            this.existingConfig = suggested.copy();
            suppressingModeEvents = true;
            try {
                enabledCheck.setSelected(suggested.enabled);
                simpleModeCombo.setSelectedItem(
                        suggested.connectionMode == ConnectionMode.LOCAL_CLI ? MODE_BROWSER : MODE_API_KEY);
                switchCard();
            } finally {
                suppressingModeEvents = false;
            }

            CliModelClient.CliAuthStatus status = det.cliStatus();
            if (status != null && status.installed()) {
                String exe = suggested.effectiveCliExecutable(type);
                signInBrowserBtn.setEnabled(true);
                signOutCliBtn.setVisible(CliModelClient.supportsCliLogout(type, exe));
                copyInstallBtn.setVisible(false);
                cliStatusLabel.setToolTipText(status.resolvedPath());
                if (status.state() == CliModelClient.AuthState.SIGNED_OUT) {
                    cliStatusLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                    cliStatusLabel.setText("⚠ " + status.detail());
                } else {
                    cliStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                    cliStatusLabel.setText("✓ " + status.detail());
                }
            } else {
                refreshCliDetection();
            }

            if (det.discovery() != null && det.discovery().success() && !det.discovery().models().isEmpty()) {
                applyDiscoveredModels(det.discovery());
                if (suggested.modelId != null && !suggested.modelId.isBlank()) {
                    modelCombo.setSelectedItem(suggested.modelId);
                }
            }
            setFeedback(det.ready() ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.GRAY,
                    (det.ready() ? "✓ Auto-detected: " : "Detected: ") + det.summary());
        }

        private void runCliLogin() {
            signInBrowserBtn.setEnabled(false);
            setFeedback(JBColor.foreground(), "Opening default browser for " + type.getShortName() + " sign-in...");
            runAsync(() -> {
                String msg = CliModelClient.launchOfficialCliLogin(
                        type,
                        existingConfig.effectiveCliExecutable(type),
                        s -> SwingUtilities.invokeLater(() -> setFeedback(JBColor.foreground(), s))
                );
                SwingUtilities.invokeLater(() -> {
                    signInBrowserBtn.setEnabled(true);
                    boolean ok = msg.contains("successfully");
                    setFeedback(ok ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.foreground(), msg);
                    refreshCliDetection();
                    if (ok) {
                        refreshModelsAsync(true);
                    }
                });
            }, "TriModel-CliLogin-" + type.name());
        }

        private void runCliLogout() {
            signOutCliBtn.setEnabled(false);
            runAsync(() -> {
                String msg = CliModelClient.launchOfficialCliLogout(type, existingConfig.effectiveCliExecutable(type));
                SwingUtilities.invokeLater(() -> {
                    signOutCliBtn.setEnabled(true);
                    setFeedback(JBColor.foreground(), msg);
                    refreshCliDetection();
                    modelCombo.setModel(new DefaultComboBoxModel<>());
                    modelSourceLabel.setForeground(JBColor.GRAY);
                    modelSourceLabel.setText("Signed out");
                });
            }, "TriModel-CliLogout-" + type.name());
        }

        private void runQuickTest() {
            ProviderConfig cfg = buildUpdatedConfig();
            String key = getApiKey();
            testBtn.setEnabled(false);
            setFeedback(JBColor.foreground(), "Testing " + type.getShortName() + "...");

            runAsync(() -> {
                ModelClient client = ModelClientFactory.createClient(type, cfg, key);
                ModelResponse resp = client.generate(
                        new ModelRequest("Reply with exactly: OK", "Health check. Reply OK.", List.of(), 64, 30),
                        () -> false
                );
                SwingUtilities.invokeLater(() -> {
                    testBtn.setEnabled(true);
                    if (resp.success()) {
                        setFeedback(new JBColor(0x0F9D78, 0x2AD1A3),
                                "✓ Connected (" + resp.latencyMs() + "ms) — " + resp.modelId());
                        refreshCliDetection();
                        if (modelCombo.getItemCount() == 0) {
                            refreshModelsAsync(true);
                        }
                    } else {
                        setFeedback(JBColor.RED, "✗ " + resp.errorMessage());
                    }
                });
            }, "TriModel-Test-" + type.name());
        }

        private void setFeedback(Color color, String fullMessage) {
            String msg = fullMessage == null ? " " : fullMessage;
            feedbackLabel.setForeground(color);
            feedbackLabel.setToolTipText(msg);
            feedbackLabel.setText(truncate(msg, 115));
        }

        private static String truncate(String s, int max) {
            if (s == null) return "";
            String compact = s.replaceAll("\\s+", " ").trim();
            return compact.length() > max ? compact.substring(0, max) + "..." : compact;
        }

        String getSelectedModelId() {
            Object item = modelCombo.isEditable() ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
            return item == null ? "" : item.toString().trim();
        }

        ProviderConfig buildUpdatedConfig() {
            ProviderConfig cfg = existingConfig.copy();
            cfg.enabled = enabledCheck.isSelected();
            boolean useBrowser = MODE_BROWSER.equals(simpleModeCombo.getSelectedItem());
            cfg.connectionMode = useBrowser ? ConnectionMode.LOCAL_CLI : ConnectionMode.REST_API;
            cfg.modelId = getSelectedModelId();
            return cfg;
        }

        String getApiKey() {
            return new String(apiKeyField.getPassword()).trim();
        }

        private static void runAsync(Runnable task, String threadName) {
            if (ApplicationManager.getApplication() != null) {
                ApplicationManager.getApplication().executeOnPooledThread(task);
            } else {
                new Thread(task, threadName).start();
            }
        }
    }
}
