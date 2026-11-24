package io.github.obelisk121.trimodel.orchestration;

import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.ContextAttachment;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;
import io.github.obelisk121.trimodel.model.OrchestrationListener;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.OrchestrationResult;
import io.github.obelisk121.trimodel.model.ProposedFileChange;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import io.github.obelisk121.trimodel.model.StageResult;
import io.github.obelisk121.trimodel.provider.ModelClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-model orchestration engine coordinating Claude, ChatGPT, and Gemini across
 * sequential pipelines, iterative dual-review loops, parallel councils, deep audits,
 * and side-by-side comparisons.
 */
public final class OrchestrationEngine {

    private static final Pattern VERDICT_PATTERN = Pattern.compile("(?im)^\\s*VERDICT\\s*:\\s*(APPROVE|REVISE)\\b");

    public record RunOptions(
            OrchestrationMode mode,
            RoleAssignment roles,
            List<ContextAttachment> attachments,
            List<ChatMessage> history,
            int maxReviewRounds,
            boolean councilCrossCritique,
            int maxOutputTokens,
            int timeoutSeconds,
            String customSystemInstructions
    ) {
        public RunOptions {
            mode = mode == null ? OrchestrationMode.PIPELINE : mode;
            roles = roles == null ? new RoleAssignment() : roles.copy();
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            history = history == null ? List.of() : List.copyOf(history);
            maxReviewRounds = Math.max(1, Math.min(4, maxReviewRounds));
            maxOutputTokens = maxOutputTokens <= 0 ? 8192 : maxOutputTokens;
            timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
            customSystemInstructions = customSystemInstructions == null ? "" : customSystemInstructions;
        }
    }

    private final Map<ModelProviderType, ModelClient> clients;

    public OrchestrationEngine(Map<ModelProviderType, ModelClient> clients) {
        this.clients = new EnumMap<>(ModelProviderType.class);
        if (clients != null) {
            this.clients.putAll(clients);
        }
    }

    public OrchestrationResult execute(
            String userPrompt,
            RunOptions options,
            OrchestrationListener listener,
            BooleanSupplier isCancelled
    ) {
        OrchestrationListener cb = listener == null ? OrchestrationListener.NO_OP : listener;
        BooleanSupplier cancel = isCancelled == null ? () -> false : isCancelled;
        long startNanos = System.nanoTime();

        if (userPrompt == null || userPrompt.isBlank()) {
            String err = "Prompt cannot be empty.";
            cb.onFailed(err);
            return emptyError(options.mode(), "", err, 0);
        }

        if (clients.isEmpty()) {
            String err = "No AI models are configured and enabled. Open Settings -> Tools -> Tri-Model AI Orchestrator "
                    + "to configure API keys or Local CLIs for Claude, ChatGPT, and Gemini.";
            cb.onFailed(err);
            return emptyError(options.mode(), userPrompt, err, 0);
        }

        List<StageResult> stages = Collections.synchronizedList(new ArrayList<>());
        String contextBlock = PromptTemplates.buildContextBlock(options.attachments());
        String basePromptWithContext = buildBaseUserPrompt(userPrompt, contextBlock);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, Math.max(1, clients.size())), r -> {
            Thread t = new Thread(r, "TriModel-Worker");
            t.setDaemon(true);
            return t;
        });

        try {
            String finalOutput = switch (options.mode()) {
                case PIPELINE -> runPipeline(userPrompt, basePromptWithContext, options, stages, cb, cancel);
                case ITERATIVE_LOOP -> runIterativeLoop(userPrompt, basePromptWithContext, options, stages, cb, cancel, pool);
                case PARALLEL_COUNCIL -> runParallelCouncil(userPrompt, basePromptWithContext, options, stages, cb, cancel, pool);
                case DEEP_ANALYSIS -> runDeepAnalysis(userPrompt, basePromptWithContext, options, stages, cb, cancel, pool);
                case SIDE_BY_SIDE -> runSideBySide(basePromptWithContext, options, stages, cb, cancel, pool);
            };

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long totalIn = 0;
            long totalOut = 0;
            List<String> errors = new ArrayList<>();
            List<StageResult> snapshot;
            synchronized (stages) {
                snapshot = new ArrayList<>(stages);
            }
            for (StageResult s : snapshot) {
                if (s.response() != null) {
                    totalIn += s.response().inputTokens();
                    totalOut += s.response().outputTokens();
                    if (!s.response().success() && !s.response().errorMessage().isBlank()) {
                        errors.add(s.stageName() + " (" + s.provider().getShortName() + "): " + s.response().errorMessage());
                    }
                }
            }

            List<ProposedFileChange> proposedChanges = extractChangesFromResult(finalOutput, snapshot);
            boolean wasCancelled = cancel.getAsBoolean();
            String errorSummary = errors.isEmpty() ? "" : String.join("\n", errors);

            OrchestrationResult result = new OrchestrationResult(
                    options.mode(),
                    userPrompt,
                    snapshot,
                    finalOutput,
                    proposedChanges,
                    elapsedMs,
                    totalIn,
                    totalOut,
                    wasCancelled,
                    errorSummary
            );
            cb.onCompleted(result);
            return result;
        } catch (Exception e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String msg = "Orchestration error: " + e.getMessage();
            cb.onFailed(msg);
            return emptyError(options.mode(), userPrompt, msg, elapsedMs);
        } finally {
            pool.shutdownNow();
        }
    }

    private String runPipeline(
            String rawPrompt,
            String basePromptWithContext,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel
    ) {
        RoleAssignment roles = options.roles();
        ModelClient architect = resolveClient(roles.getArchitect(), "Architect", cb);
        ModelClient coder = resolveClient(roles.getCoder(), "Coder", cb);
        ModelClient reviewer = resolveClient(roles.getReviewer(), "Reviewer", cb);
        ModelClient finalizer = resolveClient(roles.getSynthesizer(), "Finalizer", cb);

        // Stage 1: Architect
        StageResult s1 = runStage(
                "pipeline-1-architect",
                "1. Architecture & Plan",
                "Analyzes requirements & designs blueprint",
                architect,
                PromptTemplates.architectSystem(options.customSystemInstructions()),
                basePromptWithContext,
                options.history(),
                options,
                stages,
                cb,
                cancel
        );
        if (cancel.getAsBoolean()) return cancelledSummary(stages);

        // Stage 2: Coder
        String coderPrompt = basePromptWithContext
                + (s1.isSuccess() ? "\n\n## Architecture Blueprint (from " + s1.provider().getShortName() + ")\n" + s1.response().content() : "");
        StageResult s2 = runStage(
                "pipeline-2-coder",
                "2. Implementation",
                "Writes complete code following the blueprint",
                coder,
                PromptTemplates.coderSystem(options.customSystemInstructions()),
                coderPrompt,
                options.history(),
                options,
                stages,
                cb,
                cancel
        );
        if (cancel.getAsBoolean()) return cancelledSummary(stages);
        if (!s2.isSuccess()) {
            return s1.isSuccess()
                    ? "Coder stage failed (" + s2.response().errorMessage() + "). Architecture plan:\n\n" + s1.response().content()
                    : "Pipeline failed during Architecture and Implementation stages.";
        }

        // Stage 3: Reviewer
        String reviewPrompt = "## Original Developer Request\n" + rawPrompt + "\n\n"
                + PromptTemplates.buildContextBlock(options.attachments())
                + (s1.isSuccess() ? "\n## Architecture Blueprint\n" + s1.response().content() + "\n" : "")
                + "\n## Proposed Implementation (from " + s2.provider().getShortName() + ")\n"
                + s2.response().content();
        StageResult s3 = runStage(
                "pipeline-3-reviewer",
                "3. Independent Code Review",
                "Audits bugs, edge cases, security & style",
                reviewer,
                PromptTemplates.reviewerSystem(options.customSystemInstructions()),
                reviewPrompt,
                List.of(),
                options,
                stages,
                cb,
                cancel
        );
        if (cancel.getAsBoolean()) return cancelledSummary(stages);

        // Stage 4: Finalizer
        String finalizerPrompt = "## Original Developer Request\n" + rawPrompt + "\n\n"
                + PromptTemplates.buildContextBlock(options.attachments())
                + (s1.isSuccess() ? "\n## Architecture Blueprint (" + s1.provider().getShortName() + ")\n" + s1.response().content() + "\n" : "")
                + "\n## Implementation (" + s2.provider().getShortName() + ")\n" + s2.response().content() + "\n"
                + (s3.isSuccess() ? "\n## Independent Review (" + s3.provider().getShortName() + ")\n" + s3.response().content() : "");
        StageResult s4 = runStage(
                "pipeline-4-finalizer",
                "4. Final Synthesis & Code",
                "Integrates review fixes into production output",
                finalizer,
                PromptTemplates.finalizerSystem(options.customSystemInstructions()),
                finalizerPrompt,
                List.of(),
                options,
                stages,
                cb,
                cancel
        );

        return s4.isSuccess() ? s4.response().content() : s2.response().content();
    }

    private String runIterativeLoop(
            String rawPrompt,
            String basePromptWithContext,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel,
            ExecutorService pool
    ) {
        ModelClient coder = resolveClient(options.roles().getCoder(), "Coder", cb);
        List<ModelClient> reviewers = pickReviewersExcluding(coder.getProviderType(), cb);

        // Round 1: Initial Implementation
        StageResult codeStage = runStage(
                "loop-r1-code",
                "Round 1: Implementation",
                "Initial code authoring",
                coder,
                PromptTemplates.coderSystem(options.customSystemInstructions()),
                basePromptWithContext,
                options.history(),
                options,
                stages,
                cb,
                cancel
        );
        if (cancel.getAsBoolean() || !codeStage.isSuccess()) {
            return codeStage.isSuccess() ? codeStage.response().content() : cancelledSummary(stages);
        }

        String currentCode = codeStage.response().content();
        int maxRounds = options.maxReviewRounds();

        for (int round = 1; round <= maxRounds; round++) {
            if (cancel.getAsBoolean()) return cancelledSummary(stages);

            final int currentRound = round;
            final String codeSnapshot = currentCode;

            // Parallel dual review by the other two models
            List<CompletableFuture<StageResult>> reviewFutures = new ArrayList<>();
            for (int idx = 0; idx < reviewers.size(); idx++) {
                ModelClient revClient = reviewers.get(idx);
                String focus = (idx == 0)
                        ? "Functional Correctness, Edge Cases, Typing & Tests"
                        : "Security, Concurrency, Error Handling, Architecture & Performance";
                String stageId = "loop-r" + currentRound + "-review-" + (idx + 1);
                String stageName = "Round " + currentRound + ": Review (" + (idx == 0 ? "Correctness" : "Security & Arch") + ")";
                String reviewInput = "## Developer Request\n" + rawPrompt + "\n\n"
                        + PromptTemplates.buildContextBlock(options.attachments())
                        + "\n## Code Under Review (Round " + currentRound + ")\n" + codeSnapshot;

                reviewFutures.add(CompletableFuture.supplyAsync(() -> runStage(
                        stageId,
                        stageName,
                        focus,
                        revClient,
                        PromptTemplates.dualReviewerSystem(focus, options.customSystemInstructions()),
                        reviewInput,
                        List.of(),
                        options,
                        stages,
                        cb,
                        cancel
                ), pool));
            }

            List<StageResult> roundReviews = new ArrayList<>();
            for (CompletableFuture<StageResult> f : reviewFutures) {
                roundReviews.add(f.join());
            }

            if (cancel.getAsBoolean()) return cancelledSummary(stages);

            boolean needsRevision = roundReviews.stream()
                    .anyMatch(r -> r.isSuccess() && "REVISE".equalsIgnoreCase(r.verdict()));

            if (!needsRevision) {
                cb.onLog("Reviewers approved implementation after Round " + round + ".");
                return buildApprovedLoopSummary(currentCode, roundReviews, round);
            }

            if (round >= maxRounds) {
                cb.onLog("Reached max review rounds (" + maxRounds + "); performing final refinement pass.");
            } else {
                cb.onLog("Reviewers requested revisions in Round " + round + "; refining code...");
            }

            StringBuilder refinePrompt = new StringBuilder();
            refinePrompt.append("## Developer Request\n").append(rawPrompt).append("\n\n");
            refinePrompt.append(PromptTemplates.buildContextBlock(options.attachments()));
            refinePrompt.append("\n## Your Previous Implementation (Round ").append(round).append(")\n")
                    .append(currentCode).append("\n\n");
            refinePrompt.append("## Peer Reviews To Address\n");
            for (StageResult r : roundReviews) {
                if (r.isSuccess()) {
                    refinePrompt.append("### ").append(r.stageName())
                            .append(" [").append(r.provider().getShortName()).append(" - ").append(r.verdict()).append("]\n")
                            .append(r.response().content()).append("\n\n");
                }
            }

            StageResult refined = runStage(
                    "loop-r" + (round + 1) + "-refine",
                    "Round " + (round + 1) + ": Refinement",
                    "Applies dual-reviewer feedback",
                    coder,
                    PromptTemplates.coderRefinementSystem(options.customSystemInstructions()),
                    refinePrompt.toString(),
                    List.of(),
                    options,
                    stages,
                    cb,
                    cancel
            );
            if (refined.isSuccess()) {
                currentCode = refined.response().content();
            }
        }

        return currentCode;
    }

    private String runParallelCouncil(
            String rawPrompt,
            String basePromptWithContext,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel,
            ExecutorService pool
    ) {
        List<ModelClient> members = new ArrayList<>(clients.values());

        // Round 1: All configured models answer independently in parallel
        List<CompletableFuture<StageResult>> round1Futures = new ArrayList<>();
        for (ModelClient client : members) {
            round1Futures.add(CompletableFuture.supplyAsync(() -> runStage(
                    "council-r1-" + client.getProviderType().name().toLowerCase(),
                    "Council Proposal: " + client.getProviderType().getShortName(),
                    "Independent proposal",
                    client,
                    PromptTemplates.councilMemberSystem(client.getProviderType().getFullName(), options.customSystemInstructions()),
                    basePromptWithContext,
                    options.history(),
                    options,
                    stages,
                    cb,
                    cancel
            ), pool));
        }

        Map<ModelProviderType, StageResult> proposals = new LinkedHashMap<>();
        for (CompletableFuture<StageResult> f : round1Futures) {
            StageResult sr = f.join();
            proposals.put(sr.provider(), sr);
        }

        if (cancel.getAsBoolean()) return cancelledSummary(stages);

        long successfulProposals = proposals.values().stream().filter(StageResult::isSuccess).count();
        if (successfulProposals == 0) {
            return "All council models failed. Check the Timeline tab and Settings -> Test Connection.";
        }

        // Round 2 (optional): Parallel Cross-Critique when at least 2 models succeeded
        Map<ModelProviderType, StageResult> critiques = new LinkedHashMap<>();
        if (options.councilCrossCritique() && successfulProposals >= 2) {
            List<CompletableFuture<StageResult>> round2Futures = new ArrayList<>();
            for (ModelClient client : members) {
                StageResult own = proposals.get(client.getProviderType());
                if (own == null || !own.isSuccess()) continue;

                StringBuilder peerBundle = new StringBuilder();
                peerBundle.append("## Original Developer Request\n").append(rawPrompt).append("\n\n");
                peerBundle.append("## Your Initial Proposal\n").append(own.response().content()).append("\n\n");
                peerBundle.append("## Peer Proposals to Critique\n");
                for (StageResult other : proposals.values()) {
                    if (other.provider() != client.getProviderType() && other.isSuccess()) {
                        peerBundle.append("### ").append(other.provider().getFullName()).append("\n")
                                .append(other.response().content()).append("\n\n");
                    }
                }

                round2Futures.add(CompletableFuture.supplyAsync(() -> runStage(
                        "council-r2-" + client.getProviderType().name().toLowerCase(),
                        "Cross-Critique: " + client.getProviderType().getShortName(),
                        "Evaluates peer proposals & spots flaws",
                        client,
                        PromptTemplates.councilCritiqueSystem(client.getProviderType().getFullName(), options.customSystemInstructions()),
                        peerBundle.toString(),
                        List.of(),
                        options,
                        stages,
                        cb,
                        cancel
                ), pool));
            }
            for (CompletableFuture<StageResult> f : round2Futures) {
                StageResult sr = f.join();
                critiques.put(sr.provider(), sr);
            }
        }

        if (cancel.getAsBoolean()) return cancelledSummary(stages);

        // Round 3: Chair Synthesis
        ModelClient chair = resolveClient(options.roles().getSynthesizer(), "Council Chair", cb);
        StringBuilder synthesisInput = new StringBuilder();
        synthesisInput.append("## Original Developer Request\n").append(rawPrompt).append("\n\n");
        synthesisInput.append(PromptTemplates.buildContextBlock(options.attachments())).append("\n");
        synthesisInput.append("## Round 1 Independent Proposals\n");
        for (StageResult prop : proposals.values()) {
            if (prop.isSuccess()) {
                synthesisInput.append("### ").append(prop.provider().getFullName())
                        .append(" (").append(prop.modelId()).append(")\n")
                        .append(prop.response().content()).append("\n\n");
            }
        }
        if (!critiques.isEmpty()) {
            synthesisInput.append("## Round 2 Cross-Critiques\n");
            for (StageResult crit : critiques.values()) {
                if (crit.isSuccess()) {
                    synthesisInput.append("### Critique by ").append(crit.provider().getFullName()).append("\n")
                            .append(crit.response().content()).append("\n\n");
                }
            }
        }

        StageResult chairStage = runStage(
                "council-r3-chair",
                "Chair Synthesis (" + chair.getProviderType().getShortName() + ")",
                "Produces unified consensus solution & code",
                chair,
                PromptTemplates.councilChairSystem(options.customSystemInstructions()),
                synthesisInput.toString(),
                List.of(),
                options,
                stages,
                cb,
                cancel
        );

        if (chairStage.isSuccess()) {
            return chairStage.response().content();
        }
        // Fallback to first successful proposal
        return proposals.values().stream()
                .filter(StageResult::isSuccess)
                .map(s -> s.response().content())
                .findFirst()
                .orElse("Council synthesis failed.");
    }

    private String runDeepAnalysis(
            String rawPrompt,
            String basePromptWithContext,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel,
            ExecutorService pool
    ) {
        List<ModelClient> active = new ArrayList<>(clients.values());
        String[][] lenses = new String[][]{
                {
                        "Correctness, Bugs & Type Safety",
                        "- Logic bugs, edge cases, None/null hazards, off-by-one errors\n"
                                + "- Type safety, schema/API mismatches, broken invariants\n"
                                + "- Subtle Python/runtime pitfalls (mutable defaults, late binding, encoding)"
                },
                {
                        "Security, Reliability & Error Handling",
                        "- Injection, unsafe deserialization, path traversal, auth/secret leaks\n"
                                + "- Exception handling, resource leaks, timeouts, retry safety\n"
                                + "- Concurrency, async race conditions, transaction/data integrity"
                },
                {
                        "Architecture, Performance & Maintainability",
                        "- Algorithmic complexity, N+1 queries, memory spikes, I/O bottlenecks\n"
                                + "- Modularity, coupling, cohesion, API ergonomics, readability\n"
                                + "- Testability gaps and concrete refactoring opportunities"
                }
        };

        List<CompletableFuture<StageResult>> futures = new ArrayList<>();
        for (int i = 0; i < lenses.length; i++) {
            final int lensIdx = i;
            ModelClient assigned = active.get(lensIdx % active.size());
            String lensTitle = lenses[lensIdx][0];
            String lensChecks = lenses[lensIdx][1];

            futures.add(CompletableFuture.supplyAsync(() -> runStage(
                    "audit-lens-" + (lensIdx + 1),
                    "Audit: " + lensTitle,
                    lensTitle,
                    assigned,
                    PromptTemplates.deepAuditLensSystem(lensTitle, lensChecks, options.customSystemInstructions()),
                    basePromptWithContext,
                    options.history(),
                    options,
                    stages,
                    cb,
                    cancel
            ), pool));
        }

        List<StageResult> audits = new ArrayList<>();
        for (CompletableFuture<StageResult> f : futures) {
            audits.add(f.join());
        }

        if (cancel.getAsBoolean()) return cancelledSummary(stages);

        ModelClient synth = resolveClient(options.roles().getSynthesizer(), "Audit Synthesizer", cb);
        StringBuilder synthPrompt = new StringBuilder();
        synthPrompt.append("## Analysis Request\n").append(rawPrompt).append("\n\n");
        synthPrompt.append(PromptTemplates.buildContextBlock(options.attachments())).append("\n");
        synthPrompt.append("## Independent Audits\n");
        for (StageResult a : audits) {
            if (a.isSuccess()) {
                synthPrompt.append("### ").append(a.stageName())
                        .append(" [").append(a.provider().getFullName()).append("]\n")
                        .append(a.response().content()).append("\n\n");
            }
        }

        StageResult report = runStage(
                "audit-synthesis",
                "Unified Tri-Model Audit Report",
                "Deduplicates & prioritizes findings across all 3 models",
                synth,
                PromptTemplates.deepAuditSynthesizerSystem(options.customSystemInstructions()),
                synthPrompt.toString(),
                List.of(),
                options,
                stages,
                cb,
                cancel
        );

        if (report.isSuccess()) {
            return report.response().content();
        }
        StringBuilder fallback = new StringBuilder();
        for (StageResult a : audits) {
            if (a.isSuccess()) {
                fallback.append("## ").append(a.stageName()).append(" (").append(a.provider().getShortName()).append(")\n\n")
                        .append(a.response().content()).append("\n\n");
            }
        }
        return fallback.toString();
    }

    private String runSideBySide(
            String basePromptWithContext,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel,
            ExecutorService pool
    ) {
        List<CompletableFuture<StageResult>> futures = new ArrayList<>();
        for (ModelClient client : clients.values()) {
            futures.add(CompletableFuture.supplyAsync(() -> runStage(
                    "compare-" + client.getProviderType().name().toLowerCase(),
                    client.getProviderType().getFullName(),
                    "Parallel direct response",
                    client,
                    PromptTemplates.directCompareSystem(options.customSystemInstructions()),
                    basePromptWithContext,
                    options.history(),
                    options,
                    stages,
                    cb,
                    cancel
            ), pool));
        }

        List<StageResult> results = new ArrayList<>();
        for (CompletableFuture<StageResult> f : futures) {
            results.add(f.join());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Side-by-Side Comparison\n\n");
        sb.append("| Model | Status | Latency | Tokens |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");
        for (StageResult sr : results) {
            String status = sr.isSuccess() ? "OK" : "FAILED";
            String latency = sr.response() != null ? sr.response().latencyMs() + " ms" : "-";
            String tokens = sr.response() != null ? sr.response().totalTokens() + "" : "-";
            sb.append("| **").append(sr.provider().getFullName()).append("** (`").append(sr.modelId()).append("`) | ")
                    .append(status).append(" | ").append(latency).append(" | ").append(tokens).append(" |\n");
        }
        sb.append("\n---\n\n");
        for (StageResult sr : results) {
            sb.append("## ").append(sr.provider().getFullName()).append(" (`").append(sr.modelId()).append("`)\n\n");
            if (sr.isSuccess()) {
                sb.append(sr.response().content()).append("\n\n");
            } else {
                sb.append("> **Error:** ").append(sr.response() != null ? sr.response().errorMessage() : "Unknown error").append("\n\n");
            }
        }
        return sb.toString();
    }

    private StageResult runStage(
            String stageId,
            String stageName,
            String roleDescription,
            ModelClient client,
            String systemPrompt,
            String userPrompt,
            List<ChatMessage> history,
            RunOptions options,
            List<StageResult> stages,
            OrchestrationListener cb,
            BooleanSupplier cancel
    ) {
        cb.onStageStarted(stageId, stageName, client.getProviderType(), client.getModelId());
        ModelRequest req = new ModelRequest(
                systemPrompt,
                userPrompt,
                history,
                options.maxOutputTokens(),
                options.timeoutSeconds()
        );
        ModelResponse resp = client.generate(req, cancel);
        String verdict = resp.success() ? parseVerdict(resp.content()) : "";
        StageResult.Status status = resp.success() ? StageResult.Status.COMPLETED : StageResult.Status.FAILED;
        StageResult result = new StageResult(
                stageId,
                stageName,
                roleDescription,
                client.getProviderType(),
                resp.modelId().isBlank() ? client.getModelId() : resp.modelId(),
                status,
                resp,
                verdict
        );
        stages.add(result);
        cb.onStageCompleted(result);
        return result;
    }

    private ModelClient resolveClient(ModelProviderType preferred, String roleName, OrchestrationListener cb) {
        ModelClient direct = clients.get(preferred);
        if (direct != null) {
            return direct;
        }
        ModelClient fallback = clients.values().iterator().next();
        cb.onLog("Note: " + preferred.getShortName() + " is not ready for role '" + roleName
                + "'; falling back to " + fallback.getProviderType().getShortName() + ".");
        return fallback;
    }

    private List<ModelClient> pickReviewersExcluding(ModelProviderType coderProvider, OrchestrationListener cb) {
        List<ModelClient> others = new ArrayList<>();
        for (ModelProviderType type : ModelProviderType.values()) {
            if (type != coderProvider && clients.containsKey(type)) {
                others.add(clients.get(type));
            }
        }
        if (others.isEmpty()) {
            ModelClient self = clients.get(coderProvider);
            cb.onLog("Only one provider configured; using " + self.getProviderType().getShortName() + " for self-review.");
            others.add(self);
        }
        return others;
    }

    private static List<ProposedFileChange> extractChangesFromResult(String finalOutput, List<StageResult> stages) {
        List<ProposedFileChange> primary = CodeChangeExtractor.extract(finalOutput, "Final Output");
        if (!primary.isEmpty()) {
            return primary;
        }
        // Fallback: check stages in reverse chronological order for code blocks
        for (int i = stages.size() - 1; i >= 0; i--) {
            StageResult sr = stages.get(i);
            if (sr.isSuccess()) {
                List<ProposedFileChange> found = CodeChangeExtractor.extract(
                        sr.response().content(),
                        sr.stageName() + " (" + sr.provider().getShortName() + ")"
                );
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return List.of();
    }

    static String parseVerdict(String content) {
        if (content == null || content.isBlank()) return "";
        Matcher m = VERDICT_PATTERN.matcher(content);
        String found = "";
        while (m.find()) {
            found = m.group(1).toUpperCase();
        }
        return found;
    }

    private static String buildBaseUserPrompt(String userPrompt, String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return userPrompt.trim();
        }
        return "## Task\n" + userPrompt.trim() + "\n\n" + contextBlock;
    }

    private static String buildApprovedLoopSummary(String code, List<StageResult> reviews, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("> **Consensus Reached in Round ").append(round).append(":** All reviewers approved the implementation.\n\n");
        sb.append(code).append("\n\n---\n\n### Reviewer Sign-Off Notes\n");
        for (StageResult r : reviews) {
            if (r.isSuccess()) {
                sb.append("- **").append(r.provider().getFullName()).append("** (")
                        .append(r.roleDescription()).append("): `")
                        .append(r.verdict().isBlank() ? "APPROVED" : r.verdict()).append("`\n");
            }
        }
        return sb.toString();
    }

    private static String cancelledSummary(List<StageResult> stages) {
        for (int i = stages.size() - 1; i >= 0; i--) {
            StageResult s = stages.get(i);
            if (s.isSuccess()) {
                return "*Orchestration cancelled before completion. Showing latest completed stage ("
                        + s.stageName() + "):*\n\n" + s.response().content();
            }
        }
        return "*Orchestration cancelled by user.*";
    }

    private static OrchestrationResult emptyError(OrchestrationMode mode, String prompt, String error, long latencyMs) {
        return new OrchestrationResult(mode, prompt, List.of(), "", List.of(), latencyMs, 0, 0, false, error);
    }
}
