# Security Policy

## Security Architecture & Guarantees

**Tri-Model AI Orchestrator** is designed with strict security boundaries suitable for professional IDE workflows and JetBrains Marketplace security review:

1. **Secure Credential Isolation**
   - API keys are stored strictly in **JetBrains PasswordSafe** (`SecretStorageService.java`) and are never serialized to `tri-model-orchestrator.xml` or written to disk in plain text.
   - Each provider's API key is sent **only** to that specific provider's configured endpoint (`x-api-key` for Claude, `Authorization: Bearer` for ChatGPT, `x-goog-api-key` for Gemini).
   - Zero telemetry, zero hidden network calls, and zero third-party SDK/analytics bundles.

2. **Safe File System Operations**
   - `CodeChangeExtractor.sanitizePath()` and `CodeApplyService.applyFileChanges()` normalize paths and reject any path containing `..` traversal segments, null bytes, or targets outside the active PyCharm project root.
   - Code edits are **never** auto-written to disk silently — the user explicitly reviews changes via the **Code Changes** tab / native **Diff Viewer** and confirms multi-file writes in `ApplyChangesDialog`.
   - All document writes run inside IntelliJ `WriteCommandAction` so changes are atomic and reversible via standard Undo (`Cmd/Ctrl+Z`).

3. **Safe Local CLI Execution**
   - `CliModelClient` invokes user-configured binaries directly via `ProcessBuilder` with an explicit argument list — **never** through `sh -c` or `cmd /c` shell interpolation, eliminating command injection from prompts or code snippets.
   - Prompts are piped over `stdin` by default, and subprocesses enforce timeouts and cooperative cancellation (`destroyForcibly()`).

4. **HTML Rendering Safety**
   - `MarkdownHtmlRenderer` HTML-escapes all model output before rendering in Swing `JEditorPane`.

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `1.0.x` | Yes |

## Reporting a Vulnerability

Please **do not** open public GitHub issues for security vulnerabilities.

Instead, report security issues privately by emailing **oabaiadze@gmail.com** with:
- A description of the vulnerability and potential impact
- Steps to reproduce or a proof of concept
- Affected plugin and PyCharm/IDE version

We aim to acknowledge reports within 72 hours and coordinate a fix and release promptly.
