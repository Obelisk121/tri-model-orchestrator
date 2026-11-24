package io.github.obelisk121.trimodel.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level persistent configuration for Tri-Model AI Orchestrator.
 */
@Service(Service.Level.APP)
@State(
        name = "TriModelOrchestratorSettings",
        storages = @Storage("tri-model-orchestrator.xml")
)
public final class TriModelSettings implements PersistentStateComponent<TriModelSettings.State> {

    public static class State {
        public ProviderConfig claude = ProviderConfig.defaultsFor(ModelProviderType.CLAUDE);
        public ProviderConfig chatgpt = ProviderConfig.defaultsFor(ModelProviderType.CHATGPT);
        public ProviderConfig gemini = ProviderConfig.defaultsFor(ModelProviderType.GEMINI);

        public OrchestrationMode defaultMode = OrchestrationMode.PIPELINE;
        public ModelProviderType architectRole = ModelProviderType.GEMINI;
        public ModelProviderType coderRole = ModelProviderType.CLAUDE;
        public ModelProviderType reviewerRole = ModelProviderType.CHATGPT;
        public ModelProviderType synthesizerRole = ModelProviderType.CLAUDE;

        public int maxReviewRounds = 2;
        public boolean councilCrossCritique = true;
        public int requestTimeoutSeconds = 120;
        public int maxOutputTokens = 8192;
        public String customSystemInstructions = "";
    }

    private State state = new State();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public void addChangeListener(Runnable listener) {
        if (listener != null) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        if (listener != null) changeListeners.remove(listener);
    }

    public void notifySettingsChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception ignored) {
            }
        }
    }

    public static TriModelSettings getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return new TriModelSettings();
        }
        return ApplicationManager.getApplication().getService(TriModelSettings.class);
    }

    @Override
    public @Nullable State getState() {
        ensureDefaults(state);
        return state;
    }

    @Override
    public void loadState(@NotNull State loadedState) {
        XmlSerializerUtil.copyBean(loadedState, this.state);
        ensureDefaults(this.state);
    }

    private static void ensureDefaults(State s) {
        if (s.claude == null) s.claude = ProviderConfig.defaultsFor(ModelProviderType.CLAUDE);
        if (s.chatgpt == null) s.chatgpt = ProviderConfig.defaultsFor(ModelProviderType.CHATGPT);
        if (s.gemini == null) s.gemini = ProviderConfig.defaultsFor(ModelProviderType.GEMINI);
        if (s.defaultMode == null) s.defaultMode = OrchestrationMode.PIPELINE;
        if (s.architectRole == null) s.architectRole = ModelProviderType.GEMINI;
        if (s.coderRole == null) s.coderRole = ModelProviderType.CLAUDE;
        if (s.reviewerRole == null) s.reviewerRole = ModelProviderType.CHATGPT;
        if (s.synthesizerRole == null) s.synthesizerRole = ModelProviderType.CLAUDE;
        if (s.maxReviewRounds < 1) s.maxReviewRounds = 1;
        if (s.maxReviewRounds > 4) s.maxReviewRounds = 4;
        if (s.requestTimeoutSeconds < 15) s.requestTimeoutSeconds = 15;
        if (s.maxOutputTokens < 512) s.maxOutputTokens = 512;
        if (s.customSystemInstructions == null) s.customSystemInstructions = "";
    }

    public ProviderConfig getProviderConfig(ModelProviderType type) {
        ensureDefaults(state);
        return switch (type) {
            case CLAUDE -> state.claude;
            case CHATGPT -> state.chatgpt;
            case GEMINI -> state.gemini;
        };
    }

    public void setProviderConfig(ModelProviderType type, ProviderConfig config) {
        if (config == null) return;
        switch (type) {
            case CLAUDE -> state.claude = config.copy();
            case CHATGPT -> state.chatgpt = config.copy();
            case GEMINI -> state.gemini = config.copy();
        }
    }

    public RoleAssignment getRoleAssignment() {
        ensureDefaults(state);
        return new RoleAssignment(
                state.architectRole,
                state.coderRole,
                state.reviewerRole,
                state.synthesizerRole
        );
    }

    public void setRoleAssignment(RoleAssignment roles) {
        if (roles == null) return;
        state.architectRole = roles.getArchitect();
        state.coderRole = roles.getCoder();
        state.reviewerRole = roles.getReviewer();
        state.synthesizerRole = roles.getSynthesizer();
    }

    public List<ModelProviderType> getEnabledProviders() {
        List<ModelProviderType> list = new ArrayList<>();
        for (ModelProviderType type : ModelProviderType.values()) {
            if (getProviderConfig(type).enabled) {
                list.add(type);
            }
        }
        return list;
    }

    public OrchestrationMode getDefaultMode() {
        ensureDefaults(state);
        return state.defaultMode;
    }

    public void setDefaultMode(OrchestrationMode mode) {
        state.defaultMode = mode == null ? OrchestrationMode.PIPELINE : mode;
    }

    public int getMaxReviewRounds() {
        ensureDefaults(state);
        return state.maxReviewRounds;
    }

    public void setMaxReviewRounds(int rounds) {
        state.maxReviewRounds = Math.max(1, Math.min(4, rounds));
    }

    public boolean isCouncilCrossCritique() {
        return state.councilCrossCritique;
    }

    public void setCouncilCrossCritique(boolean councilCrossCritique) {
        state.councilCrossCritique = councilCrossCritique;
    }

    public int getRequestTimeoutSeconds() {
        ensureDefaults(state);
        return state.requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int timeoutSeconds) {
        state.requestTimeoutSeconds = Math.max(15, Math.min(600, timeoutSeconds));
    }

    public int getMaxOutputTokens() {
        ensureDefaults(state);
        return state.maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        state.maxOutputTokens = Math.max(512, Math.min(65536, maxOutputTokens));
    }

    public String getCustomSystemInstructions() {
        ensureDefaults(state);
        return state.customSystemInstructions;
    }

    public void setCustomSystemInstructions(String instructions) {
        state.customSystemInstructions = instructions == null ? "" : instructions;
    }
}
