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
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelClientFactory;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelConfigurable;
import io.github.obelisk121.trimodel.settings.TriModelSettings;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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

/**
 * Simple one-screen setup dialog so users can authorize Claude, ChatGPT, and Gemini
 * in two clicks via Browser Sign-In (official CLI — zero Client ID required) or by pasting an API key.
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
        setOKButtonText("Save & Done");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setPreferredSize(new Dimension(740, 440));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        JBLabel intro = new JBLabel(
                "<html>Connect all three models on one screen: choose <b>Browser Sign-In</b> (uses official "
                        + "<code>claude</code>, <code>codex</code>, <code>gemini</code> CLIs — no Client ID needed) "
                        + "or paste an <b>API Key</b>.</html>");
        top.add(intro, BorderLayout.CENTER);

        JPanel quickBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton useBrowserAllBtn = new JButton("Use Browser Sign-In for All");
        useBrowserAllBtn.addActionListener(e -> {
            for (ProviderRow row : rows.values()) {
                if (CliModelClient.isCliInstalled(row.type, row.existingConfig.effectiveCliExecutable(row.type))) {
                    row.enabledCheck.setSelected(true);
                    row.simpleModeCombo.setSelectedItem(MODE_BROWSER);
                }
            }
        });
        JButton advancedBtn = new JButton("Advanced Settings...");
        advancedBtn.addActionListener(e -> {
            saveChoices();
            close(OK_EXIT_CODE);
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TriModelConfigurable.class);
        });
        quickBar.add(useBrowserAllBtn);
        quickBar.add(advancedBtn);
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
        return root;
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
        final ProviderConfig existingConfig;
        final JBCheckBox enabledCheck;
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
            panel.add(modeCards, BorderLayout.CENTER);
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

            simpleModeCombo.addActionListener(e -> switchCard());
            getKeyBtn.addActionListener(e -> BrowserUtil.browse(type.getApiKeyConsoleUrl()));
            copyInstallBtn.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(type.getCliInstallHint()), null);
                feedbackLabel.setForeground(JBColor.foreground());
                feedbackLabel.setText("Copied install command: " + type.getCliInstallHint());
            });
            signInBrowserBtn.addActionListener(e -> runCliLogin());
            signOutCliBtn.addActionListener(e -> runCliLogout());
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
                signOutCliBtn.setVisible(!type.getDefaultCliLogoutArgs().isBlank());
                copyInstallBtn.setVisible(false);
            } else {
                cliStatusLabel.setForeground(new JBColor(0xD97706, 0xF59E0B));
                cliStatusLabel.setText(exe + " CLI not installed");
                signInBrowserBtn.setEnabled(false);
                signOutCliBtn.setVisible(false);
                copyInstallBtn.setVisible(true);
            }
        }

        private void runCliLogin() {
            signInBrowserBtn.setEnabled(false);
            feedbackLabel.setForeground(JBColor.foreground());
            feedbackLabel.setText("Opening default browser for " + type.getShortName() + " sign-in...");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String msg = CliModelClient.launchOfficialCliLogin(
                        type,
                        existingConfig.effectiveCliExecutable(type),
                        s -> SwingUtilities.invokeLater(() -> feedbackLabel.setText(s))
                );
                SwingUtilities.invokeLater(() -> {
                    signInBrowserBtn.setEnabled(true);
                    feedbackLabel.setForeground(msg.contains("successfully")
                            ? new JBColor(0x0F9D78, 0x2AD1A3) : JBColor.foreground());
                    feedbackLabel.setText(msg);
                });
            });
        }

        private void runCliLogout() {
            signOutCliBtn.setEnabled(false);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String msg = CliModelClient.launchOfficialCliLogout(type, existingConfig.effectiveCliExecutable(type));
                SwingUtilities.invokeLater(() -> {
                    signOutCliBtn.setEnabled(true);
                    feedbackLabel.setForeground(JBColor.foreground());
                    feedbackLabel.setText(msg);
                });
            });
        }

        private void runQuickTest() {
            ProviderConfig cfg = buildUpdatedConfig();
            String key = getApiKey();
            testBtn.setEnabled(false);
            feedbackLabel.setForeground(JBColor.foreground());
            feedbackLabel.setText("Testing " + type.getShortName() + "...");

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                ModelClient client = ModelClientFactory.createClient(type, cfg, key);
                ModelResponse resp = client.generate(
                        new ModelRequest("Reply with exactly: OK", "Health check. Reply OK.", List.of(), 64, 30),
                        () -> false
                );
                SwingUtilities.invokeLater(() -> {
                    testBtn.setEnabled(true);
                    if (resp.success()) {
                        feedbackLabel.setForeground(new JBColor(0x0F9D78, 0x2AD1A3));
                        feedbackLabel.setText("✓ Connected (" + resp.latencyMs() + "ms) — " + resp.modelId());
                    } else {
                        feedbackLabel.setForeground(JBColor.RED);
                        String err = resp.errorMessage();
                        if (err.length() > 110) err = err.substring(0, 110) + "...";
                        feedbackLabel.setText("✗ " + err);
                    }
                });
            });
        }

        ProviderConfig buildUpdatedConfig() {
            ProviderConfig cfg = existingConfig.copy();
            cfg.enabled = enabledCheck.isSelected();
            boolean useBrowser = MODE_BROWSER.equals(simpleModeCombo.getSelectedItem());
            cfg.connectionMode = useBrowser ? ConnectionMode.LOCAL_CLI : ConnectionMode.REST_API;
            return cfg;
        }

        String getApiKey() {
            return new String(apiKeyField.getPassword()).trim();
        }
    }
}
