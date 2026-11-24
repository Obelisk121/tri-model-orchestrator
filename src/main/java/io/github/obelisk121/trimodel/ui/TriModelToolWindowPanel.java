package io.github.obelisk121.trimodel.ui;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;
import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.ide.CodeApplyService;
import io.github.obelisk121.trimodel.ide.IdeContextService;
import io.github.obelisk121.trimodel.ide.TriModelProjectService;
import io.github.obelisk121.trimodel.model.ContextAttachment;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.OrchestrationListener;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.OrchestrationResult;
import io.github.obelisk121.trimodel.model.ProposedFileChange;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import io.github.obelisk121.trimodel.model.StageResult;
import io.github.obelisk121.trimodel.orchestration.OrchestrationEngine;
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelClientFactory;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelConfigurable;
import io.github.obelisk121.trimodel.settings.TriModelSettings;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main PyCharm Tool Window UI for orchestrating Claude, ChatGPT, and Gemini together.
 */
public final class TriModelToolWindowPanel extends JPanel implements Disposable {

    private final Project project;
    private final TriModelProjectService projectService;
    private final Runnable settingsChangedListener;
    private final Runnable oauthSessionListener;

    // Header & Orchestration controls
    private final ComboBox<OrchestrationMode> modeCombo = new ComboBox<>(OrchestrationMode.values());
    private final ComboBox<ModelProviderType> architectCombo = new ComboBox<>(ModelProviderType.values());
    private final ComboBox<ModelProviderType> coderCombo = new ComboBox<>(ModelProviderType.values());
    private final ComboBox<ModelProviderType> reviewerCombo = new ComboBox<>(ModelProviderType.values());
    private final ComboBox<ModelProviderType> chairCombo = new ComboBox<>(ModelProviderType.values());
    private final Map<ModelProviderType, JBLabel> statusBadges = new EnumMap<>(ModelProviderType.class);

    // Status strip
    private final JProgressBar progressBar = new JProgressBar();
    private final JBLabel statusLabel = new JBLabel("Ready — configure Claude, ChatGPT & Gemini in Settings.");
    private final JBLabel metricsLabel = new JBLabel("");

    // Output tabs
    private final JBTabbedPane outputTabs = new JBTabbedPane();
    private final JEditorPane finalOutputPane = createHtmlPane();
    private final JEditorPane timelinePane = createHtmlPane();
    private final Map<ModelProviderType, JEditorPane> providerPanes = new EnumMap<>(ModelProviderType.class);

    // Proposed Code Changes tab
    private final DefaultListModel<String> changeListModel = new DefaultListModel<>();
    private final JBList<String> changeList = new JBList<>(changeListModel);
    private final JBTextArea changePreviewArea = new JBTextArea();
    private final JButton diffBtn = new JButton("Preview Diff", AllIcons.Actions.Diff);
    private final JButton applySelectedBtn = new JButton("Apply Selected", AllIcons.Actions.Checked);
    private final JButton replaceEditorBtn = new JButton("Replace Editor/Selection");
    private final JButton applyAllBtn = new JButton("Apply All...");
    private final JButton copyCodeBtn = new JButton("Copy Code", AllIcons.Actions.Copy);
    private final JButton copyMarkdownBtn = new JButton("Copy Output", AllIcons.Actions.Copy);

    // Context & prompt input
    private final JBCheckBox includeActiveFileCheck = new JBCheckBox("Active File", true);
    private final JBCheckBox selectionOnlyCheck = new JBCheckBox("Selection Only", false);
    private final JBCheckBox includeDiagnosticsCheck = new JBCheckBox("IDE Problems", true);
    private final JBLabel attachedFilesLabel = new JBLabel("No extra files attached");
    private final JBTextArea promptArea = new JBTextArea(4, 40);
    private final JButton runBtn = new JButton("Run Orchestration", AllIcons.Actions.Execute);
    private final JButton stopBtn = new JButton("Stop", AllIcons.Actions.Suspend);
    private final JBLabel historyLabel = new JBLabel("Session: 0 turns");

    // Live state
    private final Map<String, VirtualFile> attachedProjectFiles = new LinkedHashMap<>();
    private final List<StageResult> liveStages = new ArrayList<>();
    private final StringBuilder liveTimelineNotes = new StringBuilder();
    private List<ProposedFileChange> currentChanges = List.of();
    private String lastFinalMarkdown = "";
    private volatile AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private volatile boolean isRunning = false;
    private boolean updatingControls = false;

    public TriModelToolWindowPanel(Project project) {
        super(new BorderLayout(0, 6));
        this.project = project;
        this.projectService = TriModelProjectService.getInstance(project);
        this.projectService.attachPanel(this);

        setBorder(JBUI.Borders.empty(6));
        loadSettingsIntoControls();

        add(buildTopHeader(), BorderLayout.NORTH);
        add(buildCenterSplitter(), BorderLayout.CENTER);

        wireEvents();
        this.settingsChangedListener = () -> SwingUtilities.invokeLater(() -> {
            loadSettingsIntoControls();
            refreshProviderBadges();
        });
        this.oauthSessionListener = () -> SwingUtilities.invokeLater(this::refreshProviderBadges);
        TriModelSettings.getInstance().addChangeListener(this.settingsChangedListener);
        OAuthSessionStore.addChangeListener(this.oauthSessionListener);
        refreshProviderBadges();
        SecretStorageService.warmCacheAsync(() -> SwingUtilities.invokeLater(this::refreshProviderBadges));
        renderWelcomeView();
    }

    @Override
    public void dispose() {
        TriModelSettings.getInstance().removeChangeListener(this.settingsChangedListener);
        OAuthSessionStore.removeChangeListener(this.oauthSessionListener);
        cancelFlag.set(true);
    }

    private JPanel buildTopHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        // Row 1: Mode + Provider Badges + Utility buttons
        JPanel row1 = new JPanel(new BorderLayout(6, 0));
        row1.setBorder(JBUI.Borders.emptyBottom(4));

        JPanel modePanel = new JPanel(new BorderLayout(6, 0));
        JBLabel modeLbl = new JBLabel("Mode:");
        modePanel.add(modeLbl, BorderLayout.WEST);
        modePanel.add(modeCombo, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        for (ModelProviderType type : ModelProviderType.values()) {
            JBLabel pill = new JBLabel();
            pill.setOpaque(true);
            pill.setBorder(JBUI.Borders.empty(2, 6));
            pill.setFont(pill.getFont().deriveFont(Font.BOLD, 11f));
            pill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            pill.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openQuickSetup();
                }
            });
            statusBadges.put(type, pill);
            rightControls.add(pill);
        }
        JButton quickSetupBtn = new JButton("Sign In");
        quickSetupBtn.setToolTipText("Quick one-screen setup: Sign in via Browser or paste API keys for Claude, ChatGPT & Gemini");
        quickSetupBtn.addActionListener(e -> openQuickSetup());

        JButton newSessionBtn = new JButton("New", AllIcons.Actions.Refresh);
        newSessionBtn.setToolTipText("Start a fresh orchestration session (clears conversation history)");
        newSessionBtn.addActionListener(e -> resetSession());

        JButton settingsBtn = new JButton(AllIcons.General.GearPlain);
        settingsBtn.setToolTipText("All Settings (Orchestration Defaults, Custom URLs, Custom OAuth)");
        settingsBtn.addActionListener(e -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TriModelConfigurable.class);
            loadSettingsIntoControls();
            refreshProviderBadges();
        });

        rightControls.add(quickSetupBtn);
        rightControls.add(newSessionBtn);
        rightControls.add(settingsBtn);

        row1.add(modePanel, BorderLayout.CENTER);
        row1.add(rightControls, BorderLayout.EAST);

        // Row 2: Responsive 2x2 Role assignment grid (never clips in narrow PyCharm sidebars)
        JPanel row2 = new JPanel(new GridLayout(2, 2, 8, 4));
        row2.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(4, 2)
        ));
        row2.add(labeledRoleCell("Architect:", architectCombo));
        row2.add(labeledRoleCell("Coder:", coderCombo));
        row2.add(labeledRoleCell("Reviewer:", reviewerCombo));
        row2.add(labeledRoleCell("Chair:", chairCombo));

        header.add(row1);
        header.add(row2);
        return header;
    }

    private static JPanel labeledRoleCell(String label, ComboBox<ModelProviderType> combo) {
        JPanel cell = new JPanel(new BorderLayout(4, 0));
        cell.add(new JBLabel(label), BorderLayout.WEST);
        cell.add(combo, BorderLayout.CENTER);
        return cell;
    }

    private JBSplitter buildCenterSplitter() {
        JBSplitter splitter = new JBSplitter(true, 0.66f);
        splitter.setFirstComponent(buildOutputPanel());
        splitter.setSecondComponent(buildPromptPanel());
        return splitter;
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        // Status ribbon
        JPanel statusBar = new JPanel(new BorderLayout(6, 0));
        statusBar.setBorder(JBUI.Borders.empty(2, 2));
        progressBar.setPreferredSize(new Dimension(90, 14));
        progressBar.setVisible(false);

        JPanel statusLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusLeft.add(progressBar);
        statusLeft.add(statusLabel);

        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        metricsLabel.setForeground(JBColor.GRAY);
        copyMarkdownBtn.setEnabled(false);
        statusRight.add(metricsLabel);
        statusRight.add(copyMarkdownBtn);

        statusBar.add(statusLeft, BorderLayout.CENTER);
        statusBar.add(statusRight, BorderLayout.EAST);

        // Tabs
        outputTabs.addTab("Final Output", new JBScrollPane(finalOutputPane));
        outputTabs.addTab("Timeline", new JBScrollPane(timelinePane));
        for (ModelProviderType type : ModelProviderType.values()) {
            JEditorPane pane = createHtmlPane();
            providerPanes.put(type, pane);
            outputTabs.addTab(type.getShortName(), new JBScrollPane(pane));
        }
        outputTabs.addTab("Code Changes (0)", buildCodeChangesTab());

        panel.add(statusBar, BorderLayout.NORTH);
        panel.add(outputTabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildCodeChangesTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.empty(4));

        changeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        changePreviewArea.setEditable(false);
        changePreviewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JBSplitter codeSplit = new JBSplitter(false, 0.30f);
        codeSplit.setFirstComponent(new JBScrollPane(changeList));
        codeSplit.setSecondComponent(new JBScrollPane(changePreviewArea));

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        actions.add(diffBtn);
        actions.add(applySelectedBtn);
        actions.add(replaceEditorBtn);
        actions.add(applyAllBtn);
        actions.add(copyCodeBtn);
        setCodeButtonsEnabled(false);

        panel.add(actions, BorderLayout.NORTH);
        panel.add(codeSplit, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPromptPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.emptyTop(4));

        // Context + Preset bar
        JPanel topContext = new JPanel();
        topContext.setLayout(new BoxLayout(topContext, BoxLayout.Y_AXIS));

        JPanel ctxRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        ctxRow.add(includeActiveFileCheck);
        ctxRow.add(selectionOnlyCheck);
        ctxRow.add(includeDiagnosticsCheck);

        JButton addFilesBtn = new JButton("+ Add Files", AllIcons.Actions.AddFile);
        addFilesBtn.addActionListener(e -> chooseProjectFiles());
        JButton clearFilesBtn = new JButton("Clear");
        clearFilesBtn.addActionListener(e -> {
            attachedProjectFiles.clear();
            updateAttachedFilesLabel();
        });
        attachedFilesLabel.setForeground(JBColor.GRAY);

        ctxRow.add(addFilesBtn);
        ctxRow.add(clearFilesBtn);
        ctxRow.add(attachedFilesLabel);

        JPanel presetsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        presetsRow.add(new JBLabel("Quick:"));
        presetsRow.add(createPresetButton("Write Code", OrchestrationMode.PIPELINE,
                "Design and implement clean, well-typed Python code for: "));
        presetsRow.add(createPresetButton("Deep Audit", OrchestrationMode.DEEP_ANALYSIS,
                "Perform a thorough tri-model analysis (bugs, security, architecture, performance) of the attached code."));
        presetsRow.add(createPresetButton("Fix Bugs", OrchestrationMode.ITERATIVE_LOOP,
                "Diagnose and fix all bugs, edge cases, and IDE diagnostics in the attached code."));
        presetsRow.add(createPresetButton("Council", OrchestrationMode.PARALLEL_COUNCIL,
                "Compare architectural options and produce the strongest consensus implementation for: "));
        presetsRow.add(createPresetButton("Write Tests", OrchestrationMode.ITERATIVE_LOOP,
                "Write comprehensive pytest unit tests covering normal flows and edge cases for the attached code."));

        topContext.add(ctxRow);
        topContext.add(presetsRow);

        // Prompt text area
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setBorder(JBUI.Borders.empty(6));
        promptArea.getEmptyText().setText(
                "Describe what you want Claude, ChatGPT, and Gemini to build, analyze, or orchestrate... (Cmd/Ctrl+Enter to run)");

        // Bottom Run bar
        JPanel bottomBar = new JPanel(new BorderLayout());
        JPanel leftInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        historyLabel.setForeground(JBColor.GRAY);
        leftInfo.add(historyLabel);

        JPanel runControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        stopBtn.setEnabled(false);
        runControls.add(stopBtn);
        runControls.add(runBtn);

        bottomBar.add(leftInfo, BorderLayout.WEST);
        bottomBar.add(runControls, BorderLayout.EAST);

        panel.add(topContext, BorderLayout.NORTH);
        panel.add(new JBScrollPane(promptArea), BorderLayout.CENTER);
        panel.add(bottomBar, BorderLayout.SOUTH);
        return panel;
    }

    private JButton createPresetButton(String label, OrchestrationMode mode, String template) {
        JButton btn = new JButton(label);
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.addActionListener(e -> {
            modeCombo.setSelectedItem(mode);
            if (promptArea.getText().isBlank()) {
                promptArea.setText(template);
            }
            promptArea.requestFocusInWindow();
        });
        return btn;
    }

    private void wireEvents() {
        // Persist mode/role changes
        modeCombo.addActionListener(e -> {
            if (updatingControls) return;
            OrchestrationMode selected = (OrchestrationMode) modeCombo.getSelectedItem();
            if (selected != null) {
                TriModelSettings.getInstance().setDefaultMode(selected);
                modeCombo.setToolTipText(selected.getDescription());
            }
        });

        Runnable persistRoles = () -> {
            if (updatingControls) return;
            RoleAssignment roles = getSelectedRoles();
            TriModelSettings.getInstance().setRoleAssignment(roles);
        };
        architectCombo.addActionListener(e -> persistRoles.run());
        coderCombo.addActionListener(e -> persistRoles.run());
        reviewerCombo.addActionListener(e -> persistRoles.run());
        chairCombo.addActionListener(e -> persistRoles.run());

        runBtn.addActionListener(e -> startOrchestration());
        stopBtn.addActionListener(e -> {
            cancelFlag.set(true);
            statusLabel.setText("Cancelling in-flight model calls...");
            stopBtn.setEnabled(false);
        });

        // Cmd+Enter / Ctrl+Enter shortcut in prompt area
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        promptArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, shortcutMask), "runOrchestration");
        promptArea.getActionMap().put("runOrchestration", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!isRunning) {
                    startOrchestration();
                }
            }
        });

        // Code changes tab listeners
        changeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ProposedFileChange selected = getSelectedChange();
                changePreviewArea.setText(selected == null ? "" : selected.code());
                changePreviewArea.setCaretPosition(0);
            }
        });

        diffBtn.addActionListener(e -> {
            ProposedFileChange selected = getSelectedChange();
            if (selected != null) {
                CodeApplyService.showDiffForChange(project, selected);
            }
        });

        applySelectedBtn.addActionListener(e -> {
            ProposedFileChange selected = getSelectedChange();
            if (selected != null) {
                CodeApplyService.applyFileChanges(project, List.of(selected));
            }
        });

        replaceEditorBtn.addActionListener(e -> {
            ProposedFileChange selected = getSelectedChange();
            if (selected != null) {
                CodeApplyService.replaceActiveEditorOrSelection(project, selected.code());
            }
        });

        applyAllBtn.addActionListener(e -> {
            if (currentChanges.isEmpty()) return;
            ApplyChangesDialog dialog = new ApplyChangesDialog(project, currentChanges);
            if (dialog.showAndGet()) {
                List<ProposedFileChange> chosen = dialog.getSelectedChanges();
                if (!chosen.isEmpty()) {
                    CodeApplyService.applyFileChanges(project, chosen);
                }
            }
        });

        copyCodeBtn.addActionListener(e -> {
            ProposedFileChange selected = getSelectedChange();
            if (selected != null) {
                copyToClipboard(selected.code());
                CodeApplyService.notify(project, "Copied code for " + selected.displayName() + " to clipboard.", NotificationType.INFORMATION);
            }
        });

        copyMarkdownBtn.addActionListener(e -> {
            if (!lastFinalMarkdown.isBlank()) {
                copyToClipboard(lastFinalMarkdown);
                CodeApplyService.notify(project, "Copied orchestration output to clipboard.", NotificationType.INFORMATION);
            }
        });
    }

    public void applyQuickActionPreset(
            OrchestrationMode mode,
            String prompt,
            boolean selectionOnly,
            boolean includeDiagnostics,
            boolean autoRun
    ) {
        if (mode != null) {
            modeCombo.setSelectedItem(mode);
        }
        includeActiveFileCheck.setSelected(true);
        selectionOnlyCheck.setSelected(selectionOnly);
        includeDiagnosticsCheck.setSelected(includeDiagnostics);
        if (prompt != null && !prompt.isBlank()) {
            promptArea.setText(prompt);
        }
        refreshProviderBadges();
        promptArea.requestFocusInWindow();
        if (autoRun && !isRunning) {
            startOrchestration();
        }
    }

    private void startOrchestration() {
        if (isRunning) return;
        String prompt = promptArea.getText().trim();
        if (prompt.isBlank()) {
            statusLabel.setText("Enter a task or analysis prompt first.");
            promptArea.requestFocusInWindow();
            return;
        }

        TriModelSettings settings = TriModelSettings.getInstance();
        Map<ModelProviderType, ModelClient> clients = ModelClientFactory.createConfiguredClients(settings);
        refreshProviderBadges();

        if (clients.isEmpty()) {
            statusLabel.setText("No models signed in yet. Opening Quick Setup...");
            openQuickSetup();
            return;
        }

        // Gather IDE context on EDT before launching background orchestration
        List<ContextAttachment> attachments = collectAttachments();
        OrchestrationMode mode = (OrchestrationMode) modeCombo.getSelectedItem();
        RoleAssignment roles = getSelectedRoles();

        OrchestrationEngine.RunOptions options = new OrchestrationEngine.RunOptions(
                mode,
                roles,
                attachments,
                projectService.getHistorySnapshot(),
                settings.getMaxReviewRounds(),
                settings.isCouncilCrossCritique(),
                settings.getMaxOutputTokens(),
                settings.getRequestTimeoutSeconds(),
                settings.getCustomSystemInstructions()
        );

        // Reset live UI state
        isRunning = true;
        cancelFlag = new AtomicBoolean(false);
        liveStages.clear();
        liveTimelineNotes.setLength(0);
        currentChanges = List.of();
        lastFinalMarkdown = "";
        updateCodeChangesUI(List.of());
        copyMarkdownBtn.setEnabled(false);

        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(true);
        metricsLabel.setText("");
        statusLabel.setText("Starting " + mode.getDisplayName() + " (" + clients.size() + " model(s) active)...");

        String runningBanner = "# Running " + mode.getDisplayName() + "\n\n"
                + "**Active Models:** " + formatProviderList(clients) + "\n\n"
                + "**Attached Context:** " + (attachments.isEmpty() ? "None" : attachments.size() + " file(s)") + "\n\n"
                + "*Live progress is updating in the **Timeline** tab...*";
        setHtml(finalOutputPane, runningBanner);
        setHtml(timelinePane, "# Orchestration Timeline\n\n*Executing stages...*");
        for (ModelProviderType t : ModelProviderType.values()) {
            setHtml(providerPanes.get(t), clients.containsKey(t)
                    ? "### " + t.getFullName() + "\n\n*Waiting for stage output...*"
                    : "### " + t.getFullName() + "\n\n*Not enabled or API key not configured.*");
        }

        OrchestrationEngine engine = new OrchestrationEngine(clients);
        OrchestrationListener listener = new OrchestrationListener() {
            @Override
            public void onStageStarted(String stageId, String stageName, ModelProviderType provider, String modelId) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Running: " + stageName + " [" + provider.getShortName() + " / " + modelId + "]...");
                    liveTimelineNotes.append("- Running **").append(stageName).append("** via **")
                            .append(provider.getFullName()).append("** (`").append(modelId).append("`)...\n");
                    renderLiveTimeline();
                });
            }

            @Override
            public void onStageCompleted(StageResult stageResult) {
                SwingUtilities.invokeLater(() -> {
                    liveStages.add(stageResult);
                    String outcome = stageResult.isSuccess()
                            ? "Completed (" + stageResult.response().latencyMs() + "ms)"
                            : "FAILED (" + stageResult.response().errorMessage() + ")";
                    statusLabel.setText(stageResult.stageName() + " [" + stageResult.provider().getShortName() + "]: " + outcome);
                    renderLiveTimeline();
                    renderProviderTab(stageResult.provider());
                });
            }

            @Override
            public void onLog(String message) {
                SwingUtilities.invokeLater(() -> {
                    liveTimelineNotes.append("> ").append(message).append("\n\n");
                    renderLiveTimeline();
                });
            }

            @Override
            public void onCompleted(OrchestrationResult result) {
                SwingUtilities.invokeLater(() -> finishRun(prompt, result));
            }

            @Override
            public void onFailed(String errorMessage) {
                SwingUtilities.invokeLater(() -> {
                    isRunning = false;
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    progressBar.setVisible(false);
                    statusLabel.setText("Error: " + errorMessage);
                    setHtml(finalOutputPane, "# Orchestration Failed\n\n> " + errorMessage);
                });
            }
        };

        ApplicationManager.getApplication().executeOnPooledThread(() ->
                engine.execute(prompt, options, listener, cancelFlag::get));
    }

    private void finishRun(String prompt, OrchestrationResult result) {
        isRunning = false;
        runBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);

        lastFinalMarkdown = result.finalOutput();
        copyMarkdownBtn.setEnabled(!lastFinalMarkdown.isBlank());

        if (!result.cancelled() && !result.finalOutput().isBlank()) {
            projectService.appendTurn(prompt, result.finalOutput());
            updateHistoryLabel();
        }

        StringBuilder finalView = new StringBuilder();
        if (result.hasErrors()) {
            finalView.append("> **Notice (partial errors occurred):** ")
                    .append(result.errorSummary().replace("\n", " | "))
                    .append("\n\n");
        }
        finalView.append(result.finalOutput());
        setHtml(finalOutputPane, finalView.toString());

        liveStages.clear();
        liveStages.addAll(result.stages());
        renderLiveTimeline();
        for (ModelProviderType type : ModelProviderType.values()) {
            renderProviderTab(type);
        }

        updateCodeChangesUI(result.proposedChanges());

        double secs = result.totalLatencyMs() / 1000.0;
        long totalTokens = result.totalInputTokens() + result.totalOutputTokens();
        metricsLabel.setText(String.format("%.1fs | %d tok | %d stage(s)", secs, totalTokens, result.stages().size()));
        statusLabel.setText(result.cancelled()
                ? "Cancelled by user."
                : "Completed " + result.mode().getDisplayName() + " (" + result.proposedChanges().size() + " code change(s) ready).");

        outputTabs.setSelectedIndex(0);
    }

    private void renderLiveTimeline() {
        StringBuilder md = new StringBuilder();
        md.append("# Orchestration Timeline\n\n");
        if (!liveTimelineNotes.isEmpty()) {
            md.append(liveTimelineNotes).append("\n---\n\n");
        }
        for (StageResult s : liveStages) {
            md.append("## ").append(s.stageName())
                    .append(" — **").append(s.provider().getFullName()).append("** (`").append(s.modelId()).append("`)\n");
            if (s.response() != null) {
                md.append("*Role:* ").append(s.roleDescription())
                        .append(" | *Latency:* ").append(s.response().latencyMs()).append(" ms")
                        .append(" | *Tokens:* ").append(s.response().inputTokens()).append(" in / ")
                        .append(s.response().outputTokens()).append(" out");
                if (!s.verdict().isBlank()) {
                    md.append(" | VERDICT: ").append(s.verdict());
                }
                md.append("\n\n");
                if (s.isSuccess()) {
                    md.append(s.response().content()).append("\n\n");
                } else {
                    md.append("> **Error:** ").append(s.response().errorMessage()).append("\n\n");
                }
            }
            md.append("---\n\n");
        }
        setHtml(timelinePane, md.toString());
    }

    private void renderProviderTab(ModelProviderType provider) {
        JEditorPane pane = providerPanes.get(provider);
        if (pane == null) return;
        List<StageResult> matching = liveStages.stream().filter(s -> s.provider() == provider).toList();
        if (matching.isEmpty()) {
            setHtml(pane, "### " + provider.getFullName() + "\n\n*No completed stages for " + provider.getShortName() + " in this run.*");
            return;
        }
        StringBuilder md = new StringBuilder();
        md.append("# ").append(provider.getFullName()).append(" Contributions\n\n");
        for (StageResult s : matching) {
            md.append("## ").append(s.stageName()).append(" (`").append(s.modelId()).append("`)\n");
            if (s.response() != null) {
                md.append("*Latency:* ").append(s.response().latencyMs()).append(" ms | *Tokens:* ")
                        .append(s.response().inputTokens()).append(" in / ").append(s.response().outputTokens()).append(" out\n\n");
                if (s.isSuccess()) {
                    if (!s.response().thinking().isBlank()) {
                        md.append("> **Reasoning Summary:** ").append(s.response().thinking().replace("\n", " ")).append("\n\n");
                    }
                    md.append(s.response().content()).append("\n\n");
                } else {
                    md.append("> **Error:** ").append(s.response().errorMessage()).append("\n\n");
                }
            }
            md.append("---\n\n");
        }
        setHtml(pane, md.toString());
    }

    private void updateCodeChangesUI(List<ProposedFileChange> changes) {
        this.currentChanges = changes == null ? List.of() : List.copyOf(changes);
        changeListModel.clear();
        for (ProposedFileChange c : currentChanges) {
            int lines = c.code().split("\n", -1).length;
            changeListModel.addElement(c.displayName() + " (" + lines + " lines)");
        }
        int codeTabIdx = outputTabs.getTabCount() - 1;
        outputTabs.setTitleAt(codeTabIdx, "Code Changes (" + currentChanges.size() + ")");

        if (!currentChanges.isEmpty()) {
            changeList.setSelectedIndex(0);
            changePreviewArea.setText(currentChanges.get(0).code());
            changePreviewArea.setCaretPosition(0);
            setCodeButtonsEnabled(true);
        } else {
            changePreviewArea.setText("// No code blocks extracted yet.\n// Run an orchestration that generates code to inspect, diff, and apply changes here.");
            setCodeButtonsEnabled(false);
        }
    }

    private void setCodeButtonsEnabled(boolean enabled) {
        diffBtn.setEnabled(enabled);
        applySelectedBtn.setEnabled(enabled);
        replaceEditorBtn.setEnabled(enabled);
        applyAllBtn.setEnabled(enabled);
        copyCodeBtn.setEnabled(enabled);
    }

    private ProposedFileChange getSelectedChange() {
        int idx = changeList.getSelectedIndex();
        if (idx < 0 || idx >= currentChanges.size()) {
            return null;
        }
        return currentChanges.get(idx);
    }

    private List<ContextAttachment> collectAttachments() {
        List<ContextAttachment> list = new ArrayList<>();
        boolean includeDiags = includeDiagnosticsCheck.isSelected();

        if (includeActiveFileCheck.isSelected()) {
            ContextAttachment active = IdeContextService.captureActiveEditor(
                    project, selectionOnlyCheck.isSelected(), includeDiags);
            if (active != null) {
                list.add(active);
            }
        }
        for (VirtualFile vf : attachedProjectFiles.values()) {
            if (vf == null || !vf.isValid()) continue;
            String rel = IdeContextService.relativePath(project, vf);
            boolean duplicate = list.stream().anyMatch(a -> a.relativePath().equals(rel));
            if (duplicate) continue;
            ContextAttachment att = IdeContextService.readVirtualFile(project, vf, includeDiags);
            if (att != null) {
                list.add(att);
            }
        }
        return list;
    }

    private void chooseProjectFiles() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor();
        descriptor.setTitle("Attach Project Files to Tri-Model Context");
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        VirtualFile[] chosen = FileChooser.chooseFiles(descriptor, project, baseDir);
        for (VirtualFile vf : chosen) {
            if (vf != null && !vf.isDirectory()) {
                attachedProjectFiles.put(vf.getPath(), vf);
            }
        }
        updateAttachedFilesLabel();
    }

    private void updateAttachedFilesLabel() {
        if (attachedProjectFiles.isEmpty()) {
            attachedFilesLabel.setText("No extra files attached");
        } else {
            List<String> names = attachedProjectFiles.values().stream()
                    .map(VirtualFile::getName)
                    .limit(3)
                    .toList();
            String extra = attachedProjectFiles.size() > 3 ? " +" + (attachedProjectFiles.size() - 3) + " more" : "";
            attachedFilesLabel.setText("Attached: " + String.join(", ", names) + extra);
        }
    }

    private void resetSession() {
        projectService.clearHistory();
        liveStages.clear();
        liveTimelineNotes.setLength(0);
        lastFinalMarkdown = "";
        updateCodeChangesUI(List.of());
        copyMarkdownBtn.setEnabled(false);
        metricsLabel.setText("");
        updateHistoryLabel();
        renderWelcomeView();
        statusLabel.setText("Started new orchestration session.");
    }

    private void updateHistoryLabel() {
        int turns = projectService.getHistorySnapshot().size() / 2;
        historyLabel.setText("Session: " + turns + " turn" + (turns == 1 ? "" : "s"));
    }

    private void loadSettingsIntoControls() {
        updatingControls = true;
        try {
            TriModelSettings settings = TriModelSettings.getInstance();
            modeCombo.setSelectedItem(settings.getDefaultMode());
            modeCombo.setToolTipText(settings.getDefaultMode().getDescription());
            RoleAssignment roles = settings.getRoleAssignment();
            architectCombo.setSelectedItem(roles.getArchitect());
            coderCombo.setSelectedItem(roles.getCoder());
            reviewerCombo.setSelectedItem(roles.getReviewer());
            chairCombo.setSelectedItem(roles.getSynthesizer());
        } finally {
            updatingControls = false;
        }
    }

    private RoleAssignment getSelectedRoles() {
        return new RoleAssignment(
                (ModelProviderType) architectCombo.getSelectedItem(),
                (ModelProviderType) coderCombo.getSelectedItem(),
                (ModelProviderType) reviewerCombo.getSelectedItem(),
                (ModelProviderType) chairCombo.getSelectedItem()
        );
    }

    public void refreshProviderBadges() {
        TriModelSettings settings = TriModelSettings.getInstance();
        for (ModelProviderType type : ModelProviderType.values()) {
            JBLabel pill = statusBadges.get(type);
            if (pill == null) continue;
            ProviderConfig cfg = settings.getProviderConfig(type);
            boolean ready = ModelClientFactory.isReady(type, cfg);
            String label = ModelClientFactory.readinessLabel(type, cfg);
            pill.setText((ready ? "● " : "○ ") + type.getShortName());
            pill.setToolTipText(type.getFullName() + ": " + label + " (" + cfg.effectiveModelId(type) + ")");
            if (ready) {
                pill.setForeground(Color.WHITE);
                pill.setBackground(new JBColor(Color.decode(type.getLightAccentHex()), Color.decode(type.getDarkAccentHex())));
            } else {
                pill.setForeground(JBColor.GRAY);
                pill.setBackground(new JBColor(0xE8E9EC, 0x35373C));
            }
        }
    }

    private void openQuickSetup() {
        QuickSetupDialog dialog = new QuickSetupDialog(project);
        dialog.showAndGet();
        loadSettingsIntoControls();
        refreshProviderBadges();
    }

    private void renderWelcomeView() {
        String welcome = """
                # Tri-Model AI Orchestrator for PyCharm

                Combine **Claude**, **ChatGPT**, and **Gemini** in one unified workflow to write, review, analyze, and refine code.

                ### Available Orchestration Modes
                - **Role Pipeline (`Architect -> Coder -> Reviewer -> Finalizer`)**: Assign each model to its strongest role and pass structured handoffs end-to-end.
                - **Iterative Code & Dual-Review Loop (`Coder <-> 2 Reviewers`)**: One model writes code while the other two audit correctness, security, and architecture in parallel — automatically triggering refinement rounds until consensus.
                - **Parallel Council & Consensus (`All 3 -> Cross-Critique -> Chair`)**: All three models solve independently in parallel, critique each other, and the Chair synthesizes the best answer.
                - **Tri-Model Deep Code Audit**: Parallel 3-lens inspection (Bugs & Types, Security & Reliability, Architecture & Performance) consolidated into a severity-ranked report.
                - **Parallel Side-by-Side Compare**: Compare Claude, ChatGPT, and Gemini side-by-side on the exact same prompt.

                ### Getting Started (2 Simple Steps)
                1. Click **Sign In** in the top-right bar (or click any model pill above) to connect **Claude**, **ChatGPT**, and **Gemini** on one screen — either with 1-click **Browser Sign-In** (no API key or Client ID needed) or by pasting an **API Key**.
                2. Open any file in PyCharm, type your request below, and press **Run Orchestration** (`Cmd/Ctrl+Enter`).
                3. Inspect proposed edits in the **Code Changes** tab, preview in PyCharm's native **Diff Viewer**, and apply with full Undo support.

                > **Privacy Notice:** Nothing is sent until you explicitly run a prompt. Prompts and attached context are transmitted directly to your configured AI provider endpoints with zero telemetry or intermediary servers.
                """;
        setHtml(finalOutputPane, welcome);
        setHtml(timelinePane, "### Orchestration Timeline\n\n*Run an orchestration to trace every stage, latency, token count, and reviewer verdict here.*");
        for (ModelProviderType type : ModelProviderType.values()) {
            setHtml(providerPanes.get(type), "### " + type.getFullName() + "\n\n*Model output will appear here during runs.*");
        }
    }

    private static JEditorPane createHtmlPane() {
        JEditorPane pane = new JEditorPane("text/html", "");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return pane;
    }

    private static void setHtml(JEditorPane pane, String markdown) {
        pane.setText(MarkdownHtmlRenderer.toHtmlDocument(markdown));
        pane.setCaretPosition(0);
    }

    private static String formatProviderList(Map<ModelProviderType, ModelClient> clients) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ModelProviderType, ModelClient> entry : clients.entrySet()) {
            parts.add(entry.getKey().getShortName() + " (`" + entry.getValue().getModelId() + "`)");
        }
        return String.join(", ", parts);
    }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
