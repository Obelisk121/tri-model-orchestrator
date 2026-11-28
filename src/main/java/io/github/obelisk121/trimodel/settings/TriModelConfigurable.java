package io.github.obelisk121.trimodel.settings;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import io.github.obelisk121.trimodel.auth.OAuthPkceService;
import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.auth.OAuthToken;
import io.github.obelisk121.trimodel.model.AgentSkill;
import io.github.obelisk121.trimodel.model.AgentSkillConfig;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import io.github.obelisk121.trimodel.provider.CliModelClient;
import io.github.obelisk121.trimodel.provider.EnvironmentAutoDetector;
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelClientFactory;
import io.github.obelisk121.trimodel.provider.ModelDiscoveryService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clean, card-based PyCharm Settings page (Settings -> Tools -> Tri-Model AI Orchestrator).
 * Displays only the fields relevant to the chosen sign-in method and keeps advanced options collapsed.
 */
public final class TriModelConfigurable implements SearchableConfigurable {

    private final Map<ModelProviderType, ProviderEditor> providerEditors = new EnumMap<>(ModelProviderType.class);

    private ComboBox<OrchestrationMode> defaultModeBox;
    private ComboBox<ModelProviderType> architectBox;
    private ComboBox<ModelProviderType> coderBox;
    private ComboBox<ModelProviderType> reviewerBox;
    private ComboBox<ModelProviderType> synthesizerBox;
    private JSpinner maxReviewRoundsSpinner;
    private JBCheckBox councilCrossCritiqueCheck;
    private ComboBox<AgentSkillConfig.Preset> skillPresetBox;
    private JBCheckBox skillNoBlindAgreementCheck;
    private JBCheckBox skillTopicAuditCheck;
    private JBCheckBox skillDetailPrecisionCheck;
    private JBCheckBox skillBlindLabelsCheck;
    private JBCheckBox skillDevilsAdvocateCheck;
    private boolean syncingSkillControls = false;
    private JSpinner timeoutSpinner;
    private JSpinner maxTokensSpinner;
    private JBTextArea customInstructionsArea;

    private JPanel rootPanel;

    @Override
    public @NotNull String getId() {
        return "io.github.obelisk121.trimodel.settings";
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tri-Model AI Orchestrator";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootPanel = new JPanel(new BorderLayout(0, 8));
        rootPanel.setBorder(JBUI.Borders.empty(4));

        JBLabel header = new JBLabel(
                "<html>Choose <b>Browser Sign-In</b> (no API key or Client ID needed) or paste an <b>API Key</b> "
                        + "for Claude, ChatGPT, and Gemini. Prompts go <b>directly</b> to your providers &mdash; zero telemetry.</html>");
        header.setBorder(JBUI.Borders.emptyBottom(6));
        rootPanel.add(header, BorderLayout.NORTH);

        JBTabbedPane tabs = new JBTabbedPane();
        providerEditors.clear();
        for (ModelProviderType type : ModelProviderType.values()) {
            ProviderEditor editor = new ProviderEditor(type);
            providerEditors.put(type, editor);
            JBScrollPane scroll = new JBScrollPane(editor.panel);
            scroll.setBorder(JBUI.Borders.empty());
            tabs.addTab(type.getFullName(), scroll);
        }
        tabs.addTab("Orchestration & Defaults", buildOrchestrationTab());
        rootPanel.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton autoDetectAllBtn = new JButton("Auto-Detect All (" + EnvironmentAutoDetector.formatOsLabel(
                System.getProperty("os.name", ""), System.getProperty("os.arch", "")) + ")");
        JButton testAllBtn = new JButton("Test All Enabled Models");
        JBLabel footerStatus = new JBLabel(" ");

        autoDetectAllBtn.addActionListener(e -> {
            autoDetectAllBtn.setEnabled(false);
            footerStatus.setForeground(JBColor.foreground());
            footerStatus.setText("Auto-detecting CLIs, environment variables & live models...");
            Map<ModelProviderType, ProviderConfig> currentConfigs = new EnumMap<>(ModelProviderType.class);
            Map<ModelProviderType, String> apiKeys = new EnumMap<>(ModelProviderType.class);
            for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
                currentConfigs.put(entry.getKey(), entry.getValue().toConfig());
                apiKeys.put(entry.getKey(), entry.getValue().getApiKeyText());
            }
            ProviderEditor.runAsync(() -> {
                EnvironmentAutoDetector.EnvironmentReport report = new EnvironmentAutoDetector()
                        .detectAll(currentConfigs, apiKeys, true, () -> rootPanel == null);
                SwingUtilities.invokeLater(() -> {
                    if (rootPanel == null) return;
                    autoDetectAllBtn.setEnabled(true);
                    footerStatus.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                    footerStatus.setText(report.summary() + " — click Apply/OK to save.");
                    for (Map.Entry<ModelProviderType, EnvironmentAutoDetector.ProviderDetection> detEntry : report.providers().entrySet()) {
                        ProviderEditor ed = providerEditors.get(detEntry.getKey());
                        if (ed != null) {
                            ed.applyAutoDetection(detEntry.getValue());
                        }
                    }
                });
            }, "TriModel-SettingsAutoDetect");
        });

        testAllBtn.addActionListener(e -> {
            footerStatus.setForeground(JBColor.foreground());
            footerStatus.setText("Testing enabled providers — check each provider tab for results.");
            for (ProviderEditor editor : providerEditors.values()) {
                if (editor.enabledCheck.isSelected()) {
                    editor.runConnectionTest();
                }
            }
        });
        footer.add(autoDetectAllBtn);
        footer.add(testAllBtn);
        footer.add(footerStatus);
        rootPanel.add(footer, BorderLayout.SOUTH);

        reset();
        return rootPanel;
    }

    private JPanel buildOrchestrationTab() {
        defaultModeBox = new ComboBox<>(OrchestrationMode.values());
        architectBox = new ComboBox<>(ModelProviderType.values());
        coderBox = new ComboBox<>(ModelProviderType.values());
        reviewerBox = new ComboBox<>(ModelProviderType.values());
        synthesizerBox = new ComboBox<>(ModelProviderType.values());
        maxReviewRoundsSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        councilCrossCritiqueCheck = new JBCheckBox("Enable Round-2 cross-critique in Parallel Council mode");

        skillPresetBox = new ComboBox<>(AgentSkillConfig.Preset.values());
        skillNoBlindAgreementCheck = new JBCheckBox(AgentSkill.NO_BLIND_AGREEMENT.getDisplayName()
                + " — forbid blind agreement & rubber-stamp approvals");
        skillTopicAuditCheck = new JBCheckBox(AgentSkill.TOPIC_BY_TOPIC_AUDIT.getDisplayName()
                + " — audit every peer topic ([VERIFIED]/[DISPUTED]/[MISSING]/[UNPROVEN])");
        skillDetailPrecisionCheck = new JBCheckBox(AgentSkill.DETAIL_PRECISION.getDisplayName()
                + " — enforce exact symbols, edge cases, exceptions & complexity");
        skillBlindLabelsCheck = new JBCheckBox(AgentSkill.ANONYMIZED_BLIND_REVIEW.getDisplayName()
                + " — hide peer model brand names in handoffs");
        skillDevilsAdvocateCheck = new JBCheckBox(AgentSkill.DEVILS_ADVOCATE.getDisplayName()
                + " — construct breaking inputs & adversarial edge-case traces");

        skillPresetBox.addActionListener(e -> {
            if (syncingSkillControls) return;
            AgentSkillConfig.Preset p = (AgentSkillConfig.Preset) skillPresetBox.getSelectedItem();
            if (p != null && p != AgentSkillConfig.Preset.CUSTOM) {
                loadSkillConfigIntoUI(AgentSkillConfig.forPreset(p));
            }
        });
        Runnable updatePresetFromCheckboxes = () -> {
            if (syncingSkillControls) return;
            syncingSkillControls = true;
            try {
                skillPresetBox.setSelectedItem(readSkillConfigFromUI().detectPreset());
            } finally {
                syncingSkillControls = false;
            }
        };
        skillNoBlindAgreementCheck.addActionListener(e -> updatePresetFromCheckboxes.run());
        skillTopicAuditCheck.addActionListener(e -> updatePresetFromCheckboxes.run());
        skillDetailPrecisionCheck.addActionListener(e -> updatePresetFromCheckboxes.run());
        skillBlindLabelsCheck.addActionListener(e -> updatePresetFromCheckboxes.run());
        skillDevilsAdvocateCheck.addActionListener(e -> updatePresetFromCheckboxes.run());

        timeoutSpinner = new JSpinner(new SpinnerNumberModel(120, 15, 600, 15));
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 512, 65536, 512));

        customInstructionsArea = new JBTextArea(4, 40);
        customInstructionsArea.setLineWrap(true);
        customInstructionsArea.setWrapStyleWord(true);
        JBScrollPane instructionsScroll = new JBScrollPane(customInstructionsArea);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Default orchestration mode:", defaultModeBox)
                .addSeparator(8)
                .addLabeledComponent("Agent independence & precision profile:", skillPresetBox)
                .addComponent(skillNoBlindAgreementCheck)
                .addComponent(skillTopicAuditCheck)
                .addComponent(skillDetailPrecisionCheck)
                .addComponent(skillBlindLabelsCheck)
                .addComponent(skillDevilsAdvocateCheck)
                .addSeparator(8)
                .addLabeledComponent("Architect / Planner role:", architectBox)
                .addLabeledComponent("Primary Coder role:", coderBox)
                .addLabeledComponent("Reviewer / Critic role:", reviewerBox)
                .addLabeledComponent("Chair / Finalizer role:", synthesizerBox)
                .addSeparator(8)
                .addLabeledComponent("Max iterative review rounds:", maxReviewRoundsSpinner)
                .addComponent(councilCrossCritiqueCheck)
                .addSeparator(8)
                .addLabeledComponent("Request timeout (seconds):", timeoutSpinner)
                .addLabeledComponent("Max output tokens per stage:", maxTokensSpinner)
                .addLabeledComponentFillVertically("Global project/coding instructions (appended to all models):", instructionsScroll)
                .getPanel();

        form.setBorder(JBUI.Borders.empty(10));
        JBScrollPane scroll = new JBScrollPane(form);
        scroll.setBorder(JBUI.Borders.empty());
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private void loadSkillConfigIntoUI(AgentSkillConfig cfg) {
        syncingSkillControls = true;
        try {
            skillNoBlindAgreementCheck.setSelected(cfg.noBlindAgreement);
            skillTopicAuditCheck.setSelected(cfg.topicByTopicAudit);
            skillDetailPrecisionCheck.setSelected(cfg.detailPrecision);
            skillBlindLabelsCheck.setSelected(cfg.anonymizedBlindReview);
            skillDevilsAdvocateCheck.setSelected(cfg.devilsAdvocate);
            skillPresetBox.setSelectedItem(cfg.detectPreset());
        } finally {
            syncingSkillControls = false;
        }
    }

    private AgentSkillConfig readSkillConfigFromUI() {
        AgentSkillConfig cfg = new AgentSkillConfig();
        cfg.noBlindAgreement = skillNoBlindAgreementCheck.isSelected();
        cfg.topicByTopicAudit = skillTopicAuditCheck.isSelected();
        cfg.detailPrecision = skillDetailPrecisionCheck.isSelected();
        cfg.anonymizedBlindReview = skillBlindLabelsCheck.isSelected();
        cfg.devilsAdvocate = skillDevilsAdvocateCheck.isSelected();
        return cfg;
    }

    @Override
    public boolean isModified() {
        TriModelSettings settings = TriModelSettings.getInstance();
        for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
            ModelProviderType type = entry.getKey();
            ProviderEditor ed = entry.getValue();
            if (!Objects.equals(ed.toConfig(), settings.getProviderConfig(type))) {
                return true;
            }
            if (!Objects.equals(ed.getApiKeyText(), SecretStorageService.getStoredApiKey(type))) {
                return true;
            }
            if (!Objects.equals(ed.getOAuthClientSecretText(), SecretStorageService.getOAuthClientSecret(type))) {
                return true;
            }
        }
        RoleAssignment roles = settings.getRoleAssignment();
        return defaultModeBox.getSelectedItem() != settings.getDefaultMode()
                || architectBox.getSelectedItem() != roles.getArchitect()
                || coderBox.getSelectedItem() != roles.getCoder()
                || reviewerBox.getSelectedItem() != roles.getReviewer()
                || synthesizerBox.getSelectedItem() != roles.getSynthesizer()
                || ((Number) maxReviewRoundsSpinner.getValue()).intValue() != settings.getMaxReviewRounds()
                || councilCrossCritiqueCheck.isSelected() != settings.isCouncilCrossCritique()
                || !Objects.equals(readSkillConfigFromUI(), settings.getAgentSkills())
                || ((Number) timeoutSpinner.getValue()).intValue() != settings.getRequestTimeoutSeconds()
                || ((Number) maxTokensSpinner.getValue()).intValue() != settings.getMaxOutputTokens()
                || !Objects.equals(customInstructionsArea.getText().trim(), settings.getCustomSystemInstructions().trim());
    }

    @Override
    public void apply() {
        TriModelSettings settings = TriModelSettings.getInstance();
        for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
            ModelProviderType type = entry.getKey();
            ProviderEditor ed = entry.getValue();
            settings.setProviderConfig(type, ed.toConfig());
            SecretStorageService.saveApiKey(type, ed.getApiKeyText());
            SecretStorageService.saveOAuthClientSecret(type, ed.getOAuthClientSecretText());
        }
        settings.setDefaultMode((OrchestrationMode) defaultModeBox.getSelectedItem());
        settings.setRoleAssignment(new RoleAssignment(
                (ModelProviderType) architectBox.getSelectedItem(),
                (ModelProviderType) coderBox.getSelectedItem(),
                (ModelProviderType) reviewerBox.getSelectedItem(),
                (ModelProviderType) synthesizerBox.getSelectedItem()
        ));
        settings.setMaxReviewRounds(((Number) maxReviewRoundsSpinner.getValue()).intValue());
        settings.setCouncilCrossCritique(councilCrossCritiqueCheck.isSelected());
        settings.setAgentSkills(readSkillConfigFromUI());
        settings.setRequestTimeoutSeconds(((Number) timeoutSpinner.getValue()).intValue());
        settings.setMaxOutputTokens(((Number) maxTokensSpinner.getValue()).intValue());
        settings.setCustomSystemInstructions(customInstructionsArea.getText().trim());
        settings.notifySettingsChanged();
    }

    @Override
    public void reset() {
        TriModelSettings settings = TriModelSettings.getInstance();
        for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
            ModelProviderType type = entry.getKey();
            entry.getValue().loadFrom(
                    settings.getProviderConfig(type),
                    SecretStorageService.getStoredApiKey(type),
                    SecretStorageService.getOAuthClientSecret(type)
            );
        }
        SecretStorageService.warmCacheAsync(() -> SwingUtilities.invokeLater(() -> {
            if (rootPanel == null) return;
            for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
                ProviderEditor ed = entry.getValue();
                String storedKey = SecretStorageService.getStoredApiKey(entry.getKey());
                boolean keyLoaded = false;
                if (ed.getApiKeyText().isEmpty() && !storedKey.isEmpty()) {
                    ed.apiKeyField.setText(storedKey);
                    keyLoaded = true;
                }
                String storedSecret = SecretStorageService.getOAuthClientSecret(entry.getKey());
                if (ed.getOAuthClientSecretText().isEmpty() && !storedSecret.isEmpty()) {
                    ed.oauthClientSecretField.setText(storedSecret);
                }
                ed.refreshOAuthStatusLabel();
                if (keyLoaded || ed.connectionModeBox.getSelectedItem() == ConnectionMode.OAUTH_BROWSER) {
                    ed.refreshModelsAsync(false, false);
                }
            }
        }));
        RoleAssignment roles = settings.getRoleAssignment();
        defaultModeBox.setSelectedItem(settings.getDefaultMode());
        architectBox.setSelectedItem(roles.getArchitect());
        coderBox.setSelectedItem(roles.getCoder());
        reviewerBox.setSelectedItem(roles.getReviewer());
        synthesizerBox.setSelectedItem(roles.getSynthesizer());
        maxReviewRoundsSpinner.setValue(settings.getMaxReviewRounds());
        councilCrossCritiqueCheck.setSelected(settings.isCouncilCrossCritique());
        loadSkillConfigIntoUI(settings.getAgentSkills());
        timeoutSpinner.setValue(settings.getRequestTimeoutSeconds());
        maxTokensSpinner.setValue(settings.getMaxOutputTokens());
        customInstructionsArea.setText(settings.getCustomSystemInstructions());
    }

    @Override
    public void disposeUIResources() {
        for (ProviderEditor ed : providerEditors.values()) {
            ed.cancelActiveAuth();
        }
        providerEditors.clear();
        rootPanel = null;
    }

    private static final class ProviderEditor {
        final ModelProviderType type;
        final JBCheckBox enabledCheck = new JBCheckBox("Enable this provider in orchestrations");
        final ComboBox<ConnectionMode> connectionModeBox = new ComboBox<>(ConnectionMode.values());

        final CardLayout modeCardLayout = new CardLayout();
        final JPanel modeCardsPanel = new JPanel(modeCardLayout);

        // Card 1: Browser Sign-In (Official CLI — simplest, zero Client ID)
        final JBLabel cliDetectionLabel = new JBLabel();
        final JButton cliLoginBtn = new JButton("Sign In in Browser");
        final JButton cliLogoutBtn = new JButton("Sign Out");
        final JButton cliCopyInstallBtn = new JButton("Copy Install Command");
        final JButton cliRecheckBtn = new JButton("Re-check");
        final JBTextField cliExecutableField = new JBTextField();
        final JBTextField cliArgsField = new JBTextField();

        // Card 2: API Key (Paste & Go)
        final JBPasswordField apiKeyField = new JBPasswordField();
        final JButton getApiKeyBtn = new JButton("Get API Key in Browser ↗");
        final JBLabel envHintLabel = new JBLabel();
        final JBTextField baseUrlField = new JBTextField();
        final JBTextField anthropicVersionField = new JBTextField();
        final ComboBox<OpenAiApiStyle> openAiStyleBox = new ComboBox<>(OpenAiApiStyle.values());
        final ComboBox<GeminiApiStyle> geminiStyleBox = new ComboBox<>(GeminiApiStyle.values());

        // Shared live Model selector (populated via API / CLI discovery — never hardcoded)
        final ComboBox<String> modelBox = new ComboBox<>();
        final JButton refreshModelsBtn = new JButton("Refresh Models");
        final JBLabel modelSourceLabel = new JBLabel("Models are fetched live from the provider API / CLI.");
        final AtomicInteger modelFetchGen = new AtomicInteger(0);

        // Card 3: Custom OAuth 2.0 (Own Client ID / Enterprise)
        final JBTextField oauthClientIdField = new JBTextField();
        final JButton getOAuthClientBtn = new JButton("Create Client ID ↗");
        final JBPasswordField oauthClientSecretField = new JBPasswordField();
        final JBTextField oauthAuthorizeUrlField = new JBTextField();
        final JBTextField oauthTokenUrlField = new JBTextField();
        final JBTextField oauthScopesField = new JBTextField();
        final JSpinner oauthCallbackPortSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
        final JBCheckBox oauthTemporaryOnlyCheck = new JBCheckBox(
                "Temporary session only (RAM only — clears when PyCharm closes)", true);
        final JButton authorizeBrowserBtn = new JButton("Authorize in Browser...");
        final JButton cancelAuthBtn = new JButton("Cancel");
        final JButton signOutOAuthBtn = new JButton("Sign Out");
        final JBLabel oauthStatusLabel = new JBLabel("Not authorized");

        // Shared bottom actions
        final JButton testButton = new JButton("Test Connection");
        final JButton resetDefaultsButton = new JButton("Reset Defaults");
        final JBLabel testStatusLabel = new JBLabel(" ");

        final JPanel panel;
        private volatile AtomicBoolean authCancelFlag = new AtomicBoolean(false);
        private boolean loadingConfig = false;

        ProviderEditor(ModelProviderType type) {
            this.type = type;
            this.modelBox.setEditable(true);
            this.modelSourceLabel.setForeground(JBColor.GRAY);

            modeCardsPanel.add(buildBrowserCliCard(), ConnectionMode.LOCAL_CLI.name());
            modeCardsPanel.add(buildApiKeyCard(), ConnectionMode.REST_API.name());
            modeCardsPanel.add(buildCustomOAuthCard(), ConnectionMode.OAUTH_BROWSER.name());

            JPanel modelRow = new JPanel(new BorderLayout(6, 0));
            modelRow.add(modelBox, BorderLayout.CENTER);
            modelRow.add(refreshModelsBtn, BorderLayout.EAST);

            JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actionsRow.add(testButton);
            actionsRow.add(resetDefaultsButton);
            actionsRow.add(testStatusLabel);

            this.panel = FormBuilder.createFormBuilder()
                    .addComponent(enabledCheck)
                    .addLabeledComponent("Sign-in method:", connectionModeBox)
                    .addSeparator(8)
                    .addComponent(modeCardsPanel)
                    .addSeparator(8)
                    .addLabeledComponent("Available Model (live):", modelRow)
                    .addComponent(modelSourceLabel)
                    .addSeparator(8)
                    .addComponent(actionsRow)
                    .addComponentFillVertically(new JPanel(), 0)
                    .getPanel();
            this.panel.setBorder(JBUI.Borders.empty(10));

            connectionModeBox.addActionListener(e -> {
                showActiveModeCard();
                if (!loadingConfig) {
                    refreshModelsAsync(false, true);
                }
            });
            refreshModelsBtn.addActionListener(e -> refreshModelsAsync(true, true));
            testButton.addActionListener(e -> runConnectionTest());
            resetDefaultsButton.addActionListener(e -> {
                ProviderConfig def = ProviderConfig.defaultsFor(type);
                loadFrom(def, getApiKeyText(), getOAuthClientSecretText());
            });
        }

        private JPanel buildBrowserCliCard() {
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            buttons.add(cliLoginBtn);
            buttons.add(cliLogoutBtn);
            buttons.add(cliCopyInstallBtn);
            buttons.add(cliRecheckBtn);

            JPanel advancedPanel = FormBuilder.createFormBuilder()
                    .addLabeledComponent("CLI executable:", cliExecutableField)
                    .addLabeledComponent("CLI arguments:", cliArgsField)
                    .getPanel();
            advancedPanel.setVisible(false);

            JButton advToggle = createCollapsibleToggle("Advanced CLI options", advancedPanel);

            cliLoginBtn.addActionListener(e -> runCliBrowserLogin());
            cliLogoutBtn.addActionListener(e -> runCliLogout());
            cliCopyInstallBtn.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(type.getCliInstallHint()), null);
                testStatusLabel.setForeground(JBColor.foreground());
                testStatusLabel.setText("Copied: " + type.getCliInstallHint());
            });
            cliRecheckBtn.addActionListener(e -> {
                // Auto-adapt CLI arguments when user switches executable (e.g., agy <-> gemini)
                String exe = cliExecutableField.getText().trim();
                String currentArgs = cliArgsField.getText().trim();
                cliArgsField.setText(EnvironmentAutoDetector.defaultArgsForExecutable(type, exe, currentArgs));
                refreshCliDetection();
                refreshModelsAsync(true, true);
            });

            String cliDesc = type == ModelProviderType.GEMINI
                    ? "<code>agy</code> (Google Antigravity)"
                    : "<code>" + type.getDefaultCliExecutable() + "</code>";
            return FormBuilder.createFormBuilder()
                    .addComponent(new JBLabel("<html>Uses your official " + cliDesc
                            + " CLI to sign in via your default web browser &mdash; <b>no API key or OAuth Client ID needed</b>.</html>"))
                    .addLabeledComponent("CLI status:", cliDetectionLabel)
                    .addComponent(buttons)
                    .addComponent(advToggle)
                    .addComponent(advancedPanel)
                    .getPanel();
        }

        private JPanel buildApiKeyCard() {
            apiKeyField.getEmptyText().setText("Paste your " + type.getShortName() + " API key (saved securely in Keychain)");
            JPanel keyRow = new JPanel(new BorderLayout(6, 0));
            keyRow.add(apiKeyField, BorderLayout.CENTER);
            keyRow.add(getApiKeyBtn, BorderLayout.EAST);

            boolean envDetected = SecretStorageService.hasEnvironmentKey(type);
            String envBaseUrl = EnvironmentAutoDetector.detectEnvBaseUrl(type, System.getenv());
            String baseHint = envBaseUrl.isBlank() ? "" : " | Base URL env detected: " + envBaseUrl;
            envHintLabel.setText((envDetected
                    ? "Detected " + type.getEnvVarName() + " in environment (used automatically if field is left blank)."
                    : "Optional: leave blank if you already export " + type.getEnvVarName() + ".") + baseHint);
            envHintLabel.setForeground((envDetected || !envBaseUrl.isBlank()) ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.GRAY);

            FormBuilder advBuilder = FormBuilder.createFormBuilder()
                    .addLabeledComponent("API Base URL:", baseUrlField);
            if (type == ModelProviderType.CLAUDE) {
                advBuilder.addLabeledComponent("Anthropic API Version:", anthropicVersionField);
            } else if (type == ModelProviderType.CHATGPT) {
                advBuilder.addLabeledComponent("OpenAI API Protocol:", openAiStyleBox);
            } else if (type == ModelProviderType.GEMINI) {
                advBuilder.addLabeledComponent("Gemini API Protocol:", geminiStyleBox);
            }
            JPanel advancedPanel = advBuilder.getPanel();
            advancedPanel.setVisible(false);

            JButton advToggle = createCollapsibleToggle("Advanced API options (Base URL / Protocol)", advancedPanel);
            getApiKeyBtn.addActionListener(e -> BrowserUtil.browse(type.getApiKeyConsoleUrl()));

            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("API Key:", keyRow)
                    .addComponent(envHintLabel)
                    .addComponent(advToggle)
                    .addComponent(advancedPanel)
                    .getPanel();
        }

        private JPanel buildCustomOAuthCard() {
            oauthClientIdField.getEmptyText().setText("Paste your registered OAuth 2.0 Client ID");
            oauthClientSecretField.getEmptyText().setText("Optional (needed by Google Desktop OAuth apps)");

            JPanel clientIdRow = new JPanel(new BorderLayout(6, 0));
            clientIdRow.add(oauthClientIdField, BorderLayout.CENTER);
            clientIdRow.add(getOAuthClientBtn, BorderLayout.EAST);

            cancelAuthBtn.setEnabled(false);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            buttons.add(authorizeBrowserBtn);
            buttons.add(cancelAuthBtn);
            buttons.add(signOutOAuthBtn);

            JPanel advancedPanel = FormBuilder.createFormBuilder()
                    .addLabeledComponent("Authorize URL:", oauthAuthorizeUrlField)
                    .addLabeledComponent("Token URL:", oauthTokenUrlField)
                    .addLabeledComponent("Scopes:", oauthScopesField)
                    .addLabeledComponent("Loopback port (0 = auto):", oauthCallbackPortSpinner)
                    .getPanel();
            advancedPanel.setVisible(false);

            JButton advToggle = createCollapsibleToggle("Advanced OAuth endpoints (URLs / Scopes / Port)", advancedPanel);

            getOAuthClientBtn.addActionListener(e -> BrowserUtil.browse(type.getOAuthConsoleUrl()));
            authorizeBrowserBtn.addActionListener(e -> startBrowserAuthorization());
            cancelAuthBtn.addActionListener(e -> cancelActiveAuth());
            signOutOAuthBtn.addActionListener(e -> {
                OAuthSessionStore.clearSession(type);
                ModelDiscoveryService.invalidateCache(type);
                refreshOAuthStatusLabel();
            });

            return FormBuilder.createFormBuilder()
                    .addComponent(new JBLabel("<html>For users with their own OAuth 2.0 Client ID (e.g., Google Cloud Desktop app or enterprise SSO). "
                            + "For zero-setup browser sign-in, pick <b>Browser Sign-In</b> above instead.</html>"))
                    .addLabeledComponent("OAuth Client ID:", clientIdRow)
                    .addLabeledComponent("Client Secret (optional):", oauthClientSecretField)
                    .addComponent(oauthTemporaryOnlyCheck)
                    .addLabeledComponent("Session status:", oauthStatusLabel)
                    .addComponent(buttons)
                    .addComponent(advToggle)
                    .addComponent(advancedPanel)
                    .getPanel();
        }

        private static JButton createCollapsibleToggle(String title, JPanel target) {
            JButton toggle = new JButton("▸ " + title);
            toggle.setBorderPainted(false);
            toggle.setContentAreaFilled(false);
            toggle.setHorizontalAlignment(JButton.LEFT);
            toggle.addActionListener(e -> {
                boolean next = !target.isVisible();
                target.setVisible(next);
                toggle.setText((next ? "▾ " : "▸ ") + title);
                target.revalidate();
            });
            return toggle;
        }

        void loadFrom(ProviderConfig cfg, String storedKey, String storedOAuthSecret) {
            loadingConfig = true;
            try {
                enabledCheck.setSelected(cfg.enabled);
                connectionModeBox.setSelectedItem(cfg.connectionMode == null ? ConnectionMode.REST_API : cfg.connectionMode);
                apiKeyField.setText(storedKey == null ? "" : storedKey);

                DefaultComboBoxModel<String> initialModel = new DefaultComboBoxModel<>();
                String savedModel = cfg.effectiveModelId(type);
                if (!savedModel.isBlank()) {
                    initialModel.addElement(savedModel);
                }
                modelBox.setModel(initialModel);
                modelBox.setSelectedItem(savedModel);

                baseUrlField.setText(cfg.effectiveBaseUrl(type));
                anthropicVersionField.setText(cfg.effectiveAnthropicVersion());
                openAiStyleBox.setSelectedItem(cfg.openAiApiStyle == null ? OpenAiApiStyle.RESPONSES : cfg.openAiApiStyle);
                geminiStyleBox.setSelectedItem(cfg.geminiApiStyle == null ? GeminiApiStyle.INTERACTIONS : cfg.geminiApiStyle);

                oauthClientIdField.setText(cfg.effectiveOAuthClientId());
                oauthClientSecretField.setText(storedOAuthSecret == null ? "" : storedOAuthSecret);
                oauthAuthorizeUrlField.setText(cfg.effectiveOAuthAuthorizeUrl(type));
                oauthTokenUrlField.setText(cfg.effectiveOAuthTokenUrl(type));
                oauthScopesField.setText(cfg.effectiveOAuthScopes(type));
                oauthCallbackPortSpinner.setValue(cfg.effectiveOAuthCallbackPort());
                oauthTemporaryOnlyCheck.setSelected(cfg.oauthTemporaryOnly);

                cliExecutableField.setText(cfg.effectiveCliExecutable(type));
                cliArgsField.setText(cfg.effectiveCliArgs(type));

                refreshCliDetection();
                refreshOAuthStatusLabel();
                showActiveModeCard();
            } finally {
                loadingConfig = false;
            }
            refreshModelsAsync(false, false);
        }

        void showActiveModeCard() {
            ConnectionMode mode = (ConnectionMode) connectionModeBox.getSelectedItem();
            if (mode == null) mode = ConnectionMode.REST_API;
            modeCardLayout.show(modeCardsPanel, mode.name());
        }

        void refreshCliDetection() {
            String exe = cliExecutableField.getText().trim();
            if (exe.isBlank()) exe = type.getDefaultCliExecutable();
            String resolved = CliModelClient.resolveExecutable(exe);
            if (resolved != null) {
                cliDetectionLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                cliDetectionLabel.setText("✓ Installed (" + resolved + ")");
                cliLoginBtn.setEnabled(true);
                cliLogoutBtn.setVisible(CliModelClient.supportsCliLogout(type, exe));
                cliCopyInstallBtn.setVisible(false);

                String capturedExe = exe;
                runAsync(() -> {
                    CliModelClient.CliAuthStatus status = CliModelClient.checkCliAuthStatus(type, capturedExe);
                    SwingUtilities.invokeLater(() -> {
                        if (!status.installed()) return;
                        switch (status.state()) {
                            case SIGNED_IN -> {
                                cliDetectionLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                                cliDetectionLabel.setText("✓ " + status.detail() + " (" + status.resolvedPath() + ")");
                            }
                            case SIGNED_OUT -> {
                                cliDetectionLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                                cliDetectionLabel.setText("⚠ " + status.detail() + " (" + status.resolvedPath() + ")");
                            }
                            default -> {
                                cliDetectionLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                                cliDetectionLabel.setText("✓ Installed (" + status.resolvedPath() + ")");
                            }
                        }
                    });
                }, "TriModel-CliStatus-" + type.name());
            } else {
                cliDetectionLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                cliDetectionLabel.setText("Not installed — run: " + type.getCliInstallHint());
                cliLoginBtn.setEnabled(false);
                cliLogoutBtn.setVisible(false);
                cliCopyInstallBtn.setVisible(true);
            }
        }

        void refreshModelsAsync(boolean forceRefresh, boolean autoSelectIfInvalid) {
            int gen = modelFetchGen.incrementAndGet();
            ProviderConfig cfg = toConfig();
            String key = getApiKeyText();

            if (cfg.connectionMode == ConnectionMode.LOCAL_CLI
                    && !CliModelClient.isCliInstalled(type, cfg.effectiveCliExecutable(type))) {
                modelSourceLabel.setForeground(JBColor.GRAY);
                modelSourceLabel.setText("Install CLI to discover available models.");
                return;
            }
            if (cfg.connectionMode == ConnectionMode.REST_API
                    && key.isBlank() && !SecretStorageService.hasEnvironmentKey(type)) {
                modelSourceLabel.setForeground(JBColor.GRAY);
                modelSourceLabel.setText("Enter an API key (or export " + type.getEnvVarName() + "), then click Refresh Models.");
                return;
            }
            if (cfg.connectionMode == ConnectionMode.OAUTH_BROWSER && !OAuthSessionStore.hasUsableSession(type)) {
                modelSourceLabel.setForeground(JBColor.GRAY);
                modelSourceLabel.setText("Authorize in Browser first, then click Refresh Models.");
                return;
            }

            refreshModelsBtn.setEnabled(false);
            modelSourceLabel.setForeground(JBColor.GRAY);
            modelSourceLabel.setText("Fetching live available models from " + type.getShortName() + "...");

            runAsync(() -> {
                ModelDiscoveryService discovery = new ModelDiscoveryService();
                ModelDiscoveryService.DiscoveryResult res = discovery.fetchAvailableModels(
                        type, cfg, key, forceRefresh, () -> modelFetchGen.get() != gen);
                SwingUtilities.invokeLater(() -> {
                    if (modelFetchGen.get() != gen) return;
                    refreshModelsBtn.setEnabled(true);
                    if (res.success() && !res.models().isEmpty()) {
                        String current = getSelectedModelText();
                        DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();
                        for (ModelDiscoveryService.DiscoveredModel m : res.models()) {
                            comboModel.addElement(m.id());
                        }
                        boolean currentAvailable = !current.isBlank() && res.containsModelId(current);
                        modelBox.setModel(comboModel);
                        if (currentAvailable) {
                            modelBox.setSelectedItem(current);
                            modelSourceLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                            modelSourceLabel.setText("Loaded " + res.models().size() + " available model(s) from " + res.source() + ".");
                        } else if (!current.isBlank()) {
                            String fallback = res.models().get(0).id();
                            modelBox.setSelectedItem(fallback);
                            modelSourceLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                            modelSourceLabel.setText("Switched from unavailable '" + current + "' to '" + fallback
                                    + "' (" + res.models().size() + " live from " + res.source() + ") — click Apply/OK to save.");
                        } else {
                            // Saved model was blank on initial load: preserve blank unless user clicked Refresh/switched mode
                            modelBox.setSelectedItem(autoSelectIfInvalid ? res.models().get(0).id() : "");
                            modelSourceLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                            modelSourceLabel.setText("Loaded " + res.models().size() + " available model(s) from " + res.source() + ".");
                        }
                    } else {
                        modelSourceLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                        String err = res.errorMessage().isBlank() ? "Could not fetch models." : res.errorMessage();
                        modelSourceLabel.setText(err.length() > 120 ? err.substring(0, 120) + "..." : err);
                        modelSourceLabel.setToolTipText(err);
                    }
                });
            }, "TriModel-Discover-" + type.name());
        }

        private String getSelectedModelText() {
            Object selectedModel = modelBox.getEditor().getItem();
            return selectedModel == null ? "" : selectedModel.toString().trim();
        }

        void applyAutoDetection(EnvironmentAutoDetector.ProviderDetection det) {
            if (det == null) return;
            ProviderConfig suggested = det.suggestedConfig();
            loadingConfig = true;
            try {
                enabledCheck.setSelected(suggested.enabled);
                connectionModeBox.setSelectedItem(suggested.connectionMode == null ? ConnectionMode.REST_API : suggested.connectionMode);
                baseUrlField.setText(suggested.effectiveBaseUrl(type));
                cliExecutableField.setText(suggested.effectiveCliExecutable(type));
                cliArgsField.setText(suggested.effectiveCliArgs(type));
                showActiveModeCard();
            } finally {
                loadingConfig = false;
            }

            CliModelClient.CliAuthStatus status = det.cliStatus();
            if (status != null && status.installed()) {
                cliLoginBtn.setEnabled(true);
                cliLogoutBtn.setVisible(CliModelClient.supportsCliLogout(type, suggested.effectiveCliExecutable(type)));
                cliCopyInstallBtn.setVisible(false);
                if (status.state() == CliModelClient.AuthState.SIGNED_OUT) {
                    cliDetectionLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                    cliDetectionLabel.setText("⚠ " + status.detail() + " (" + status.resolvedPath() + ")");
                } else {
                    cliDetectionLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                    cliDetectionLabel.setText("✓ " + status.detail() + " (" + status.resolvedPath() + ")");
                }
            } else {
                refreshCliDetection();
            }

            ModelDiscoveryService.DiscoveryResult disc = det.discovery();
            if (disc != null && disc.success() && !disc.models().isEmpty()) {
                DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();
                for (ModelDiscoveryService.DiscoveredModel m : disc.models()) {
                    comboModel.addElement(m.id());
                }
                modelBox.setModel(comboModel);
                String chosen = (suggested.modelId != null && !suggested.modelId.isBlank())
                        ? suggested.modelId : disc.models().get(0).id();
                modelBox.setSelectedItem(chosen);
                modelSourceLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                modelSourceLabel.setText("Auto-detected " + disc.models().size() + " available model(s) from " + disc.source() + ".");
            }
            testStatusLabel.setForeground(det.ready() ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.GRAY);
            testStatusLabel.setText((det.ready() ? "✓ " : "") + det.summary());
            testStatusLabel.setToolTipText(det.summary());
        }

        void refreshOAuthStatusLabel() {
            OAuthToken token = OAuthSessionStore.getToken(type);
            if (token != null && token.hasAccessToken() && !token.isExpired()) {
                oauthStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                oauthStatusLabel.setText(token.statusSummary());
            } else if (token != null && token.hasAccessToken()) {
                oauthStatusLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                oauthStatusLabel.setText(token.statusSummary());
            } else {
                oauthStatusLabel.setForeground(JBColor.GRAY);
                oauthStatusLabel.setText("Not authorized");
            }
        }

        void startBrowserAuthorization() {
            ProviderConfig cfg = toConfig();
            String clientSecret = getOAuthClientSecretText();
            authCancelFlag = new AtomicBoolean(false);
            authorizeBrowserBtn.setEnabled(false);
            cancelAuthBtn.setEnabled(true);
            oauthStatusLabel.setForeground(JBColor.foreground());
            oauthStatusLabel.setText("Starting localhost listener...");

            Runnable task = () -> {
                OAuthPkceService.AuthResult result = new OAuthPkceService().authorizeInBrowser(
                        type, cfg, clientSecret, null,
                        msg -> SwingUtilities.invokeLater(() -> oauthStatusLabel.setText(msg)),
                        authCancelFlag::get
                );
                SwingUtilities.invokeLater(() -> {
                    authorizeBrowserBtn.setEnabled(true);
                    cancelAuthBtn.setEnabled(false);
                    if (result.success()) {
                        refreshOAuthStatusLabel();
                        refreshModelsAsync(true, true);
                    } else {
                        oauthStatusLabel.setForeground(JBColor.RED);
                        oauthStatusLabel.setText(result.errorMessage());
                    }
                });
            };
            runAsync(task, "TriModel-OAuth-" + type.name());
        }

        void cancelActiveAuth() {
            authCancelFlag.set(true);
            cancelAuthBtn.setEnabled(false);
        }

        void runCliBrowserLogin() {
            String exe = cliExecutableField.getText().trim();
            cliLoginBtn.setEnabled(false);
            testStatusLabel.setForeground(JBColor.foreground());
            testStatusLabel.setText("Launching browser sign-in for " + type.getShortName() + "...");
            runAsync(() -> {
                String msg = CliModelClient.launchOfficialCliLogin(
                        type, exe, s -> SwingUtilities.invokeLater(() -> testStatusLabel.setText(s)));
                SwingUtilities.invokeLater(() -> {
                    cliLoginBtn.setEnabled(true);
                    boolean ok = msg.contains("successfully");
                    testStatusLabel.setForeground(ok
                            ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.foreground());
                    testStatusLabel.setText(msg);
                    testStatusLabel.setToolTipText(msg);
                    refreshCliDetection();
                    if (ok) {
                        refreshModelsAsync(true, true);
                    }
                });
            }, "TriModel-CliLogin-" + type.name());
        }

        void runCliLogout() {
            String exe = cliExecutableField.getText().trim();
            cliLogoutBtn.setEnabled(false);
            runAsync(() -> {
                String msg = CliModelClient.launchOfficialCliLogout(type, exe);
                SwingUtilities.invokeLater(() -> {
                    cliLogoutBtn.setEnabled(true);
                    testStatusLabel.setForeground(JBColor.foreground());
                    testStatusLabel.setText(msg);
                    testStatusLabel.setToolTipText(msg);
                    refreshCliDetection();
                });
            }, "TriModel-CliLogout-" + type.name());
        }

        ProviderConfig toConfig() {
            ProviderConfig cfg = new ProviderConfig();
            cfg.enabled = enabledCheck.isSelected();
            cfg.connectionMode = (ConnectionMode) connectionModeBox.getSelectedItem();
            cfg.modelId = getSelectedModelText();
            cfg.baseUrl = baseUrlField.getText().trim();
            cfg.anthropicVersion = anthropicVersionField.getText().trim();
            cfg.openAiApiStyle = (OpenAiApiStyle) openAiStyleBox.getSelectedItem();
            cfg.geminiApiStyle = (GeminiApiStyle) geminiStyleBox.getSelectedItem();
            cfg.oauthClientId = oauthClientIdField.getText().trim();
            cfg.oauthAuthorizeUrl = oauthAuthorizeUrlField.getText().trim();
            cfg.oauthTokenUrl = oauthTokenUrlField.getText().trim();
            cfg.oauthScopes = oauthScopesField.getText().trim();
            cfg.oauthCallbackPort = ((Number) oauthCallbackPortSpinner.getValue()).intValue();
            cfg.oauthTemporaryOnly = oauthTemporaryOnlyCheck.isSelected();
            cfg.cliExecutable = cliExecutableField.getText().trim();
            cfg.cliArgs = cliArgsField.getText().trim();
            return cfg;
        }

        String getApiKeyText() {
            return new String(apiKeyField.getPassword()).trim();
        }

        String getOAuthClientSecretText() {
            return new String(oauthClientSecretField.getPassword()).trim();
        }

        void runConnectionTest() {
            ProviderConfig cfg = toConfig();
            String key = getApiKeyText();
            testButton.setEnabled(false);
            testStatusLabel.setForeground(JBColor.foreground());
            testStatusLabel.setText("Testing " + type.getShortName() + "...");

            runAsync(() -> {
                ModelClient client = ModelClientFactory.createClient(type, cfg, key);
                ModelRequest ping = new ModelRequest(
                        "Reply with exactly: OK",
                        "Connection health check. Reply with OK.",
                        List.of(),
                        64,
                        30
                );
                ModelResponse resp = client.generate(ping, () -> false);
                SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    if (resp.success()) {
                        testStatusLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                        String okText = "✓ Connected (" + resp.latencyMs() + "ms) - " + resp.modelId();
                        testStatusLabel.setText(okText);
                        testStatusLabel.setToolTipText(okText);
                        if (modelBox.getItemCount() == 0) {
                            refreshModelsAsync(true, true);
                        }
                    } else {
                        testStatusLabel.setForeground(JBColor.RED);
                        String fullErr = resp.errorMessage();
                        testStatusLabel.setToolTipText(fullErr);
                        String err = fullErr.length() > 110 ? fullErr.substring(0, 110) + "..." : fullErr;
                        testStatusLabel.setText("✗ " + err);
                    }
                });
            }, "TriModel-Test-" + type.name());
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
