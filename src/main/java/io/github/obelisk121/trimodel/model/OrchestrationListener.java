package io.github.obelisk121.trimodel.model;

/**
 * Progress observer invoked by OrchestrationEngine as stages execute.
 */
public interface OrchestrationListener {
    default void onStageStarted(String stageId, String stageName, ModelProviderType provider, String modelId) {
    }

    default void onStageCompleted(StageResult stageResult) {
    }

    default void onLog(String message) {
    }

    default void onCompleted(OrchestrationResult result) {
    }

    default void onFailed(String errorMessage) {
    }

    OrchestrationListener NO_OP = new OrchestrationListener() {
    };
}
