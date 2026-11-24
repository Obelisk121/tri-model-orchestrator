package io.github.obelisk121.trimodel.model;

/**
 * Multi-model collaboration and orchestration strategies.
 */
public enum OrchestrationMode {
    PIPELINE(
            "Role Pipeline (Architect -> Coder -> Reviewer -> Finalizer)",
            "Sequential engineering pipeline: Architect plans, Coder implements, Reviewer audits, Finalizer integrates."
    ),
    ITERATIVE_LOOP(
            "Iterative Code & Dual-Review Loop (Coder <-> 2 Reviewers)",
            "Coder writes code while the other two models review in parallel (Logic/Bugs & Security/Architecture) and trigger auto-refinement rounds."
    ),
    PARALLEL_COUNCIL(
            "Parallel Council & Consensus (All 3 -> Critique -> Chair)",
            "Claude, ChatGPT, and Gemini solve the task independently in parallel, cross-critique each other, and the Chair synthesizes the best solution."
    ),
    DEEP_ANALYSIS(
            "Tri-Model Deep Code Audit (3 Parallel Lenses + Report)",
            "All three models analyze your code in parallel (Bugs/Types, Security/Reliability, Architecture/Performance) and produce a unified severity-ranked report."
    ),
    SIDE_BY_SIDE(
            "Parallel Side-by-Side Compare (All Enabled Models)",
            "Sends the exact same prompt and context to all enabled models in parallel so you can compare Claude, ChatGPT, and Gemini directly."
    );

    private final String displayName;
    private final String description;

    OrchestrationMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
