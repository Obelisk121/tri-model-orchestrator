# Tri-Model AI Orchestrator

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![IDE Compatibility](https://img.shields.io/badge/PyCharm%20%2F%20IntelliJ%20Platform-2024.2%2B-green.svg)](#installation)

Orchestrate **Claude**, **ChatGPT**, and **Gemini** together inside **PyCharm** (and compatible JetBrains IDEs) to write, analyze, review, debate, and apply code under one unified tool window.

> **Privacy & Independence Notice:** Your prompts, explicitly attached workspace files, and optional IDE diagnostics are sent **directly** from your machine to your configured AI provider endpoints (Anthropic, OpenAI, Google, custom proxy URL, or local CLI). This project operates **no intermediary backend servers** and collects **zero telemetry**. See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).  
> *Independent open-source software licensed under Apache 2.0. Not affiliated with or endorsed by JetBrains, Anthropic, OpenAI, or Google.*

---

## Installation

**Requirements:** PyCharm `2024.2` or newer (tested on **PyCharm 2026.1.2**, both Community and Professional editions, or any IntelliJ Platform IDE running on JBR 21+).

### Option 1: Install from GitHub Releases (Recommended / Fastest)

1. Download the latest `tri-model-orchestrator-<version>.zip` from the [**GitHub Releases**](https://github.com/Obelisk121/tri-model-orchestrator/releases/latest) page.
2. Open **PyCharm**.
3. Go to **Settings / Preferences** (`Cmd + ,` on macOS, `Ctrl + Alt + S` on Windows/Linux) → **Plugins**.
4. Click the **gear icon (⚙)** at the top right of the Plugins pane and select **Install Plugin from Disk...**.
5. Select the downloaded `tri-model-orchestrator-1.1.0.zip` file and click **OK**.
6. Restart PyCharm when prompted.
7. Open the **Tri-Model AI** tool window on the right sidebar (or right-click inside any editor → **Tri-Model AI**).

### Option 2: Install from JetBrains Marketplace *(once listed)*

1. Open **PyCharm → Settings / Preferences → Plugins → Marketplace**.
2. Search for **Tri-Model AI Orchestrator**.
3. Click **Install** and restart PyCharm.

### Option 3: Build & Install from Source

```bash
git clone https://github.com/Obelisk121/tri-model-orchestrator.git
cd tri-model-orchestrator
```

**Fast local build (macOS with PyCharm installed — zero network required):**
```bash
./scripts/build-plugin.sh
```
This compiles against your installed `/Applications/PyCharm.app` (set `PYCHARM_APP=/path/to/PyCharm.app` if installed elsewhere), runs the full 14-test suite, runs JetBrains Marketplace compliance checks, and generates `build/distributions/tri-model-orchestrator-1.1.0.zip`.

**Standard Gradle build (cross-platform, IntelliJ Platform Gradle Plugin 2.x):**
```bash
gradle buildPlugin verifyPlugin
```
Then install `build/distributions/tri-model-orchestrator-1.1.0.zip` via **Settings → Plugins → ⚙ → Install Plugin from Disk...**.

### Option 4: Private / Enterprise Custom Plugin Repository

Organizations hosting an internal JetBrains plugin repository can serve the release ZIP over HTTPS with an `updatePlugins.xml` descriptor:

```xml
<plugins>
  <plugin id="io.github.obelisk121.trimodel"
          url="https://internal.example.com/plugins/tri-model-orchestrator-1.1.0.zip"
          version="1.1.0">
    <idea-version since-build="242"/>
    <name>Tri-Model AI Orchestrator</name>
  </plugin>
</plugins>
```
Add the `updatePlugins.xml` URL in **PyCharm → Settings → Plugins → ⚙ → Manage Plugin Repositories...**.

---

## Configuration (Claude, ChatGPT, and Gemini)

Open **Settings → Tools → Tri-Model AI Orchestrator** (or click the **gear icon** inside the **Tri-Model AI** tool window).

### Mode A: Official REST APIs (Default)
API keys are stored securely in **JetBrains PasswordSafe** (macOS Keychain / Windows Credential Manager / Linux Secret Service) — never written to plain XML — and fall back to environment variables if left blank:

| Model | Default Model ID (editable) | API Protocol | Environment Fallback |
| :--- | :--- | :--- | :--- |
| **Claude** (Anthropic) | `claude-sonnet-5-5` *(presets: `claude-opus-5-5`, `claude-fable-5-2`, `claude-haiku-5-5`)* | Messages API (`/v1/messages`, `anthropic-version: 2026-10-21`) | `ANTHROPIC_API_KEY` |
| **ChatGPT** (OpenAI) | `gpt-6.2-sol` *(presets: `gpt-6.2-astra`, `gpt-6.1-terra`, `gpt-6-luna`)* | Responses API (`/v1/responses`) or Chat Completions (`/v1/chat/completions`) | `OPENAI_API_KEY` |
| **Gemini** (Google) | `gemini-4-neon` *(presets: `gemini-4-carbon`, `gemini-4-argon`, `gemini-4-hydrogen`, `gemini-3.8-flash`)* | Interactions API (`/v1beta/interactions`) or `generateContent` | `GEMINI_API_KEY` / `GOOGLE_API_KEY` |

- Custom Base URLs (proxies, corporate gateways, OpenAI-compatible gateways) are supported per provider.
- Click **Test Connection** on each tab (or **Test All Enabled Models**) to verify connectivity and measure latency.

### Mode B: Browser OAuth 2.0 (Temporary Session — Default Browser Pop-Up)
Switch any provider's **Connection mode** to **Browser OAuth (Temporary Session)** to authorize via your default web browser using RFC 8252 (`127.0.0.1` loopback) + RFC 7636 PKCE (`S256`):
1. Enter your registered **OAuth Client ID** (and optional Client Secret for Google Desktop OAuth apps; stored in `PasswordSafe`).
2. Leave **Temporary session only (RAM only)** checked so the short-lived access token stays strictly in memory and clears automatically when PyCharm closes (or uncheck to store securely in `PasswordSafe` with automatic refresh).
3. Click **Authorize in Browser...** (in Settings or via the **Auth** button in the **Tri-Model AI** tool window header) to open your default browser, approve authorization, and immediately return to PyCharm.
4. Click **Sign Out** (or **Auth → Sign Out All Temporary Browser Sessions**) at any time to terminate the session immediately.

> **Provider OAuth Note:** Google Gemini supports Google Cloud Desktop OAuth 2.0 directly (`generative-language` scope); ChatGPT uses registered OpenAI OAuth (`auth.openai.com`); Anthropic restricts third-party IDE OAuth to **Claude Platform OAuth** (billable API organizations) or enterprise OIDC gateways. For consumer subscriptions via official vendor CLIs, use **Mode C** below.

### Mode C: Local CLI Tools (with Official Browser Login Helper)
Switch any provider's **Connection mode** to **Local CLI Tool** to invoke locally authenticated CLIs (`claude`, `codex`, `gemini`) via safe non-shell `ProcessBuilder` execution (prompts are piped on `stdin` by default, or interpolated via `{prompt}`), and click **Browser Login via CLI** in Settings to launch the vendor's official CLI login flow (`claude login`, `codex login`, `gemini`).

---

## Five Multi-Model Orchestration Workflows

Assign **Claude**, **ChatGPT**, or **Gemini** to any role (`Architect`, `Coder`, `Reviewer`, `Chair`) directly in the tool window header:

1. **Role Pipeline (`Architect → Coder → Reviewer → Finalizer`)**
   - Architect inspects the request + attached code and produces an engineering blueprint.
   - Coder implements complete files following the blueprint.
   - Reviewer audits bugs, edge cases, typing, security, and Pythonic idioms.
   - Finalizer integrates review feedback into clean, production-ready code.
2. **Iterative Code & Dual-Review Loop (`Coder ⇄ 2 Reviewers`)**
   - Coder generates the implementation.
   - The other two models review **in parallel** (Correctness/Edge Cases/Tests + Security/Architecture/Performance) and emit `VERDICT: APPROVE` or `VERDICT: REVISE`.
   - If either reviewer flags critical/major defects, the Coder automatically refines the code and re-submits for review (up to `maxReviewRounds`).
3. **Parallel Council & Consensus (`All 3 → Cross-Critique → Chair`)**
   - All three models independently propose solutions in parallel.
   - Round 2 (optional): each model cross-critiques the other two proposals.
   - Round 3: the Chair synthesizes the consensus solution and final code.
4. **Tri-Model Deep Code Audit**
   - Parallel 3-lens code inspection (Logic/Bugs/Types, Security/Reliability/Concurrency, Architecture/Performance/Maintainability) merged into a severity-ranked report with proposed fixes.
5. **Parallel Side-by-Side Compare**
   - Runs all enabled models simultaneously on the exact same prompt with latency/token comparison and dedicated per-model tabs.

---

## PyCharm IDE Integration

- **Workspace Context:** Attach the active editor file, selected lines only, live PyCharm inspection warnings/errors (`IDE Problems`), and arbitrary multi-file project attachments (`+ Add Files`).
- **Native Diff & Atomic Apply:** Inspect extracted files in the **Code Changes** tab, open PyCharm's native **Diff Viewer**, replace the active editor/selection, or selectively apply multi-file edits atomically with full `Cmd/Ctrl+Z` Undo support.
- **Editor Context Menu & Shortcuts** (right-click in any editor → **Tri-Model AI**):
  - **Orchestrate & Write Code (Pipeline)...** (`Ctrl+Alt+Shift+O`)
  - **Deep Analyze with Claude + ChatGPT + Gemini** (`Ctrl+Alt+Shift+A`)
  - **Ask Parallel Council (All 3 Models)...**
  - **Fix Bugs & IDE Problems (Iterative Loop)**

---

## JetBrains Marketplace & Regulatory Compliance

This repository is structured to comply with **JetBrains Marketplace Approval Guidelines**, **Plugin Verifier** rules, **Open-Source Repository** rules, and **GDPR / EEA** transparency requirements:

- **Compliant Plugin ID & Name:** `<id>io.github.obelisk121.trimodel</id>` and `<name>Tri-Model AI Orchestrator</name>` contain no forbidden JetBrains brand/product terms (`jetbrains`, `intellij`, `pycharm`, `plugin`).
- **Verified Vendor & Metadata:** `<vendor email="..." url="...">` links directly to this public repository, with an English overview, explicit AI data-flow disclosure, and complete `<change-notes>`.
- **40×40 Light & Dark SVG Icons:** Original `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg` (plus 16×16 light/dark tool window icons).
- **Mandatory Legal & Governance Files:**
  - [LICENSE](LICENSE) — Apache License 2.0
  - [NOTICE](NOTICE) — Third-party trademark & non-affiliation notice
  - [PRIVACY.md](PRIVACY.md) — Privacy Policy & GDPR/EEA Data Handling Notice (supply this URL in the JetBrains Marketplace **Privacy Policy** field)
  - [SECURITY.md](SECURITY.md) — Credential isolation (`PasswordSafe`), safe file/CLI boundaries, and vulnerability reporting
  - [CHANGELOG.md](CHANGELOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- **Marketplace Submission Reminder (Publisher Portal):** When submitting on [JetBrains Marketplace](https://plugins.jetbrains.com/):
  1. Accept the JetBrains Marketplace Developer Agreement and declare **Trader / Non-Trader** status (required under EEA consumer protection law).
  2. Link this public GitHub URL in the mandatory **Source Code** field and **Bug Tracker** (`.../issues`) field.
  3. Link `https://github.com/Obelisk121/tri-model-orchestrator/blob/main/PRIVACY.md` in the **Privacy Policy** field.
  4. Link `https://github.com/Obelisk121/tri-model-orchestrator/blob/main/LICENSE` in the **License** field.
