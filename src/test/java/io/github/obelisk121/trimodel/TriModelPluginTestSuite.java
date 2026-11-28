package io.github.obelisk121.trimodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obelisk121.trimodel.auth.OAuthPkceService;
import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.auth.OAuthToken;
import io.github.obelisk121.trimodel.model.AgentSkillConfig;
import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ContextAttachment;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;
import io.github.obelisk121.trimodel.model.OrchestrationListener;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.model.OrchestrationResult;
import io.github.obelisk121.trimodel.model.ProposedFileChange;
import io.github.obelisk121.trimodel.model.RoleAssignment;
import io.github.obelisk121.trimodel.orchestration.CodeChangeExtractor;
import io.github.obelisk121.trimodel.orchestration.OrchestrationEngine;
import io.github.obelisk121.trimodel.orchestration.PromptTemplates;
import io.github.obelisk121.trimodel.provider.ChatGptClient;
import io.github.obelisk121.trimodel.provider.ClaudeClient;
import io.github.obelisk121.trimodel.provider.CliModelClient;
import io.github.obelisk121.trimodel.provider.EnvironmentAutoDetector;
import io.github.obelisk121.trimodel.provider.GeminiClient;
import io.github.obelisk121.trimodel.provider.HttpTransport;
import io.github.obelisk121.trimodel.provider.ModelClient;
import io.github.obelisk121.trimodel.provider.ModelDiscoveryService;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.TriModelSettings;
import io.github.obelisk121.trimodel.ui.MarkdownHtmlRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Comprehensive automated test suite verifying REST API clients, local CLI execution,
 * code-change extraction, all 5 orchestration modes, cancellation, and Markdown rendering.
 */
public final class TriModelPluginTestSuite {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Tri-Model AI Orchestrator Test Suite ===");
        runTest("Claude Messages API client (2026-10-21 compliance)", TriModelPluginTestSuite::testClaudeClient);
        runTest("ChatGPT Responses API & Chat Completions API client", TriModelPluginTestSuite::testChatGptClient);
        runTest("Gemini Interactions API (steps & outputs) & generateContent client", TriModelPluginTestSuite::testGeminiClient);
        runTest("Local CLI client (stdin piping, arg placeholder, errors)", TriModelPluginTestSuite::testCliClient);
        runTest("CodeChangeExtractor (headers, fences, comments, dedup, traversal guard)", TriModelPluginTestSuite::testCodeChangeExtractor);
        runTest("OrchestrationEngine: PIPELINE mode", TriModelPluginTestSuite::testPipelineMode);
        runTest("OrchestrationEngine: ITERATIVE_LOOP mode (REVISE -> refine -> APPROVE)", TriModelPluginTestSuite::testIterativeLoopMode);
        runTest("OrchestrationEngine: PARALLEL_COUNCIL mode (parallel + cross-critique + chair)", TriModelPluginTestSuite::testParallelCouncilMode);
        runTest("OrchestrationEngine: DEEP_ANALYSIS mode", TriModelPluginTestSuite::testDeepAnalysisMode);
        runTest("OrchestrationEngine: SIDE_BY_SIDE mode & partial provider fallback", TriModelPluginTestSuite::testSideBySideAndFallback);
        runTest("OrchestrationEngine: Cooperative cancellation", TriModelPluginTestSuite::testCancellation);
        runTest("MarkdownHtmlRenderer (tables, code blocks, badges, HTML escaping)", TriModelPluginTestSuite::testMarkdownRenderer);
        runTest("OAuth 2.0 PKCE (S256, auth URL, CSRF state, code exchange, temporary session & refresh)", TriModelPluginTestSuite::testOAuthPkceFlow);
        runTest("Bearer OAuth headers on ClaudeClient & GeminiClient", TriModelPluginTestSuite::testOAuthBearerHeaders);
        runTest("Agent Independence Skills (anti-sycophancy, topic matrix, blind peer labels, contradiction guard)", TriModelPluginTestSuite::testAgentIndependenceSkills);
        runTest("Dynamic Model Discovery via REST APIs (Anthropic, OpenAI, Gemini + pagination/filtering)", TriModelPluginTestSuite::testRestApiModelDiscovery);
        runTest("Dynamic Model Discovery via CLIs (gemini, agy, codex, claude) & no hardcoded presets", TriModelPluginTestSuite::testCliModelDiscoveryAndNoHardcodedModels);
        runTest("CLI Auth, agy/gemini/codex/claude arg normalization, Codex error extraction & Gemini sign-out", TriModelPluginTestSuite::testCliAuthNormalizationAndSignOut);
        runTest("Settings auto-migration for agy CLI args & legacy hardcoded CLI models", TriModelPluginTestSuite::testSettingsMigration);
        runTest("Cross-platform PATH, executable & install-hint detection (Linux, Windows, macOS)", TriModelPluginTestSuite::testCrossPlatformPathAndExecutableDetection);
        runTest("EnvironmentAutoDetector (signed-in CLI, agy fallback, env base URLs & first-run preservation)", TriModelPluginTestSuite::testEnvironmentAutoDetector);

        System.out.println();
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.err.println("[FAIL] " + name + ": " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    private static void testClaudeClient() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        AtomicReference<JsonObject> capturedBody = new AtomicReference<>();

        HttpTransport.PostFunction mockTransport = (url, headers, jsonBody, timeout, cancel) -> {
            capturedUrl.set(url);
            capturedHeaders.set(headers);
            capturedBody.set(JsonParser.parseString(jsonBody).getAsJsonObject());
            if (url.contains("/err/")) {
                return new HttpTransport.HttpResult(401,
                        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid x-api-key\"}}",
                        14, false, null);
            }
            String resp = """
                    {
                      "id": "msg_01",
                      "model": "claude-sonnet-5-5",
                      "content": [
                        {"type": "thinking", "thinking": "Evaluating edge cases."},
                        {"type": "text", "text": "Hello from Claude!"}
                      ],
                      "usage": {"input_tokens": 120, "output_tokens": 45}
                    }
                    """;
            return new HttpTransport.HttpResult(200, resp, 25, false, null);
        };

        ClaudeClient client = new ClaudeClient("https://api.anthropic.com", "sk-ant-test", "claude-sonnet-5-5", "2026-10-21", mockTransport);
        ModelRequest req = new ModelRequest(
                "Be concise",
                "Write a function",
                List.of(ChatMessage.user("Previous question"), ChatMessage.assistant("Previous answer")),
                2048,
                30
        );
        ModelResponse res = client.generate(req, () -> false);

        assertTrue(res.success(), "Claude call should succeed: " + res.errorMessage());
        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl.get(), "Claude endpoint");
        assertEquals("Hello from Claude!", res.content(), "Claude text output");
        assertEquals("Evaluating edge cases.", res.thinking(), "Claude thinking output");
        assertEquals(120L, res.inputTokens(), "Claude input tokens");
        assertEquals(45L, res.outputTokens(), "Claude output tokens");
        assertEquals("sk-ant-test", capturedHeaders.get().get("x-api-key"), "x-api-key header");
        assertEquals("2026-10-21", capturedHeaders.get().get("anthropic-version"), "anthropic-version header");

        JsonObject body = capturedBody.get();
        assertTrue(!body.has("temperature"), "Claude 2026-10-21 must NOT send temperature");
        assertTrue(!body.has("top_p"), "Claude 2026-10-21 must NOT send top_p");
        assertTrue(!body.has("top_k"), "Claude 2026-10-21 must NOT send top_k");
        assertEquals("Be concise", body.get("system").getAsString(), "System prompt sent");
        assertEquals(3, body.getAsJsonArray("messages").size(), "History + user turn count");

        ClaudeClient errClient = new ClaudeClient("https://api.anthropic.com/err", "bad-key", "claude-sonnet-5-5", "2026-10-21", mockTransport);
        ModelResponse errRes = errClient.generate(ModelRequest.simple("", "Hi"), () -> false);
        assertTrue(!errRes.success(), "401 should fail");
        assertTrue(errRes.errorMessage().contains("Invalid x-api-key"), "Error detail preserved");
    }

    private static void testChatGptClient() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        AtomicReference<JsonObject> capturedBody = new AtomicReference<>();

        HttpTransport.PostFunction mockTransport = (url, headers, jsonBody, timeout, cancel) -> {
            capturedUrl.set(url);
            capturedHeaders.set(headers);
            capturedBody.set(JsonParser.parseString(jsonBody).getAsJsonObject());
            if (url.endsWith("/responses")) {
                String resp = """
                        {
                          "id": "resp_123",
                          "model": "gpt-6.2-sol",
                          "output": [
                            {"type": "reasoning", "summary": [{"type": "summary_text", "text": "Checked complexity."}]},
                            {
                              "type": "message",
                              "role": "assistant",
                              "content": [{"type": "output_text", "text": "Hello from Responses API!"}]
                            }
                          ],
                          "usage": {"input_tokens": 200, "output_tokens": 80}
                        }
                        """;
                return new HttpTransport.HttpResult(200, resp, 30, false, null);
            }
            String resp = """
                    {
                      "id": "chatcmpl_1",
                      "model": "gpt-6.2-sol",
                      "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": "Hello from Chat Completions!"}}
                      ],
                      "usage": {"prompt_tokens": 90, "completion_tokens": 30}
                    }
                    """;
            return new HttpTransport.HttpResult(200, resp, 20, false, null);
        };

        ChatGptClient responsesClient = new ChatGptClient("https://api.openai.com/v1", "sk-openai-test", "gpt-6.2-sol", OpenAiApiStyle.RESPONSES, mockTransport);
        ModelResponse r1 = responsesClient.generate(ModelRequest.simple("Sys", "Hello"), () -> false);
        assertTrue(r1.success(), "Responses API should succeed: " + r1.errorMessage());
        assertEquals("https://api.openai.com/v1/responses", capturedUrl.get(), "Responses endpoint");
        assertEquals("Hello from Responses API!", r1.content(), "Responses output text");
        assertEquals("Checked complexity.", r1.thinking(), "Responses reasoning summary");
        assertEquals(200L, r1.inputTokens(), "Responses input tokens");
        assertEquals("Bearer sk-openai-test", capturedHeaders.get().get("Authorization"), "Bearer header");
        assertEquals("Sys", capturedBody.get().get("instructions").getAsString(), "Responses instructions field");

        ChatGptClient chatClient = new ChatGptClient("https://api.openai.com/v1", "sk-openai-test", "gpt-6.2-sol", OpenAiApiStyle.CHAT_COMPLETIONS, mockTransport);
        ModelResponse r2 = chatClient.generate(ModelRequest.simple("Sys", "Hello"), () -> false);
        assertTrue(r2.success(), "Chat Completions should succeed: " + r2.errorMessage());
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl.get(), "Chat Completions endpoint");
        assertEquals("Hello from Chat Completions!", r2.content(), "Chat Completions output");
        assertEquals(90L, r2.inputTokens(), "Chat Completions prompt tokens");
        assertEquals(2, capturedBody.get().getAsJsonArray("messages").size(), "System + user messages");
    }

    private static void testGeminiClient() {
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        AtomicReference<JsonObject> capturedBody = new AtomicReference<>();

        HttpTransport.PostFunction mockTransport = (url, headers, jsonBody, timeout, cancel) -> {
            capturedUrl.set(url);
            capturedHeaders.set(headers);
            capturedBody.set(JsonParser.parseString(jsonBody).getAsJsonObject());
            if (url.contains("/legacy/")) {
                return new HttpTransport.HttpResult(200,
                        "{\"id\":\"int_old\",\"outputs\":[{\"type\":\"text\",\"text\":\"Hello from legacy outputs!\"}]}",
                        15, false, null);
            }
            if (url.endsWith("/interactions")) {
                String resp = """
                        {
                          "id": "int_001",
                          "model": "gemini-4-neon",
                          "status": "completed",
                          "steps": [
                            {"type": "thought", "summary": [{"type": "text", "text": "Planned module split."}]},
                            {"type": "model_output", "content": [{"type": "text", "text": "Hello from Gemini steps!"}]}
                          ],
                          "usage": {"total_input_tokens": 150, "total_output_tokens": 60}
                        }
                        """;
                return new HttpTransport.HttpResult(200, resp, 22, false, null);
            }
            String resp = """
                    {
                      "candidates": [
                        {"content": {"parts": [{"text": "Hello from generateContent!"}]}}
                      ],
                      "usageMetadata": {"promptTokenCount": 75, "candidatesTokenCount": 25}
                    }
                    """;
            return new HttpTransport.HttpResult(200, resp, 18, false, null);
        };

        GeminiClient interactionsClient = new GeminiClient(
                "https://generativelanguage.googleapis.com/v1beta", "gem-key-123", "gemini-4-neon",
                GeminiApiStyle.INTERACTIONS, mockTransport);
        ModelResponse r1 = interactionsClient.generate(ModelRequest.simple("Sys", "Hello"), () -> false);
        assertTrue(r1.success(), "Gemini Interactions should succeed: " + r1.errorMessage());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", capturedUrl.get(), "Interactions endpoint");
        assertEquals("Hello from Gemini steps!", r1.content(), "Gemini steps content");
        assertEquals("Planned module split.", r1.thinking(), "Gemini thought summary");
        assertEquals(150L, r1.inputTokens(), "Gemini input tokens");
        assertEquals("gem-key-123", capturedHeaders.get().get("x-goog-api-key"), "x-goog-api-key header");
        assertEquals("Sys", capturedBody.get().get("system_instruction").getAsString(), "system_instruction field");

        GeminiClient legacyClient = new GeminiClient(
                "https://generativelanguage.googleapis.com/legacy/v1beta", "gem-key-123", "gemini-4-neon",
                GeminiApiStyle.INTERACTIONS, mockTransport);
        ModelResponse rLegacy = legacyClient.generate(ModelRequest.simple("", "Hi"), () -> false);
        assertTrue(rLegacy.success(), "Legacy outputs[] should succeed");
        assertEquals("Hello from legacy outputs!", rLegacy.content(), "Legacy outputs content");

        GeminiClient genClient = new GeminiClient(
                "https://generativelanguage.googleapis.com/v1beta", "gem-key-123", "gemini-4-neon",
                GeminiApiStyle.GENERATE_CONTENT, mockTransport);
        ModelResponse r2 = genClient.generate(ModelRequest.simple("Sys", "Hello"), () -> false);
        assertTrue(r2.success(), "generateContent should succeed: " + r2.errorMessage());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-4-neon:generateContent",
                capturedUrl.get(), "generateContent endpoint");
        assertEquals("Hello from generateContent!", r2.content(), "generateContent text");
        assertEquals(75L, r2.inputTokens(), "generateContent promptTokenCount");
    }

    private static void testCliClient() {
        assertTrue(CliModelClient.resolveExecutable("python3") != null, "resolveExecutable finds python3");
        assertTrue(CliModelClient.resolveExecutable("nonexistent_binary_xyz_999") == null, "resolveExecutable returns null for missing binary");

        // Verify Codex CLI auto-adds --skip-git-repo-check & --color never for legacy configs
        List<String> codexArgs = CliModelClient.normalizeArgsForProvider(ModelProviderType.CHATGPT, List.of("exec", "-"));
        assertTrue(codexArgs.contains("--skip-git-repo-check"), "Codex exec auto-injects --skip-git-repo-check");
        assertTrue(codexArgs.contains("--color") && codexArgs.contains("never"), "Codex exec auto-injects --color never");

        // Verify Gemini settings.json OAuth mode switcher preserves existing keys
        String patchedGemini = CliModelClient.patchGeminiSettingsForOAuth(
                "{\"general\":{\"enabled\":true},\"security\":{\"auth\":{\"selectedType\":\"gemini-api-key\"}}}");
        assertTrue(patchedGemini.contains("\"oauth-personal\""), "Gemini settings patched to oauth-personal");
        assertTrue(patchedGemini.contains("\"general\""), "Existing Gemini settings preserved");

        CliModelClient stdinCli = new CliModelClient(
                ModelProviderType.CLAUDE,
                "/usr/bin/python3",
                "-c \"import sys; data = sys.stdin.read(); print('ECHO:' + str(len(data)))\"",
                "cli:python3"
        );
        ModelResponse ok = stdinCli.generate(ModelRequest.simple("Sys", "Test prompt"), () -> false);
        assertTrue(ok.success(), "CLI stdin mode should succeed: " + ok.errorMessage());
        assertTrue(ok.content().startsWith("ECHO:"), "CLI captured stdin output");

        CliModelClient argCli = new CliModelClient(
                ModelProviderType.GEMINI,
                "/usr/bin/python3",
                "-c \"import sys; print('ARG:' + sys.argv[1].splitlines()[-1])\" \"{prompt}\"",
                "cli:python3"
        );
        ModelResponse okArg = argCli.generate(ModelRequest.simple("", "FinalLine42"), () -> false);
        assertTrue(okArg.success(), "CLI {prompt} arg mode should succeed: " + okArg.errorMessage());
        assertEquals("ARG:FinalLine42", okArg.content(), "CLI arg substitution");

        CliModelClient failCli = new CliModelClient(
                ModelProviderType.CHATGPT,
                "/usr/bin/python3",
                "-c \"import sys; sys.stderr.write('boom'); sys.exit(2)\"",
                "cli:fail"
        );
        ModelResponse err = failCli.generate(ModelRequest.simple("", "hi"), () -> false);
        assertTrue(!err.success(), "Non-zero exit should fail");
        assertTrue(err.errorMessage().contains("boom"), "Stderr captured on failure");
    }

    private static void testCodeChangeExtractor() {
        String md = """
                Summary of changes:

                ### FILE: src/service/calc.py
                ```python
                def add(a: int, b: int) -> int:
                    return a + b
                ```

                ```python:tests/test_calc.py
                from src.service.calc import add

                def test_add():
                    assert add(2, 3) == 5
                ```

                ### FILE: ../escape/evil.py
                ```python
                print("blocked")
                ```

                ### FILE: src/service/calc.py
                ```python
                def add(a: int, b: int) -> int:
                    \"\"\"Revised version.\"\"\"
                    return a + b
                ```

                ```python
                # FILE: utils/helper.py
                def ping() -> str:
                    return "pong"
                ```
                """;

        List<ProposedFileChange> changes = CodeChangeExtractor.extract(md, "Test");
        assertEquals(3L, changes.stream().filter(ProposedFileChange::hasExplicitPath).count(),
                "Should have 3 unique safe explicit paths (evil traversal rejected, calc.py deduplicated)");

        ProposedFileChange calc = changes.stream()
                .filter(c -> "src/service/calc.py".equals(c.filePath()))
                .findFirst().orElseThrow();
        assertTrue(calc.code().contains("Revised version."), "Deduplication keeps latest version");
        assertTrue(changes.stream().noneMatch(c -> c.filePath().contains("..")), "Path traversal rejected");
        assertTrue(changes.stream().anyMatch(c -> "tests/test_calc.py".equals(c.filePath())), "Colon fence path parsed");
        assertTrue(changes.stream().anyMatch(c -> "utils/helper.py".equals(c.filePath())), "First-line comment path parsed");
    }

    private static void testPipelineMode() {
        Map<ModelProviderType, ModelClient> mocks = new EnumMap<>(ModelProviderType.class);
        mocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "## Plan\nCreate app.py"));
        mocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> """
                ### FILE: app.py
                ```python
                def greet(name: str) -> str:
                    return f"Hello, {name}!"
                ```
                """));
        mocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> "Looks clean.\nVERDICT: APPROVE"));

        OrchestrationEngine engine = new OrchestrationEngine(mocks);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.PIPELINE,
                new RoleAssignment(ModelProviderType.GEMINI, ModelProviderType.CLAUDE, ModelProviderType.CHATGPT, ModelProviderType.CLAUDE),
                List.of(new ContextAttachment("main.py", "python", "print('hi')", false, 1, 1, List.of())),
                List.of(), 2, true, 4096, 30, ""
        );

        OrchestrationResult res = engine.execute("Add greet function", opts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(4, res.stages().size(), "Pipeline has 4 stages");
        assertTrue(!res.hasErrors(), "Pipeline has no errors");
        assertEquals(1, res.proposedChanges().size(), "Finalizer extracted app.py");
        assertEquals("app.py", res.proposedChanges().get(0).filePath(), "Extracted file path");
    }

    private static void testIterativeLoopMode() {
        AtomicInteger claudeCalls = new AtomicInteger(0);
        AtomicInteger gptReviewCalls = new AtomicInteger(0);

        Map<ModelProviderType, ModelClient> mocks = new EnumMap<>(ModelProviderType.class);
        mocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> {
            int n = claudeCalls.incrementAndGet();
            return "### FILE: math_ops.py\n```python\ndef div(a, b):\n    "
                    + (n == 1 ? "return a / b" : "if b == 0: raise ValueError('zero')\n    return a / b")
                    + "\n```";
        }));
        mocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> {
            int n = gptReviewCalls.incrementAndGet();
            return n == 1
                    ? "[CRITICAL] Missing division-by-zero guard.\nVERDICT: REVISE"
                    : "Division guard added cleanly.\nVERDICT: APPROVE";
        }));
        mocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "Architecture looks solid.\nVERDICT: APPROVE"));

        OrchestrationEngine engine = new OrchestrationEngine(mocks);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.ITERATIVE_LOOP,
                new RoleAssignment(ModelProviderType.GEMINI, ModelProviderType.CLAUDE, ModelProviderType.CHATGPT, ModelProviderType.CLAUDE),
                List.of(), List.of(), 3, true, 4096, 30, ""
        );

        OrchestrationResult res = engine.execute("Write safe division", opts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(2, claudeCalls.get(), "Coder should run Round 1 + 1 refinement pass");
        assertEquals(2, gptReviewCalls.get(), "Reviewer should run Round 1 (REVISE) + Round 2 (APPROVE)");
        assertTrue(res.finalOutput().contains("Consensus Reached in Round 2"), "Consensus reached after refinement");
        assertTrue(res.proposedChanges().get(0).code().contains("ValueError"), "Refined code includes zero guard");
    }

    private static void testParallelCouncilMode() {
        Map<ModelProviderType, ModelClient> mocks = new EnumMap<>(ModelProviderType.class);
        mocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req ->
                req.systemPrompt().contains("Chair")
                        ? "Consensus Answer\n### FILE: cache.py\n```python\nclass LRUCache: pass\n```"
                        : "Claude proposal / critique"));
        mocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> "ChatGPT proposal / critique"));
        mocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "Gemini proposal / critique"));

        OrchestrationEngine engine = new OrchestrationEngine(mocks);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.PARALLEL_COUNCIL,
                new RoleAssignment(),
                List.of(), List.of(), 2, true, 4096, 30, ""
        );

        OrchestrationResult res = engine.execute("Design LRU cache", opts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(7, res.stages().size(), "Parallel Council runs 3 proposals + 3 critiques + 1 chair");
        assertEquals(1, res.proposedChanges().size(), "Chair code extracted");
        assertEquals("cache.py", res.proposedChanges().get(0).filePath(), "Council consensus file");
    }

    private static void testDeepAnalysisMode() {
        Map<ModelProviderType, ModelClient> mocks = new EnumMap<>(ModelProviderType.class);
        mocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> "[HIGH] Unclosed file handle"));
        mocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> "[CRITICAL] SQL injection in query"));
        mocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "[MEDIUM] O(n^2) nested loop"));

        OrchestrationEngine engine = new OrchestrationEngine(mocks);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.DEEP_ANALYSIS,
                new RoleAssignment(),
                List.of(), List.of(), 2, true, 4096, 30, ""
        );

        OrchestrationResult res = engine.execute("Audit app.py", opts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(4, res.stages().size(), "Deep Analysis runs 3 lenses + 1 synthesis");
        assertTrue(!res.hasErrors(), "No errors in Deep Analysis");
    }

    private static void testSideBySideAndFallback() {
        Map<ModelProviderType, ModelClient> twoModels = new EnumMap<>(ModelProviderType.class);
        twoModels.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> "Claude direct answer"));
        twoModels.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "Gemini direct answer"));

        OrchestrationEngine engine = new OrchestrationEngine(twoModels);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.SIDE_BY_SIDE, new RoleAssignment(), List.of(), List.of(), 2, true, 4096, 30, ""
        );
        OrchestrationResult res = engine.execute("Compare asyncio vs threading", opts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(2, res.stages().size(), "Side-by-side runs both configured models");
        assertTrue(res.finalOutput().contains("Claude direct answer") && res.finalOutput().contains("Gemini direct answer"),
                "Comparison output contains both answers");
    }

    private static void testCancellation() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Map<ModelProviderType, ModelClient> mocks = new EnumMap<>(ModelProviderType.class);
        mocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> {
            cancelled.set(true);
            return "Stage 1 done before cancel";
        }));
        mocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> "Should not run"));
        mocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> "Should not run"));

        OrchestrationEngine engine = new OrchestrationEngine(mocks);
        OrchestrationEngine.RunOptions opts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.PIPELINE, new RoleAssignment(), List.of(), List.of(), 2, true, 4096, 30, ""
        );
        OrchestrationResult res = engine.execute("Build API", opts, OrchestrationListener.NO_OP, cancelled::get);
        assertTrue(res.cancelled(), "Result marked cancelled");
        assertEquals(1, res.stages().size(), "Subsequent stages skipped after cancel");
    }

    private static void testMarkdownRenderer() {
        String md = """
                # Report
                [CRITICAL] Fix `dict[str, list<int>]` and <script>alert(1)</script>
                | Model | Status |
                | :--- | :--- |
                | **Claude** | OK |
                ```python
                x = 1 < 2 and 3 > 1
                ```
                """;
        String html = MarkdownHtmlRenderer.toHtmlDocument(md, true);
        assertTrue(html.contains("<h1>Report</h1>"), "Heading rendered");
        assertTrue(html.contains("badge-crit"), "Severity badge rendered");
        assertTrue(!html.contains("<script>"), "HTML script tag escaped");
        assertTrue(html.contains("&lt;script&gt;"), "Escaped script visible as text");
        assertTrue(html.contains("<table>") && html.contains("<th>Model</th>"), "Markdown table rendered");
        assertTrue(html.contains("<pre>x = 1 &lt; 2 and 3 &gt; 1"), "Code block escaped and rendered");
    }

    private static void testOAuthPkceFlow() {
        OAuthSessionStore.clearAllSessions();

        // 1. PKCE S256 deterministic RFC 7636 test vector
        String rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expectedChallenge, OAuthPkceService.deriveCodeChallengeS256(rfcVerifier), "RFC 7636 S256 challenge");

        // 2. Endpoint HTTPS enforcement
        assertTrue(OAuthPkceService.validateEndpointUrl("http://evil.example.com/auth", "URL") != null, "Non-HTTPS remote URL rejected");
        assertTrue(OAuthPkceService.validateEndpointUrl("https://accounts.google.com/o/oauth2/v2/auth", "URL") == null, "HTTPS URL accepted");
        assertTrue(OAuthPkceService.validateEndpointUrl("http://127.0.0.1:8080/token", "URL") == null, "Loopback HTTP accepted for local dev");

        // 3. Authorization URL parameters
        ProviderConfig cfg = ProviderConfig.defaultsFor(ModelProviderType.GEMINI);
        cfg.connectionMode = ConnectionMode.OAUTH_BROWSER;
        cfg.oauthClientId = "test-desktop-client.apps.googleusercontent.com";
        cfg.oauthTemporaryOnly = true;

        String authUrl = OAuthPkceService.buildAuthorizationUrl(
                ModelProviderType.GEMINI, cfg, "http://127.0.0.1:54321/callback", "state123", expectedChallenge);
        assertTrue(authUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), "Google authorize base URL");
        assertTrue(authUrl.contains("code_challenge=" + expectedChallenge), "PKCE challenge included");
        assertTrue(authUrl.contains("code_challenge_method=S256"), "PKCE S256 method included");
        assertTrue(authUrl.contains("state=state123"), "CSRF state included");
        assertTrue(authUrl.contains("access_type=online"), "Temporary mode requests online access");

        // 4. Loopback callback query validation (CSRF protection & error handling)
        OAuthPkceService.CallbackOutcome badState = OAuthPkceService.processCallbackQuery("code=abc&state=wrong", "state123");
        assertTrue(!badState.success() && badState.errorMessage().contains("CSRF"), "CSRF mismatch rejected");

        OAuthPkceService.CallbackOutcome denied = OAuthPkceService.processCallbackQuery("error=access_denied&error_description=User+cancelled", "state123");
        assertTrue(!denied.success() && denied.errorMessage().contains("access_denied"), "Provider error captured");

        OAuthPkceService.CallbackOutcome good = OAuthPkceService.processCallbackQuery("code=auth_code_99&state=state123", "state123");
        assertTrue(good.success() && "auth_code_99".equals(good.code()), "Valid callback code extracted");

        // 5. Temporary RAM-only code exchange strips long-lived refresh token
        AtomicReference<Map<String, String>> capturedForm = new AtomicReference<>();
        OAuthPkceService pkceService = new OAuthPkceService((url, params, timeout, cancel) -> {
            capturedForm.set(params);
            String json = "{\"access_token\":\"ya29.temp_token\",\"refresh_token\":\"1//ignored_in_temp\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"generative-language\"}";
            return new HttpTransport.HttpResult(200, json, 15, false, null);
        });

        OAuthPkceService.AuthResult exchanged = pkceService.exchangeAuthorizationCode(
                ModelProviderType.GEMINI, cfg, "", "auth_code_99", rfcVerifier, "http://127.0.0.1:54321/callback", () -> false);
        assertTrue(exchanged.success(), "OAuth code exchange succeeded: " + exchanged.errorMessage());
        assertEquals("ya29.temp_token", exchanged.token().accessToken(), "Access token captured");
        assertTrue(exchanged.token().temporaryOnly(), "Token marked temporary RAM-only");
        assertTrue(!exchanged.token().canRefresh(), "Temporary-only mode does not retain long-lived refresh token");
        assertEquals(rfcVerifier, capturedForm.get().get("code_verifier"), "PKCE verifier sent to token endpoint");
        assertTrue(OAuthSessionStore.hasUsableSession(ModelProviderType.GEMINI), "Session active in store");

        // 6. Persistent session + refresh token flow
        cfg.oauthTemporaryOnly = false;
        OAuthPkceService.AuthResult persisted = pkceService.exchangeAuthorizationCode(
                ModelProviderType.GEMINI, cfg, "desktop-secret", "auth_code_99", rfcVerifier, "http://127.0.0.1:54321/callback", () -> false);
        assertTrue(persisted.success() && persisted.token().canRefresh(), "Persistent mode keeps refresh token");
        assertEquals("desktop-secret", capturedForm.get().get("client_secret"), "Optional client_secret sent when provided");

        OAuthPkceService.AuthResult refreshed = pkceService.refreshAccessToken(
                ModelProviderType.GEMINI, cfg, "desktop-secret", persisted.token(), () -> false);
        assertTrue(refreshed.success(), "Token refresh succeeded");
        assertEquals("refresh_token", capturedForm.get().get("grant_type"), "Refresh grant_type used");

        // 7. Sign out clears session completely
        OAuthSessionStore.clearSession(ModelProviderType.GEMINI);
        assertTrue(!OAuthSessionStore.hasUsableSession(ModelProviderType.GEMINI), "Sign out clears session");
    }

    private static void testOAuthBearerHeaders() {
        AtomicReference<Map<String, String>> claudeHeaders = new AtomicReference<>();
        ClaudeClient claudeBearer = new ClaudeClient(
                "https://api.anthropic.com", "oauth-claude-tok", "claude-sonnet-5-5", "2026-10-21", true,
                (url, headers, body, timeout, cancel) -> {
                    claudeHeaders.set(headers);
                    return new HttpTransport.HttpResult(200,
                            "{\"model\":\"claude-sonnet-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}", 10, false, null);
                });
        ModelResponse cRes = claudeBearer.generate(ModelRequest.simple("", "ping"), () -> false);
        assertTrue(cRes.success(), "Claude Bearer OAuth call succeeded");
        assertEquals("Bearer oauth-claude-tok", claudeHeaders.get().get("Authorization"), "Claude Bearer header set");
        assertTrue(!claudeHeaders.get().containsKey("x-api-key"), "Claude x-api-key omitted in Bearer mode");

        AtomicReference<Map<String, String>> geminiHeaders = new AtomicReference<>();
        GeminiClient geminiBearer = new GeminiClient(
                "https://generativelanguage.googleapis.com/v1beta", "ya29.oauth-gem", "gemini-4-neon",
                GeminiApiStyle.INTERACTIONS, true,
                (url, headers, body, timeout, cancel) -> {
                    geminiHeaders.set(headers);
                    return new HttpTransport.HttpResult(200,
                            "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}]}", 10, false, null);
                });
        ModelResponse gRes = geminiBearer.generate(ModelRequest.simple("", "ping"), () -> false);
        assertTrue(gRes.success(), "Gemini Bearer OAuth call succeeded");
        assertEquals("Bearer ya29.oauth-gem", geminiHeaders.get().get("Authorization"), "Gemini Bearer header set");
        assertTrue(!geminiHeaders.get().containsKey("x-goog-api-key"), "Gemini x-goog-api-key omitted in Bearer mode");
    }

    private static void testAgentIndependenceSkills() {
        // 1. Prompt skill directive generation across presets
        AgentSkillConfig strict = AgentSkillConfig.forPreset(AgentSkillConfig.Preset.STRICT_ADVERSARIAL);
        String strictPeerPrompt = PromptTemplates.reviewerSystem(strict, "");
        assertTrue(strictPeerPrompt.contains("ANTI-SYCOPHANCY"), "Anti-sycophancy skill injected");
        assertTrue(strictPeerPrompt.contains("TOPIC-BY-TOPIC PEER ANALYSIS"), "Topic-by-topic verification matrix injected");
        assertTrue(strictPeerPrompt.contains("[DISPUTED]"), "Structured [DISPUTED] status tag required");
        assertTrue(strictPeerPrompt.contains("HIGH-PRECISION DETAIL"), "Detail precision skill injected");
        assertTrue(strictPeerPrompt.contains("DEVIL'S ADVOCATE"), "Devil's Advocate skill injected in STRICT_ADVERSARIAL");

        AgentSkillConfig balanced = AgentSkillConfig.forPreset(AgentSkillConfig.Preset.BALANCED);
        String balancedPeerPrompt = PromptTemplates.reviewerSystem(balanced, "");
        assertTrue(!balancedPeerPrompt.contains("ANTI-SYCOPHANCY"), "Balanced preset omits strict adversarial block");
        assertTrue(balancedPeerPrompt.contains("HIGH-PRECISION DETAIL"), "Balanced preset still retains detail precision");

        // 2. Blind peer labeling in Parallel Council Cross-Critique (no model brand names leaked!)
        AtomicReference<String> critiquePromptSeen = new AtomicReference<>();
        Map<ModelProviderType, ModelClient> councilMocks = new EnumMap<>(ModelProviderType.class);
        councilMocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> {
            if (req.systemPrompt().contains("Cross-Critique")) {
                critiquePromptSeen.set(req.userPrompt());
                return "| Topic | Status | Analysis |\n| :--- | :--- | :--- |\n| Design | [DISPUTED] | Needs lock |";
            }
            return "Proposal content alpha";
        }));
        councilMocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> "Proposal content beta"));
        councilMocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "Proposal content gamma"));

        OrchestrationEngine councilEngine = new OrchestrationEngine(councilMocks);
        OrchestrationEngine.RunOptions blindOpts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.PARALLEL_COUNCIL,
                new RoleAssignment(),
                List.of(), List.of(), 2, true, 4096, 30, "",
                AgentSkillConfig.forPreset(AgentSkillConfig.Preset.INDEPENDENT_PRECISE)
        );
        councilEngine.execute("Design thread-safe queue", blindOpts, OrchestrationListener.NO_OP, () -> false);
        String seen = critiquePromptSeen.get();
        assertTrue(seen != null && seen.contains("Proposal B"), "Blind peer alias used in cross-critique");
        assertTrue(!seen.contains("OpenAI ChatGPT") && !seen.contains("Google Gemini"),
                "Peer brand names anonymized in blind review mode");

        // 3. Anti-rubber-stamp contradiction guard: reviewer says APPROVE while flagging [DISPUTED]/[MAJOR]
        AtomicInteger coderRuns = new AtomicInteger(0);
        AtomicInteger reviewerRuns = new AtomicInteger(0);
        Map<ModelProviderType, ModelClient> guardMocks = new EnumMap<>(ModelProviderType.class);
        guardMocks.put(ModelProviderType.CLAUDE, stub(ModelProviderType.CLAUDE, req -> {
            int n = coderRuns.incrementAndGet();
            return "### FILE: svc.py\n```python\ndef run(x):\n    return " + (n == 1 ? "x" : "x or 0") + "\n```";
        }));
        guardMocks.put(ModelProviderType.CHATGPT, stub(ModelProviderType.CHATGPT, req -> {
            int n = reviewerRuns.incrementAndGet();
            return n == 1
                    ? "| Topic | Status | Note |\n| :--- | :--- | :--- |\n| None check | [DISPUTED] | Missing None guard |\nVERDICT: APPROVE"
                    : "| Topic | Status | Note |\n| :--- | :--- | :--- |\n| None check | [VERIFIED] | Guard present |\nVERDICT: APPROVE";
        }));
        guardMocks.put(ModelProviderType.GEMINI, stub(ModelProviderType.GEMINI, req -> "All topics [VERIFIED].\nVERDICT: APPROVE"));

        OrchestrationEngine guardEngine = new OrchestrationEngine(guardMocks);
        OrchestrationEngine.RunOptions guardOpts = new OrchestrationEngine.RunOptions(
                OrchestrationMode.ITERATIVE_LOOP,
                new RoleAssignment(ModelProviderType.GEMINI, ModelProviderType.CLAUDE, ModelProviderType.CHATGPT, ModelProviderType.CLAUDE),
                List.of(), List.of(), 2, true, 4096, 30, "",
                AgentSkillConfig.forPreset(AgentSkillConfig.Preset.INDEPENDENT_PRECISE)
        );
        OrchestrationResult guardRes = guardEngine.execute("Handle None safely", guardOpts, OrchestrationListener.NO_OP, () -> false);
        assertEquals(2, coderRuns.get(), "Contradiction guard forced refinement despite rubber-stamp APPROVE");
        assertTrue(guardRes.finalOutput().contains("Consensus Reached in Round 2"), "Second round verified cleanly");

        // 4. Markdown badge rendering for verification tags
        String badgeHtml = MarkdownHtmlRenderer.toHtmlDocument("[VERIFIED] [DISPUTED] [MISSING] [UNPROVEN] [ACCEPTED] [REJECTED]", true);
        assertTrue(badgeHtml.contains("badge-ok") && badgeHtml.contains("badge-crit")
                && badgeHtml.contains("badge-high") && badgeHtml.contains("badge-med"),
                "Verification status badges rendered");
    }

    private static void testRestApiModelDiscovery() {
        ModelDiscoveryService.clearCache();

        // 1. Anthropic paginated GET /v1/models
        AtomicInteger anthropicPages = new AtomicInteger(0);
        ModelDiscoveryService anthropicDiscovery = new ModelDiscoveryService((url, headers, timeout, cancel) -> {
            assertEquals("sk-ant-key", headers.get("x-api-key"), "Anthropic discovery x-api-key header");
            assertEquals("2026-10-21", headers.get("anthropic-version"), "Anthropic discovery version header");
            int p = anthropicPages.incrementAndGet();
            if (p == 1) {
                assertTrue(url.equals("https://api.anthropic.com/v1/models?limit=100"), "Anthropic page 1 URL: " + url);
                return new HttpTransport.HttpResult(200,
                        "{\"data\":[{\"id\":\"claude-live-a\",\"display_name\":\"Claude Live A\"}],\"has_more\":true,\"last_id\":\"claude-live-a\"}",
                        12, false, null);
            }
            assertTrue(url.contains("after_id=claude-live-a"), "Anthropic page 2 cursor URL: " + url);
            return new HttpTransport.HttpResult(200,
                    "{\"data\":[{\"id\":\"claude-live-b\",\"display_name\":\"Claude Live B\"}],\"has_more\":false,\"last_id\":\"claude-live-b\"}",
                    10, false, null);
        }, null);

        ProviderConfig claudeCfg = ProviderConfig.defaultsFor(ModelProviderType.CLAUDE);
        ModelDiscoveryService.DiscoveryResult cRes = anthropicDiscovery.fetchAvailableModels(
                ModelProviderType.CLAUDE, claudeCfg, "sk-ant-key", true, () -> false);
        assertTrue(cRes.success(), "Anthropic discovery succeeded: " + cRes.errorMessage());
        assertEquals(2, cRes.models().size(), "Anthropic paginated models count");
        assertEquals("claude-live-a", cRes.models().get(0).id(), "Anthropic page 1 model");
        assertEquals("claude-live-b", cRes.models().get(1).id(), "Anthropic page 2 model");

        // 2. OpenAI GET /v1/models filtering & sorting + custom gateway fallback
        ModelDiscoveryService openAiDiscovery = new ModelDiscoveryService((url, headers, timeout, cancel) -> {
            assertEquals("Bearer sk-oai-key", headers.get("Authorization"), "OpenAI Bearer header");
            if (url.contains("custom-gateway")) {
                return new HttpTransport.HttpResult(200,
                        "{\"object\":\"list\",\"data\":[{\"id\":\"custom-embedding-chat\",\"created\":100}]}",
                        10, false, null);
            }
            assertEquals("https://api.openai.com/v1/models", url, "OpenAI models endpoint");
            String body = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "gpt-older", "created": 1700000000},
                        {"id": "text-embedding-3-large", "created": 1750000000},
                        {"id": "whisper-1", "created": 1750000000},
                        {"id": "gpt-newest", "created": 1780000000},
                        {"id": "dall-e-3", "created": 1770000000}
                      ]
                    }
                    """;
            return new HttpTransport.HttpResult(200, body, 15, false, null);
        }, null);

        ProviderConfig oaiCfg = ProviderConfig.defaultsFor(ModelProviderType.CHATGPT);
        ModelDiscoveryService.DiscoveryResult oRes = openAiDiscovery.fetchAvailableModels(
                ModelProviderType.CHATGPT, oaiCfg, "sk-oai-key", true, () -> false);
        assertTrue(oRes.success(), "OpenAI discovery succeeded");
        assertEquals(List.of("gpt-newest", "gpt-older"), oRes.modelIds(), "OpenAI non-chat models filtered and sorted newest first");

        oaiCfg.baseUrl = "https://custom-gateway.local/v1";
        ModelDiscoveryService.DiscoveryResult customRes = openAiDiscovery.fetchAvailableModels(
                ModelProviderType.CHATGPT, oaiCfg, "sk-oai-key", true, () -> false);
        assertTrue(customRes.success() && customRes.containsModelId("custom-embedding-chat"),
                "Custom gateway keeps all models when filter would drop everything");

        // 3. Gemini GET /v1beta/models with pagination, models/ stripping, supportedGenerationMethods filtering & version sort
        AtomicInteger geminiPages = new AtomicInteger(0);
        ModelDiscoveryService geminiDiscovery = new ModelDiscoveryService((url, headers, timeout, cancel) -> {
            assertEquals("gem-api-key", headers.get("x-goog-api-key"), "Gemini x-goog-api-key header");
            int p = geminiPages.incrementAndGet();
            if (p == 1) {
                assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100", url, "Gemini page 1 URL");
                String body = """
                        {
                          "models": [
                            {"name": "models/gemini-2.5-pro", "displayName": "Gemini 2.5 Pro", "supportedGenerationMethods": ["generateContent"]},
                            {"name": "models/text-embedding-004", "displayName": "Embedding", "supportedGenerationMethods": ["embedContent"]},
                            {"name": "models/imagen-4.0", "displayName": "Imagen 4", "supportedGenerationMethods": ["generateContent"]}
                          ],
                          "nextPageToken": "tok2"
                        }
                        """;
                return new HttpTransport.HttpResult(200, body, 15, false, null);
            }
            assertTrue(url.contains("pageToken=tok2"), "Gemini page 2 token URL: " + url);
            String body = """
                    {
                      "models": [
                        {"name": "models/gemini-3.8-flash", "displayName": "Gemini 3.8 Flash", "supportedGenerationMethods": ["generateContent"]}
                      ]
                    }
                    """;
            return new HttpTransport.HttpResult(200, body, 12, false, null);
        }, null);

        ProviderConfig gemCfg = ProviderConfig.defaultsFor(ModelProviderType.GEMINI);
        ModelDiscoveryService.DiscoveryResult gRes = geminiDiscovery.fetchAvailableModels(
                ModelProviderType.GEMINI, gemCfg, "gem-api-key", true, () -> false);
        assertTrue(gRes.success(), "Gemini discovery succeeded: " + gRes.errorMessage());
        assertEquals(List.of("gemini-3.8-flash", "gemini-2.5-pro"), gRes.modelIds(),
                "Gemini models stripped, filtered to generateContent chat models, and sorted newest version first");
    }

    private static void testCliModelDiscoveryAndNoHardcodedModels() {
        ModelDiscoveryService.clearCache();

        // Verify no hardcoded static model presets remain on ModelProviderType or ProviderConfig
        for (ModelProviderType t : ModelProviderType.values()) {
            assertTrue(t.getModelPresets().isEmpty(), t + " must not have hardcoded modelPresets");
            assertEquals("", t.getDefaultModel(), t + " must not have hardcoded defaultModel");
            assertEquals("", ProviderConfig.defaultsFor(t).modelId, t + " ProviderConfig default modelId is empty");
        }

        // REST clients reject empty model ID instead of silently using a hardcoded model
        ClaudeClient emptyClaude = new ClaudeClient("https://api.anthropic.com", "sk-key", "", "2026-10-21",
                (u, h, b, to, c) -> new HttpTransport.HttpResult(200, "{}", 1, false, null));
        ModelResponse emptyResp = emptyClaude.generate(ModelRequest.simple("", "hi"), () -> false);
        assertTrue(!emptyResp.success() && emptyResp.errorMessage().contains("Refresh Models"),
                "Empty REST model ID produces clear Refresh Models prompt");

        // Gemini CLI JSON discovery
        ModelDiscoveryService cliDiscovery = new ModelDiscoveryService(null, (exe, args, timeout, cancel) -> {
            if ("gemini".equals(exe) && args.equals(List.of("models", "-o", "json"))) {
                return new CliModelClient.CliCommandResult(0,
                        "[{\"modelId\":\"auto\",\"displayName\":\"Auto\"},{\"modelId\":\"gemini-2.5-pro\",\"displayName\":\"gemini-2.5-pro\"}]",
                        "", false, false, null);
            }
            if ("agy".equals(exe) && args.equals(List.of("models"))) {
                // Covers both non-TTY single-tab output and TTY multi-space output
                String agyOut = "claude-opus-5-5-high\tClaude Opus 5.5 (High)\n"
                        + "gemini-4-neon-high\tGemini 4 Neon (High)\n"
                        + "\r⠋ Fetching available models...\rgemini-3.8-flash-medium        Gemini 3.8 Flash (Medium)\n";
                return new CliModelClient.CliCommandResult(0, agyOut, "", false, false, null);
            }
            if ("codex".equals(exe) && args.equals(List.of("debug", "models"))) {
                String codexJson = "{\"models\":["
                        + "{\"slug\":\"gpt-reserve\",\"display_name\":\"Reserve\",\"visibility\":\"hide\",\"priority\":1},"
                        + "{\"slug\":\"gpt-6.2-astra\",\"display_name\":\"GPT-6.2-Astra\",\"visibility\":\"list\",\"priority\":2},"
                        + "{\"slug\":\"gpt-6.2-sol\",\"display_name\":\"GPT-6.2-Sol\",\"visibility\":\"list\",\"priority\":1}"
                        + "]}";
                return new CliModelClient.CliCommandResult(0, codexJson, "", false, false, null);
            }
            if ("claude".equals(exe) && args.equals(List.of("auth", "status", "--json"))) {
                // Pretty-printed JSON with space after colon (matches real claude auth status --json)
                return new CliModelClient.CliCommandResult(0, "{\n  \"loggedIn\": true,\n  \"authMethod\": \"claude.ai\"\n}\n", "", false, false, null);
            }
            return new CliModelClient.CliCommandResult(1, "", "unsupported", false, false, null);
        });

        ProviderConfig gemCliCfg = ProviderConfig.defaultsFor(ModelProviderType.GEMINI);
        gemCliCfg.connectionMode = ConnectionMode.LOCAL_CLI;
        gemCliCfg.cliExecutable = "gemini";
        ModelDiscoveryService.DiscoveryResult gemCliRes = cliDiscovery.fetchAvailableModels(
                ModelProviderType.GEMINI, gemCliCfg, "", true, () -> false);
        assertTrue(gemCliRes.success(), "gemini CLI discovery succeeded");
        assertEquals(List.of("auto", "gemini-2.5-pro"), gemCliRes.modelIds(), "gemini CLI live models");

        gemCliCfg.cliExecutable = "agy";
        ModelDiscoveryService.DiscoveryResult agyRes = cliDiscovery.fetchAvailableModels(
                ModelProviderType.GEMINI, gemCliCfg, "", true, () -> false);
        assertTrue(agyRes.success(), "agy CLI discovery succeeded");
        assertEquals("gemini-4-neon-high", agyRes.models().get(0).id(), "agy prioritizes Gemini models for Gemini provider");
        assertTrue(agyRes.containsModelId("claude-opus-5-5-high"), "agy retains all live available models");

        ProviderConfig codexCfg = ProviderConfig.defaultsFor(ModelProviderType.CHATGPT);
        codexCfg.connectionMode = ConnectionMode.LOCAL_CLI;
        codexCfg.cliExecutable = "codex";
        ModelDiscoveryService.DiscoveryResult codexRes = cliDiscovery.fetchAvailableModels(
                ModelProviderType.CHATGPT, codexCfg, "", true, () -> false);
        assertTrue(codexRes.success(), "codex debug models succeeded");
        assertEquals(List.of("gpt-6.2-sol", "gpt-6.2-astra"), codexRes.modelIds(),
                "codex filters hidden models and sorts by priority");

        ProviderConfig claudeCliCfg = ProviderConfig.defaultsFor(ModelProviderType.CLAUDE);
        claudeCliCfg.connectionMode = ConnectionMode.LOCAL_CLI;
        claudeCliCfg.cliExecutable = "claude";
        ModelDiscoveryService.DiscoveryResult claudeCliRes = cliDiscovery.fetchAvailableModels(
                ModelProviderType.CLAUDE, claudeCliCfg, "", true, () -> false);
        assertTrue(claudeCliRes.success() && claudeCliRes.containsModelId("auto"),
                "claude CLI uses account auto default when no API key is set");
    }

    private static void testCliAuthNormalizationAndSignOut() throws Exception {
        // 1. agy must NEVER receive -o or empty -p ""; must receive --output-format text --model <m> -p {prompt}
        List<String> agyNormalized = CliModelClient.normalizeArgsForExecution(
                ModelProviderType.GEMINI, "agy", List.of("-p", "", "-o", "text"), "gemini-4-neon-high");
        assertTrue(!agyNormalized.contains("-o"), "agy args never contain -o");
        assertTrue(agyNormalized.contains("--output-format") && agyNormalized.contains("text"), "agy uses --output-format text");
        assertTrue(agyNormalized.contains("--model") && agyNormalized.contains("gemini-4-neon-high"), "agy passes selected --model");
        assertTrue(agyNormalized.contains("{prompt}"), "agy injects prompt via -p {prompt}");

        // 2. gemini replaces -o with --output-format and injects -m only for concrete models
        List<String> gemAuto = CliModelClient.normalizeArgsForExecution(
                ModelProviderType.GEMINI, "gemini", List.of("-p", "", "-o", "text"), "auto");
        assertTrue(!gemAuto.contains("-m") && gemAuto.contains("--output-format"), "gemini auto omits -m and normalizes -o");
        List<String> gemPinned = CliModelClient.normalizeArgsForExecution(
                ModelProviderType.GEMINI, "gemini", List.of("-p", "", "-o", "text"), "gemini-2.5-pro");
        assertTrue(gemPinned.contains("-m") && gemPinned.contains("gemini-2.5-pro"), "gemini passes concrete -m model");

        // 3. codex injects -m right after exec + --skip-git-repo-check + --ephemeral + --color never
        List<String> codexPinned = CliModelClient.normalizeArgsForExecution(
                ModelProviderType.CHATGPT, "codex", List.of("exec", "-"), "gpt-6.2-sol");
        assertEquals("exec", codexPinned.get(0), "codex starts with exec");
        assertTrue(codexPinned.contains("-m") && codexPinned.contains("gpt-6.2-sol"), "codex passes -m model");
        assertTrue(codexPinned.contains("--skip-git-repo-check") && codexPinned.contains("--ephemeral"), "codex safety flags");
        assertEquals("-", codexPinned.get(codexPinned.size() - 1), "codex stdin sentinel stays last");

        // 4. claude appends --model for concrete model and omits for auto
        List<String> claudePinned = CliModelClient.normalizeArgsForExecution(
                ModelProviderType.CLAUDE, "claude", List.of("-p", "--output-format", "text"), "claude-sonnet-5-5");
        assertTrue(claudePinned.contains("--model") && claudePinned.contains("claude-sonnet-5-5"), "claude passes --model");

        // 5. Codex banner stripping surfaces the actual error instead of truncating the startup header
        String codexStderr = """
                OpenAI Codex v0.164.0
                --------
                workdir: /home/otari
                model: gpt-6.2-astra
                provider: openai
                sandbox: read-only
                ERROR: The model 'gpt-6.2-astra' is not available on your current plan.
                """;
        String extracted = CliModelClient.extractCliErrorDetail(ModelProviderType.CHATGPT, "codex", codexStderr, "");
        assertTrue(extracted.contains("not available on your current plan"), "Codex real error extracted: " + extracted);
        assertTrue(!extracted.contains("workdir:"), "Codex startup banner stripped: " + extracted);

        // 6. Never open duplicate browser tabs for claude/codex/gemini native browser logins,
        //    and never open Claude's manual paste-code fallback URL (/oauth/code/callback)
        assertTrue(!CliModelClient.shouldAutoOpenPrintedUrl(
                "codex",
                "Starting local login server on http://localhost:1455.\nIf your browser did not open, navigate to:\nhttps://auth.openai.com/oauth/authorize?client_id=x",
                "https://auth.openai.com/oauth/authorize?client_id=x"),
                "Codex must NOT open a second duplicate browser tab when codex login launches browser natively");
        assertTrue(CliModelClient.shouldAutoOpenPrintedUrl(
                "codex",
                "Failed to open browser automatically.\nhttps://auth.openai.com/oauth/authorize?client_id=x",
                "https://auth.openai.com/oauth/authorize?client_id=x"),
                "Codex falls back to BrowserUtil only when native browser launch fails");
        assertTrue(!CliModelClient.shouldAutoOpenPrintedUrl(
                "claude",
                "Opening browser to sign in...\nIf the browser didn't open, visit: https://claude.com/cai/oauth/authorize?redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback",
                "https://claude.com/cai/oauth/authorize?redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"),
                "Claude must NOT open manual copy-paste code fallback URL");
        assertTrue(!CliModelClient.shouldAutoOpenPrintedUrl(
                "claude",
                "Failed to open browser\nhttps://claude.com/cai/oauth/authorize?redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback",
                "https://claude.com/cai/oauth/authorize?redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"),
                "Claude manual /oauth/code/callback URL is never auto-opened");
        assertTrue(!CliModelClient.shouldAutoOpenPrintedUrl(
                "gemini",
                "Attempting to open authentication page in your browser.\nhttps://accounts.google.com/o/oauth2/v2/auth?client_id=x",
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=x"),
                "Gemini must NOT open a duplicate tab when gemini CLI opens browser natively");
        assertTrue(CliModelClient.shouldAutoOpenPrintedUrl(
                "gemini",
                "Failed to open browser with error: ENOENT\nhttps://accounts.google.com/o/oauth2/v2/auth?client_id=x",
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=x"),
                "Gemini falls back to BrowserUtil if native open fails");

        // 7. Gemini CLI sign-out support and credential file cleanup
        assertTrue(CliModelClient.supportsCliLogout(ModelProviderType.GEMINI, "gemini"), "gemini CLI supports Sign Out");
        assertTrue(!CliModelClient.supportsCliLogout(ModelProviderType.GEMINI, "agy"), "agy CLI hides unsupported direct Sign Out");
        assertTrue(CliModelClient.supportsCliLogout(ModelProviderType.CLAUDE, "claude"), "claude CLI supports Sign Out");
        assertTrue(CliModelClient.supportsCliLogout(ModelProviderType.CHATGPT, "codex"), "codex CLI supports Sign Out");

        Path tmpGemDir = Files.createTempDirectory("trimodel-gemini-test-");
        try {
            Files.writeString(tmpGemDir.resolve("oauth_creds.json"), "{\"access_token\":\"tok123\"}", StandardCharsets.UTF_8);
            Files.writeString(tmpGemDir.resolve("google_accounts.json"), "{\"active\":\"dev@example.com\",\"old\":[]}", StandardCharsets.UTF_8);
            boolean cleared = CliModelClient.clearGeminiOAuthCredentials(tmpGemDir);
            assertTrue(cleared, "clearGeminiOAuthCredentials reports credentials removed");
            assertTrue(!Files.exists(tmpGemDir.resolve("oauth_creds.json")), "oauth_creds.json deleted");
            String accAfter = Files.readString(tmpGemDir.resolve("google_accounts.json"), StandardCharsets.UTF_8);
            assertTrue(accAfter.contains("\"active\": null"), "google_accounts.json active user cleared: " + accAfter);
        } finally {
            Files.deleteIfExists(tmpGemDir.resolve("oauth_creds.json"));
            Files.deleteIfExists(tmpGemDir.resolve("google_accounts.json"));
            Files.deleteIfExists(tmpGemDir);
        }
    }

    private static void testSettingsMigration() {
        TriModelSettings settings = new TriModelSettings();
        TriModelSettings.State legacy = new TriModelSettings.State();
        legacy.schemaVersion = 0;
        legacy.gemini.connectionMode = ConnectionMode.LOCAL_CLI;
        legacy.gemini.cliExecutable = "agy";
        legacy.gemini.cliArgs = "-p \"\" -o text";
        legacy.gemini.modelId = "gemini-4-neon";
        legacy.gemini.oauthScopes = "https://www.googleapis.com/auth/generative-language";
        legacy.chatgpt.connectionMode = ConnectionMode.LOCAL_CLI;
        legacy.chatgpt.cliArgs = "exec -";
        legacy.chatgpt.modelId = "gpt-6.2-sol";

        settings.loadState(legacy);
        ProviderConfig migratedGem = settings.getProviderConfig(ModelProviderType.GEMINI);
        ProviderConfig migratedGpt = settings.getProviderConfig(ModelProviderType.CHATGPT);

        assertTrue(!migratedGem.cliArgs.contains("-o "), "Migrated agy args remove -o: " + migratedGem.cliArgs);
        assertTrue(migratedGem.cliArgs.contains("{prompt}"), "Migrated agy args include {prompt}: " + migratedGem.cliArgs);
        assertEquals("", migratedGem.modelId, "Legacy hardcoded CLI model cleared for Gemini");
        assertEquals("", migratedGpt.modelId, "Legacy hardcoded CLI model cleared for ChatGPT");
        assertTrue(migratedGpt.cliArgs.contains("--skip-git-repo-check"), "Legacy Codex args migrated");
        assertTrue(migratedGem.oauthScopes.contains("cloud-platform"), "Legacy Gemini OAuth scope migrated");
    }

    private static void testCrossPlatformPathAndExecutableDetection() throws Exception {
        // 1. Windows candidate directories & .exe/.cmd precedence over POSIX shims
        Map<String, String> winEnv = new java.util.LinkedHashMap<>();
        winEnv.put("Path", "C:\\Windows\\System32");
        winEnv.put("APPDATA", "C:\\Users\\dev\\AppData\\Roaming");
        winEnv.put("LOCALAPPDATA", "C:\\Users\\dev\\AppData\\Local");
        winEnv.put("ProgramFiles", "C:\\Program Files");
        winEnv.put("ProgramData", "C:\\ProgramData");
        winEnv.put("NVM_SYMLINK", "C:\\Program Files\\nodejs");
        winEnv.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");

        List<String> winDirs = CliModelClient.candidateBinDirectories("Windows 11", "C:\\Users\\dev", winEnv);
        assertTrue(winDirs.contains("C:\\Users\\dev\\AppData\\Roaming\\npm"), "Windows includes %APPDATA%\\npm");
        assertTrue(winDirs.contains("C:\\Users\\dev\\AppData\\Local\\Microsoft\\WinGet\\Links"), "Windows includes WinGet Links");
        assertTrue(winDirs.contains("C:\\Users\\dev\\scoop\\shims"), "Windows includes Scoop shims");
        assertTrue(winDirs.contains("C:\\ProgramData\\chocolatey\\bin"), "Windows includes Chocolatey bin");
        assertTrue(winDirs.contains("C:\\Users\\dev\\AppData\\Local\\Programs\\claude-code"), "Windows includes claude-code dir");
        assertTrue(winDirs.contains("C:\\Users\\dev\\AppData\\Local\\Antigravity\\bin"), "Windows includes Antigravity bin");

        // Verify Windows skips extensionless npm POSIX shims and prefers .exe > .cmd > .bat
        Path winTmp = Files.createTempDirectory("trimodel-win-bin-");
        try {
            Files.writeString(winTmp.resolve("gemini"), "#!/bin/sh\n", StandardCharsets.UTF_8);
            Files.writeString(winTmp.resolve("gemini.cmd"), "@echo off\n", StandardCharsets.UTF_8);
            Files.writeString(winTmp.resolve("claude.cmd"), "@echo off\n", StandardCharsets.UTF_8);
            Files.writeString(winTmp.resolve("claude.exe"), "MZ", StandardCharsets.UTF_8);

            String resolvedGem = CliModelClient.resolveExecutable("gemini", "Windows 11", List.of(winTmp.toString()), winEnv);
            assertTrue(resolvedGem != null && resolvedGem.endsWith("gemini.cmd"),
                    "Windows resolves gemini.cmd instead of POSIX shim: " + resolvedGem);
            String resolvedClaude = CliModelClient.resolveExecutable("claude", "Windows 11", List.of(winTmp.toString()), winEnv);
            assertTrue(resolvedClaude != null && resolvedClaude.endsWith("claude.exe"),
                    "Windows prefers native claude.exe over claude.cmd: " + resolvedClaude);
        } finally {
            Files.deleteIfExists(winTmp.resolve("gemini"));
            Files.deleteIfExists(winTmp.resolve("gemini.cmd"));
            Files.deleteIfExists(winTmp.resolve("claude.cmd"));
            Files.deleteIfExists(winTmp.resolve("claude.exe"));
            Files.deleteIfExists(winTmp);
        }

        // 2. Linux candidate directories & newest-first nvm sorting
        Path linuxHome = Files.createTempDirectory("trimodel-linux-home-");
        try {
            Files.createDirectories(linuxHome.resolve(".nvm/versions/node/v18.19.0/bin"));
            Files.createDirectories(linuxHome.resolve(".nvm/versions/node/v22.12.0/bin"));
            Files.writeString(linuxHome.resolve(".npmrc"), "prefix=~/.custom-npm\n", StandardCharsets.UTF_8);

            Map<String, String> linuxEnv = Map.of("PATH", "/usr/bin", "XDG_BIN_HOME", linuxHome + "/xdg-bin");
            List<String> linuxDirs = CliModelClient.candidateBinDirectories("Linux", linuxHome.toString(), linuxEnv);
            assertTrue(linuxDirs.contains("/home/linuxbrew/.linuxbrew/bin"), "Linux includes Linuxbrew");
            assertTrue(linuxDirs.contains("/snap/bin"), "Linux includes Snap");
            assertTrue(linuxDirs.contains(linuxHome + "/.local/share/pnpm"), "Linux includes pnpm");
            assertTrue(linuxDirs.contains(linuxHome + "/.custom-npm/bin"), "Linux parses ~/.npmrc prefix");
            assertTrue(linuxDirs.contains(linuxHome + "/xdg-bin"), "Linux includes XDG_BIN_HOME");
            int idx22 = linuxDirs.indexOf(linuxHome.resolve(".nvm/versions/node/v22.12.0/bin").toAbsolutePath().toString());
            int idx18 = linuxDirs.indexOf(linuxHome.resolve(".nvm/versions/node/v18.19.0/bin").toAbsolutePath().toString());
            assertTrue(idx22 >= 0 && idx18 >= 0 && idx22 < idx18, "nvm sorts v22 before v18");
        } finally {
            Files.deleteIfExists(linuxHome.resolve(".npmrc"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions/node/v18.19.0/bin"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions/node/v18.19.0"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions/node/v22.12.0/bin"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions/node/v22.12.0"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions/node"));
            Files.deleteIfExists(linuxHome.resolve(".nvm/versions"));
            Files.deleteIfExists(linuxHome.resolve(".nvm"));
            Files.deleteIfExists(linuxHome);
        }

        // 3. macOS candidate directories & OS-specific install hints
        List<String> macDirs = CliModelClient.candidateBinDirectories("Mac OS X", "/Users/dev", Map.of("PATH", "/usr/bin"));
        assertTrue(macDirs.contains("/opt/homebrew/bin"), "macOS includes Homebrew");
        assertTrue(macDirs.contains("/Users/dev/Library/pnpm"), "macOS includes Library/pnpm");
        assertTrue(macDirs.contains("/Applications/Antigravity.app/Contents/Resources/app/bin"), "macOS includes Antigravity app bin");

        assertTrue(ModelProviderType.CLAUDE.getCliInstallHint("Windows 11").contains("install.ps1"), "Windows Claude PowerShell install hint");
        assertTrue(ModelProviderType.CLAUDE.getCliInstallHint("Linux").contains("install.sh"), "Linux Claude shell install hint");
        assertTrue(ModelProviderType.CLAUDE.getCliInstallHint("Mac OS X").contains("brew"), "macOS Claude Homebrew install hint");
    }

    private static void testEnvironmentAutoDetector() {
        EnvironmentAutoDetector.Probe fakeProbe = new EnvironmentAutoDetector.Probe() {
            @Override
            public String osName() {
                return "Linux";
            }

            @Override
            public String osArch() {
                return "amd64";
            }

            @Override
            public Map<String, String> env() {
                return Map.of(
                        "OPENAI_API_KEY", "sk-env-openai",
                        "OPENAI_BASE_URL", "https://gateway.corp.local/v1"
                );
            }

            @Override
            public String storedApiKey(ModelProviderType type) {
                return "";
            }

            @Override
            public boolean hasOAuthSession(ModelProviderType type) {
                return false;
            }

            @Override
            public CliModelClient.CliAuthStatus checkCli(ModelProviderType type, String executable) {
                if (type == ModelProviderType.CLAUDE && "claude".equals(executable)) {
                    return new CliModelClient.CliAuthStatus(true, "/usr/local/bin/claude",
                            CliModelClient.AuthState.SIGNED_IN, "claude CLI signed in (dev@corp)");
                }
                if (type == ModelProviderType.GEMINI && "gemini".equals(executable)) {
                    return new CliModelClient.CliAuthStatus(true, "/usr/local/bin/gemini",
                            CliModelClient.AuthState.SIGNED_OUT, "gemini CLI installed — sign in required");
                }
                if (type == ModelProviderType.GEMINI && "agy".equals(executable)) {
                    return new CliModelClient.CliAuthStatus(true, "/home/dev/.local/bin/agy",
                            CliModelClient.AuthState.UNKNOWN, "agy CLI ready");
                }
                return new CliModelClient.CliAuthStatus(false, "", CliModelClient.AuthState.NOT_INSTALLED, "not installed");
            }

            @Override
            public ModelDiscoveryService.DiscoveryResult discoverModels(
                    ModelProviderType type,
                    ProviderConfig config,
                    String apiKeyOverride,
                    BooleanSupplier isCancelled
            ) {
                if (type == ModelProviderType.CLAUDE) {
                    return ModelDiscoveryService.DiscoveryResult.ok(
                            List.of(ModelDiscoveryService.DiscoveredModel.auto("Auto (Claude CLI default)")), "claude CLI");
                }
                if (type == ModelProviderType.CHATGPT) {
                    return ModelDiscoveryService.DiscoveryResult.ok(
                            List.of(new ModelDiscoveryService.DiscoveredModel("gpt-live-gateway", "GPT Live")), "OpenAI API");
                }
                if (type == ModelProviderType.GEMINI && "agy".equals(config.cliExecutable)) {
                    return ModelDiscoveryService.DiscoveryResult.ok(
                            List.of(new ModelDiscoveryService.DiscoveredModel("gemini-4-neon-high", "Gemini 4 Neon (High)")), "agy CLI");
                }
                return ModelDiscoveryService.DiscoveryResult.error("not signed in");
            }
        };

        EnvironmentAutoDetector detector = new EnvironmentAutoDetector(fakeProbe);
        TriModelSettings settings = new TriModelSettings();
        EnvironmentAutoDetector.EnvironmentReport report = detector.applyFirstRunAutoDetect(settings, () -> false);

        assertEquals("Linux (amd64)", report.osLabel(), "Formatted OS label");
        assertTrue(settings.isAutoDetectedOnce(), "Marked autoDetectedOnce");

        ProviderConfig claude = settings.getProviderConfig(ModelProviderType.CLAUDE);
        assertEquals(ConnectionMode.LOCAL_CLI, claude.connectionMode, "Claude auto-selected signed-in CLI");
        assertEquals("auto", claude.modelId, "Claude selected CLI auto model");

        ProviderConfig gpt = settings.getProviderConfig(ModelProviderType.CHATGPT);
        assertEquals(ConnectionMode.REST_API, gpt.connectionMode, "ChatGPT auto-selected REST API from env key");
        assertEquals("https://gateway.corp.local/v1", gpt.baseUrl, "ChatGPT picked up OPENAI_BASE_URL from env");
        assertEquals("gpt-live-gateway", gpt.modelId, "ChatGPT picked up live discovered model");

        ProviderConfig gem = settings.getProviderConfig(ModelProviderType.GEMINI);
        assertEquals(ConnectionMode.LOCAL_CLI, gem.connectionMode, "Gemini auto-selected CLI mode");
        assertEquals("agy", gem.cliExecutable, "Gemini auto-fell back to working signed-in agy when gemini CLI was signed out");
        assertEquals(EnvironmentAutoDetector.AGY_DEFAULT_ARGS, gem.cliArgs, "Gemini auto-configured agy-safe args");
        assertEquals("gemini-4-neon-high", gem.modelId, "Gemini picked live agy Gemini model");
    }

    private static ModelClient stub(ModelProviderType type, java.util.function.Function<ModelRequest, String> fn) {
        return new ModelClient() {
            @Override
            public ModelProviderType getProviderType() {
                return type;
            }

            @Override
            public String getModelId() {
                return "stub-" + type.name().toLowerCase();
            }

            @Override
            public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
                if (isCancelled.getAsBoolean()) {
                    return ModelResponse.error(type, getModelId(), "Cancelled", 1);
                }
                return ModelResponse.ok(type, getModelId(), fn.apply(request), "", 50, 40, 12);
            }
        };
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
