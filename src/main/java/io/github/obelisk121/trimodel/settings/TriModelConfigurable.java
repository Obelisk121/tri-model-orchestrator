package io.github.obelisk121.trimodel.settings;

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
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelClientFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Native PyCharm Settings page (Settings -> Tools -> Tri-Model AI Orchestrator).
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
                "<html>Configure <b>Claude</b>, <b>ChatGPT</b>, and <b>Gemini</b> below. "
                        + "API keys are stored securely in JetBrains PasswordSafe (or read from environment variables). "
                        + "Prompts and attached code are sent <b>directly</b> to your configured providers &mdash; zero telemetry or intermediary servers.</html>");
        header.setBorder(JBUI.Borders.emptyBottom(6));
        rootPanel.add(header, BorderLayout.NORTH);

        JBTabbedPane tabs = new JBTabbedPane();
        providerEditors.clear();
        for (ModelProviderType type : ModelProviderType.values()) {
            ProviderEditor editor = new ProviderEditor(type);
            providerEditors.put(type, editor);
            tabs.addTab(type.getFullName(), editor.panel);
        }
        tabs.addTab("Orchestration & Defaults", buildOrchestrationTab());
        rootPanel.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton testAllBtn = new JButton("Test All Enabled Models");
        JBLabel testAllStatus = new JBLabel(" ");
        testAllBtn.addActionListener(e -> {
            testAllStatus.setForeground(JBColor.foreground());
            testAllStatus.setText("Testing enabled providers...");
            for (ProviderEditor editor : providerEditors.values()) {
                if (editor.enabledCheck.isSelected()) {
                    editor.runConnectionTest();
                }
            }
            testAllStatus.setText("Connection tests started — check each provider tab for results.");
        });
        footer.add(testAllBtn);
        footer.add(testAllStatus);
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
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(120, 15, 600, 15));
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 512, 65536, 512));

        customInstructionsArea = new JBTextArea(5, 40);
        customInstructionsArea.setLineWrap(true);
        customInstructionsArea.setWrapStyleWord(true);
        JBScrollPane instructionsScroll = new JBScrollPane(customInstructionsArea);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Default orchestration mode:", defaultModeBox)
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
        return form;
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
        }
        RoleAssignment roles = settings.getRoleAssignment();
        return defaultModeBox.getSelectedItem() != settings.getDefaultMode()
                || architectBox.getSelectedItem() != roles.getArchitect()
                || coderBox.getSelectedItem() != roles.getCoder()
                || reviewerBox.getSelectedItem() != roles.getReviewer()
                || synthesizerBox.getSelectedItem() != roles.getSynthesizer()
                || ((Number) maxReviewRoundsSpinner.getValue()).intValue() != settings.getMaxReviewRounds()
                || councilCrossCritiqueCheck.isSelected() != settings.isCouncilCrossCritique()
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
            entry.getValue().loadFrom(settings.getProviderConfig(type), SecretStorageService.getStoredApiKey(type));
        }
        SecretStorageService.warmCacheAsync(() -> SwingUtilities.invokeLater(() -> {
            if (rootPanel == null) return;
            for (Map.Entry<ModelProviderType, ProviderEditor> entry : providerEditors.entrySet()) {
                String stored = SecretStorageService.getStoredApiKey(entry.getKey());
                if (entry.getValue().getApiKeyText().isEmpty() && !stored.isEmpty()) {
                    entry.getValue().apiKeyField.setText(stored);
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
        timeoutSpinner.setValue(settings.getRequestTimeoutSeconds());
        maxTokensSpinner.setValue(settings.getMaxOutputTokens());
        customInstructionsArea.setText(settings.getCustomSystemInstructions());
    }

    @Override
    public void disposeUIResources() {
        providerEditors.clear();
        rootPanel = null;
    }

    private static final class ProviderEditor {
        final ModelProviderType type;
        final JBCheckBox enabledCheck = new JBCheckBox("Enable this provider in orchestrations");
        final ComboBox<ConnectionMode> connectionModeBox = new ComboBox<>(ConnectionMode.values());
        final JBPasswordField apiKeyField = new JBPasswordField();
        final JBLabel envHintLabel = new JBLabel();
        final ComboBox<String> modelBox;
        final JBTextField baseUrlField = new JBTextField();
        final JBTextField anthropicVersionField = new JBTextField();
        final ComboBox<OpenAiApiStyle> openAiStyleBox = new ComboBox<>(OpenAiApiStyle.values());
        final ComboBox<GeminiApiStyle> geminiStyleBox = new ComboBox<>(GeminiApiStyle.values());
        final JBTextField cliExecutableField = new JBTextField();
        final JBTextField cliArgsField = new JBTextField();
        final JButton testButton = new JButton("Test Connection");
        final JButton resetDefaultsButton = new JButton("Reset Defaults");
        final JBLabel testStatusLabel = new JBLabel(" ");
        final JPanel panel;

        ProviderEditor(ModelProviderType type) {
            this.type = type;
            List<String> presets = type.getModelPresets();
            this.modelBox = new ComboBox<>(presets.toArray(new String[0]));
            this.modelBox.setEditable(true);

            apiKeyField.getEmptyText().setText("Stored securely in PasswordSafe (or leave blank to use " + type.getEnvVarName() + ")");
            boolean envDetected = SecretStorageService.hasEnvironmentKey(type);
            envHintLabel.setText(envDetected
                    ? "Environment variable " + type.getEnvVarName() + " detected (used as fallback if key field is blank)."
                    : "Optional fallback: environment variable " + type.getEnvVarName() + " (currently unset).");
            envHintLabel.setForeground(envDetected ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.GRAY);

            JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actionsRow.add(testButton);
            actionsRow.add(resetDefaultsButton);
            actionsRow.add(testStatusLabel);

            FormBuilder fb = FormBuilder.createFormBuilder()
                    .addComponent(enabledCheck)
                    .addLabeledComponent("Connection mode:", connectionModeBox)
                    .addSeparator(8)
                    .addLabeledComponent("API Key:", apiKeyField)
                    .addComponent(envHintLabel)
                    .addLabeledComponent("Model ID:", modelBox)
                    .addLabeledComponent("API Base URL:", baseUrlField);

            if (type == ModelProviderType.CLAUDE) {
                fb.addLabeledComponent("Anthropic API Version:", anthropicVersionField);
            } else if (type == ModelProviderType.CHATGPT) {
                fb.addLabeledComponent("OpenAI API Protocol:", openAiStyleBox);
            } else if (type == ModelProviderType.GEMINI) {
                fb.addLabeledComponent("Gemini API Protocol:", geminiStyleBox);
            }

            fb.addSeparator(8)
                    .addLabeledComponent("Local CLI executable:", cliExecutableField)
                    .addLabeledComponent("Local CLI arguments:", cliArgsField)
                    .addComponent(new JBLabel(
                            "<html><small>Tip: In Local CLI mode, the prompt is piped via stdin by default, or use <code>{prompt}</code> inside arguments.</small></html>"))
                    .addSeparator(8)
                    .addComponent(actionsRow)
                    .addComponentFillVertically(new JPanel(), 0);

            this.panel = fb.getPanel();
            this.panel.setBorder(JBUI.Borders.empty(10));

            connectionModeBox.addActionListener(e -> updateModeFieldStates());
            testButton.addActionListener(e -> runConnectionTest());
            resetDefaultsButton.addActionListener(e -> {
                ProviderConfig def = ProviderConfig.defaultsFor(type);
                loadFrom(def, getApiKeyText());
            });
        }

        void loadFrom(ProviderConfig cfg, String storedKey) {
            enabledCheck.setSelected(cfg.enabled);
            connectionModeBox.setSelectedItem(cfg.connectionMode == null ? ConnectionMode.REST_API : cfg.connectionMode);
            apiKeyField.setText(storedKey == null ? "" : storedKey);
            modelBox.setSelectedItem(cfg.effectiveModelId(type));
            baseUrlField.setText(cfg.effectiveBaseUrl(type));
            anthropicVersionField.setText(cfg.effectiveAnthropicVersion());
            openAiStyleBox.setSelectedItem(cfg.openAiApiStyle == null ? OpenAiApiStyle.RESPONSES : cfg.openAiApiStyle);
            geminiStyleBox.setSelectedItem(cfg.geminiApiStyle == null ? GeminiApiStyle.INTERACTIONS : cfg.geminiApiStyle);
            cliExecutableField.setText(cfg.effectiveCliExecutable(type));
            cliArgsField.setText(cfg.effectiveCliArgs(type));
            updateModeFieldStates();
        }

        void updateModeFieldStates() {
            boolean isRest = connectionModeBox.getSelectedItem() == ConnectionMode.REST_API;
            apiKeyField.setEnabled(isRest);
            modelBox.setEnabled(isRest);
            baseUrlField.setEnabled(isRest);
            anthropicVersionField.setEnabled(isRest);
            openAiStyleBox.setEnabled(isRest);
            geminiStyleBox.setEnabled(isRest);
            cliExecutableField.setEnabled(!isRest);
            cliArgsField.setEnabled(!isRest);
        }

        ProviderConfig toConfig() {
            ProviderConfig cfg = new ProviderConfig();
            cfg.enabled = enabledCheck.isSelected();
            cfg.connectionMode = (ConnectionMode) connectionModeBox.getSelectedItem();
            Object selectedModel = modelBox.getEditor().getItem();
            cfg.modelId = selectedModel == null ? type.getDefaultModel() : selectedModel.toString().trim();
            cfg.baseUrl = baseUrlField.getText().trim();
            cfg.anthropicVersion = anthropicVersionField.getText().trim();
            cfg.openAiApiStyle = (OpenAiApiStyle) openAiStyleBox.getSelectedItem();
            cfg.geminiApiStyle = (GeminiApiStyle) geminiStyleBox.getSelectedItem();
            cfg.cliExecutable = cliExecutableField.getText().trim();
            cfg.cliArgs = cliArgsField.getText().trim();
            return cfg;
        }

        String getApiKeyText() {
            return new String(apiKeyField.getPassword()).trim();
        }

        void runConnectionTest() {
            ProviderConfig cfg = toConfig();
            String key = getApiKeyText();
            testButton.setEnabled(false);
            testStatusLabel.setForeground(JBColor.foreground());
            testStatusLabel.setText("Testing " + type.getShortName() + "...");

            Runnable task = () -> {
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
                        testStatusLabel.setText("Connected (" + resp.latencyMs() + "ms) - " + resp.modelId());
                    } else {
                        testStatusLabel.setForeground(JBColor.RED);
                        String err = resp.errorMessage();
                        if (err.length() > 110) {
                            err = err.substring(0, 110) + "...";
                        }
                        testStatusLabel.setText("Failed: " + err);
                    }
                });
            };

            if (ApplicationManager.getApplication() != null) {
                ApplicationManager.getApplication().executeOnPooledThread(task);
            } else {
                new Thread(task, "TriModel-Test-" + type.name()).start();
            }
        }
    }
}
