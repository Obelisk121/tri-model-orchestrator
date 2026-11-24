package io.github.obelisk121.trimodel.model;

import java.util.Objects;

/**
 * Maps orchestration roles to specific AI providers.
 */
public final class RoleAssignment {
    private ModelProviderType architect;
    private ModelProviderType coder;
    private ModelProviderType reviewer;
    private ModelProviderType synthesizer;

    public RoleAssignment() {
        this(
                ModelProviderType.GEMINI,
                ModelProviderType.CLAUDE,
                ModelProviderType.CHATGPT,
                ModelProviderType.CLAUDE
        );
    }

    public RoleAssignment(
            ModelProviderType architect,
            ModelProviderType coder,
            ModelProviderType reviewer,
            ModelProviderType synthesizer
    ) {
        this.architect = Objects.requireNonNullElse(architect, ModelProviderType.GEMINI);
        this.coder = Objects.requireNonNullElse(coder, ModelProviderType.CLAUDE);
        this.reviewer = Objects.requireNonNullElse(reviewer, ModelProviderType.CHATGPT);
        this.synthesizer = Objects.requireNonNullElse(synthesizer, ModelProviderType.CLAUDE);
    }

    public ModelProviderType getArchitect() {
        return architect;
    }

    public void setArchitect(ModelProviderType architect) {
        this.architect = Objects.requireNonNullElse(architect, ModelProviderType.GEMINI);
    }

    public ModelProviderType getCoder() {
        return coder;
    }

    public void setCoder(ModelProviderType coder) {
        this.coder = Objects.requireNonNullElse(coder, ModelProviderType.CLAUDE);
    }

    public ModelProviderType getReviewer() {
        return reviewer;
    }

    public void setReviewer(ModelProviderType reviewer) {
        this.reviewer = Objects.requireNonNullElse(reviewer, ModelProviderType.CHATGPT);
    }

    public ModelProviderType getSynthesizer() {
        return synthesizer;
    }

    public void setSynthesizer(ModelProviderType synthesizer) {
        this.synthesizer = Objects.requireNonNullElse(synthesizer, ModelProviderType.CLAUDE);
    }

    public RoleAssignment copy() {
        return new RoleAssignment(architect, coder, reviewer, synthesizer);
    }
}
