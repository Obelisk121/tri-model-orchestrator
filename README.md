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
5. Select the downloaded `tri-model-orchestrator-1.2.0.zip` file and click **OK**.
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
This compiles against your installed `/Applications/PyCharm.app` (set `PYCHARM_APP=/path/to/PyCharm.app` if installed elsewhere), runs the full 14-test suite, runs JetBrains Marketplace compliance checks, and generates `build/distributions/tri-model-orchestrator-1.2.0.zip`.

**Standard Gradle build (cross-platform, IntelliJ Platform Gradle Plugin 2.x):**
```bash
gradle buildPlugin verifyPlugin
```
Then install `build/distributions/tri-model-orchestrator-1.2.0.zip` via **Settings → Plugins → ⚙ → Install Plugin from Disk...**.

### Option 4: Private / Enterprise Custom Plugin Repository

Organizations hosting an internal JetBrains plugin repository can serve the release ZIP over HTTPS with an `updatePlugins.xml` descriptor:

```xml
<plugins>
  <plugin id="io.github.obelisk121.trimodel"
          url="https://internal.example.com/plugins/tri-model-orchestrator-1.2.0.zip"
          version="1.2.0">
    <idea-version since-build="242"/>
    <name>Tri-Model AI Orchestrator</name>
  </plugin>
</plugins>
```
Add the `updatePlugins.xml` URL in **PyCharm → Settings → Plugins → ⚙ → Manage Plugin Repositories...**.

---

## Quick Setup (2 Steps)

Click **`Sign In`** in the top bar of the **Tri-Model AI** tool window (or click any model status pill) to open the **one-screen setup dialog** for Claude, ChatGPT, and Gemini:

### Option A: Browser Sign-In (Simplest — No API Key or Client ID Needed)
1. Pick **`Browser Sign-In (No API Key)`** for any model (or click **`Use Browser Sign-In for All Installed CLIs`**).
2. Click **`Sign In in Browser`** — opens your default web browser through the vendor's official CLI (`claude auth login`, `codex login`, or `gemini`) so you can sign in with your existing subscription. Click **`Test`** → **`Save & Done`**.

### Option B: API Key (Paste & Go)
1. Pick **`API Key (Paste & Go)`** and click **`Get Key ↗`** to open the provider's API key page directly in your browser.
2. Paste the key (stored securely in **JetBrains PasswordSafe** / OS Keychain, with `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and `GEMINI_API_KEY` env-var fallbacks) → click **`Test`** → **`Save & Done`**.

| Model | Default Model ID (editable) | API Protocol | Environment Fallback |
| :--- | :--- | :--- | :--- |
| **Claude** (Anthropic) | `claude-sonnet-5-5` *(presets: `claude-opus-5-5`, `claude-fable-5-2`, `claude-haiku-5-5`)* | Messages API (`/v1/messages`, `anthropic-version: 2026-10-21`) | `ANTHROPIC_API_KEY` |
| **ChatGPT** (OpenAI) | `gpt-6.2-sol` *(presets: `gpt-6.2-astra`, `gpt-6.1-terra`, `gpt-6-luna`)* | Responses API (`/v1/responses`) or Chat Completions (`/v1/chat/completions`) | `OPENAI_API_KEY` |
| **Gemini** (Google) | `gemini-4-neon` *(presets: `gemini-4-carbon`, `gemini-4-argon`, `gemini-4-hydrogen`, `gemini-3.8-flash`)* | Interactions API (`/v1beta/interactions`) or `generateContent` | `GEMINI_API_KEY` / `GOOGLE_API_KEY` |

### Option C (Advanced): Custom Base URLs & Custom OAuth 2.0 (PKCE)
Open **Settings → Tools → Tri-Model AI Orchestrator** to expand **Advanced** options for custom proxy URLs, OpenAI/Gemini API styles, or **Custom OAuth 2.0 (Own Client ID / Enterprise SSO)** with RFC 8252 `127.0.0.1` loopback PKCE and temporary RAM-only sessions.

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
