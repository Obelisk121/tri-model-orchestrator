package io.github.obelisk121.trimodel.model;

import java.util.List;

/**
 * Aggregated result of a full multi-model orchestration run.
 */
public record OrchestrationResult(
        OrchestrationMode mode,
        String userPrompt,
        List<StageResult> stages,
        String finalOutput,
        List<ProposedFileChange> proposedChanges,
        long totalLatencyMs,
        long totalInputTokens,
        long totalOutputTokens,
        boolean cancelled,
        String errorSummary
) {
    public OrchestrationResult {
        stages = stages == null ? List.of() : List.copyOf(stages);
        finalOutput = finalOutput == null ? "" : finalOutput;
        proposedChanges = proposedChanges == null ? List.of() : List.copyOf(proposedChanges);
        errorSummary = errorSummary == null ? "" : errorSummary;
    }

    public boolean hasErrors() {
        return !errorSummary.isBlank();
    }

    public List<StageResult> stagesForProvider(ModelProviderType provider) {
        return stages.stream().filter(s -> s.provider() == provider).toList();
    }
}
