# Changelog

All notable changes to **Tri-Model AI Orchestrator** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
