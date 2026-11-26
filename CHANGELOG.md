# Changelog

All notable changes to **Tri-Model AI Orchestrator** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - 2026-11-26

### Added
- **Independent Verification & Anti-Sycophancy Agent Skills (`AgentSkill` & `AgentSkillConfig`):**
  - **Anti-Sycophancy (No Blind Agreement):** Forbids rubber-stamping or deferential filler; forces every agent to treat peer plans, code, and critiques as unverified hypotheses and rebut false positives with proof.
  - **Topic-by-Topic Peer Analysis:** Requires a structured Markdown verification table auditing every distinct topic raised by prior agents (`[VERIFIED]`, `[DISPUTED]`, `[MISSING]`, `[UNPROVEN]`), rendered with color-coded badges in the Tool Window.
  - **High-Precision Detail & Evidence:** Bans vague generalizations and enforces exact file paths, symbol names, types, boundary values, exception classes, and complexity analysis.
  - **Blind Peer Labels (De-Biased Handoff):** Anonymizes peer model brand names (`Proposal A`, `Prior Agent (Coder)`, `Independent Auditor B`) inside handoff prompts so agents judge purely on technical merit without brand bias.
  - **Devil's Advocate Stress-Testing (`Strict Adversarial` profile):** Instructs agents to construct concrete breaking inputs and step-by-step adversarial failure traces.
- **Engine-Level Contradiction Guard:** Automatically overrides a rubber-stamp `VERDICT: APPROVE` to `REVISE` in Iterative Loop mode if the reviewer still flagged `[CRITICAL]`, `[MAJOR]`, or `[DISPUTED]` defects.
- **Tool Window & Settings Controls:** Added an **Agent Skills** preset selector (`Independent & Precise`, `Strict Adversarial`, `Balanced`, `Custom`) directly in the Tool Window header and granular per-skill checkboxes in **Settings → Tools → Tri-Model AI Orchestrator → Orchestration & Defaults**.

## [1.2.1] - 2026-11-24

### Fixed
- **ChatGPT (`codex`) CLI outside Git directories:** Updated default args to `exec --skip-git-repo-check --ephemeral --color never -s read-only -` and added automatic migration/runtime injection so existing configurations work immediately after authorization without `Not inside a trusted directory` errors.
- **Google Gemini CLI Browser Sign-In:** Automatically switches Gemini CLI's `~/.gemini/settings.json` `security.auth.selectedType` to `oauth-personal` when the user clicks **Sign In in Browser**, preventing exit code 41 (`When using Gemini API, you must specify the GEMINI_API_KEY environment variable`).
- **Quick Sign-In Dialog Polish:** Widened dialog (`740x440`) and shortened button/dropdown labels so nothing clips in compact or dark themes.

## [1.2.0] - 2026-11-24

### Improved
- **One-Screen Quick Sign-In & Setup (`QuickSetupDialog`):** Click **Sign In** in the Tool Window header (or click any model pill) to connect Claude, ChatGPT, and Gemini on a single clean dialog using either 1-click **Browser Sign-In** (no API key or OAuth Client ID required) or **API Key** (with 1-click `Get Key ↗` links).
- **Decluttered Settings UI:** Switched each provider tab in Settings to a `CardLayout` that shows only the active sign-in method and tucks Base URL, API protocol, Custom OAuth endpoints, and CLI args inside collapsible **Advanced** sections.
- **macOS/Linux CLI Auto-Detection & Exact Login Commands:** `CliModelClient.resolveExecutable(...)` resolves Homebrew (`/opt/homebrew/bin`), `/usr/local/bin`, `~/.local/bin`, `~/.npm-global/bin`, and `nvm` paths directly so PyCharm GUI always finds `claude`, `codex`, and `gemini`, using exact official login/logout commands (`claude auth login`, `codex login`, `gemini`) and auto-opening printed auth URLs in the default browser.

## [1.1.0] - 2026-11-24

### Added
- **Browser OAuth 2.0 (Temporary Session)** connection mode per provider alongside API Key and Local CLI modes:
  - Uses standards-compliant RFC 8252 ephemeral `127.0.0.1` loopback callback and RFC 7636 PKCE (`S256`) with constant-time CSRF `state` verification in the user's default web browser.
  - Defaults to **Temporary RAM-Only Sessions** (short-lived access token kept strictly in memory, cleared automatically on IDE shutdown or **Sign Out**), with optional PasswordSafe persistence and automatic refresh when opted in.
  - Supports `Authorization: Bearer` OAuth authentication on Claude, ChatGPT, and Gemini clients.
  - Added quick **Auth** menu directly in the **Tri-Model AI** tool window header for one-click browser sign-in and sign-out.
- **Browser Login via CLI** helper button in Local CLI mode to trigger official vendor CLI browser logins (`claude login`, `codex login`, `gemini`).
- Added automated unit tests covering RFC 7636 PKCE S256 derivation, HTTPS enforcement, CSRF state rejection, code exchange, temporary RAM-only session storage, token refresh, and Bearer headers.

## [1.0.0] - 2026-11-24

### Added
- Unified PyCharm / IntelliJ Platform tool window (**Tri-Model AI**) orchestrating **Claude**, **ChatGPT**, and **Gemini** together.
- Five configurable multi-model orchestration modes:
  - **Role Pipeline** (`Architect -> Coder -> Reviewer -> Finalizer`) with customizable role assignment.
  - **Iterative Code & Dual-Review Loop** (`Coder <-> 2 Reviewers`) with automatic refinement rounds on `VERDICT: REVISE`.
  - **Parallel Council & Consensus** (`All 3 -> Cross-Critique -> Chair`).
  - **Tri-Model Deep Code Audit** (3 parallel engineering lenses + severity-ranked report).
  - **Parallel Side-by-Side Compare** with latency and token metrics.
- Official REST API clients:
  - Anthropic Messages API (`/v1/messages`, `anthropic-version: 2026-10-21`).
  - OpenAI Responses API (`/v1/responses`) and Chat Completions API (`/v1/chat/completions`).
  - Google Gemini Interactions API (`/v1beta/interactions`, `steps[]` & legacy `outputs[]`) and `generateContent`.
- Optional **Local CLI** execution backend (`claude`, `codex`, `gemini`, or custom executable) without shell interpolation.
- Secure JetBrains `PasswordSafe` API key storage with environment variable fallback (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`) and live **Test Connection** checks.
- Native IDE integration: active editor/selection capture, live IDE diagnostics (`IDE Problems`), multi-file attachment picker, native Diff Viewer preview, atomic Undo-capable single- and multi-file apply, and Editor/Tools context-menu shortcuts.
- JetBrains Marketplace compliance assets: light/dark 40x40 SVG plugin icons, Apache 2.0 `LICENSE`, `NOTICE`, `PRIVACY.md`, `SECURITY.md`, and automated compliance verification checks.
