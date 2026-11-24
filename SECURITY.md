# Security Policy

## Security Architecture & Guarantees

**Tri-Model AI Orchestrator** is designed with strict security boundaries suitable for professional IDE workflows and JetBrains Marketplace security review:

1. **Secure Credential Isolation & Browser OAuth 2.0 (PKCE)**
   - API keys and optional OAuth client secrets are stored strictly in **JetBrains PasswordSafe** (`SecretStorageService.java`) and are never serialized to `tri-model-orchestrator.xml` or written to disk in plain text.
   - Browser authorization uses standards-compliant **OAuth 2.0 Authorization Code + PKCE (S256)** (`OAuthPkceService.java`) opening the user's default browser (`BrowserUtil.browse`):
     - Local callback server binds strictly to loopback (`127.0.0.1`), verifies CSRF `state` in constant time (`MessageDigest.isEqual`), enforces HTTPS for remote authorize/token endpoints, and shuts down immediately after the callback or timeout/cancellation.
     - Temporary sessions default to **RAM-only** (never persisted to disk and stripped of long-lived refresh tokens), cleared automatically on IDE exit or **Sign Out**.
     - The plugin **never** inspects browser cookies, browser SQLite databases, or unofficial consumer web session tokens.
   - Each provider's credential is sent **only** to that specific provider's configured endpoint (`x-api-key` or `Authorization: Bearer` for Claude, `Authorization: Bearer` for ChatGPT, `x-goog-api-key` or `Authorization: Bearer` for Gemini).
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
| `1.1.x` | Yes |
| `1.0.x` | Security fixes via upgrade to `1.1.x` |

## Reporting a Vulnerability

Please **do not** open public GitHub issues for security vulnerabilities.

Instead, report security issues privately by emailing **oabaiadze@gmail.com** with:
- A description of the vulnerability and potential impact
- Steps to reproduce or a proof of concept
- Affected plugin and PyCharm/IDE version

We aim to acknowledge reports within 72 hours and coordinate a fix and release promptly.
