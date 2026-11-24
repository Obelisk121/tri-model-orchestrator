package io.github.obelisk121.trimodel.model;

/**
 * Captures the execution result of a single orchestration stage.
 */
public record StageResult(
        String stageId,
        String stageName,
        String roleDescription,
        ModelProviderType provider,
        String modelId,
        Status status,
        ModelResponse response,
        String verdict
) {
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public StageResult {
        stageId = stageId == null ? "" : stageId;
        stageName = stageName == null ? "" : stageName;
        roleDescription = roleDescription == null ? "" : roleDescription;
        modelId = modelId == null ? "" : modelId;
        status = status == null ? Status.PENDING : status;
        verdict = verdict == null ? "" : verdict;
    }

    public boolean isSuccess() {
        return status == Status.COMPLETED && response != null && response.success();
    }
}
