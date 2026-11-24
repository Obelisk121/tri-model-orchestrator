# Privacy Policy & Data Handling Notice

**Effective Date:** November 24, 2026  
**Plugin:** Tri-Model AI Orchestrator (`io.github.obelisk121.trimodel`)  
**Maintainer / Data Controller Contact:** Oto Abaiadze (`oabaiadze@gmail.com`)  
**Source Repository:** https://github.com/Obelisk121/tri-model-orchestrator

This Privacy Policy describes how **Tri-Model AI Orchestrator** handles your data, code, and credentials when installed in PyCharm or other compatible JetBrains IDEs. This notice is provided in compliance with the JetBrains Marketplace Approval Guidelines and applicable data protection regulations including the EU General Data Protection Regulation (GDPR).

---

## 1. Zero Developer Collection & Zero Telemetry

Tri-Model AI Orchestrator operates **strictly locally** inside your JetBrains IDE:

- We operate **no intermediary servers, proxies, or cloud backends**.
- We collect **no telemetry, analytics, usage statistics, crash reports, IP addresses, or account identifiers**.
- We never receive, view, store, or transmit your API keys, prompts, or source code to the plugin author or any unlisted third party.

---

## 2. What Data Is Transmitted and When

No workspace data leaves your machine until you **explicitly initiate an action** (such as clicking **Run Orchestration**, **Test Connection**, or invoking an editor context-menu action).

When you run an orchestration, the plugin constructs a request containing only the data you chose to include:

1. **Your Prompt Text:** The instructions you enter in the tool window (and follow-up turns within the active in-memory session, cleared when you click **New** or close the project).
2. **Explicitly Attached Code Context (User-Controlled):**
   - Active editor file or selected code lines (controlled by the **Active File** and **Selection Only** checkboxes).
   - Live IDE inspection warnings/errors on attached files (controlled by the **IDE Problems** checkbox).
   - Additional workspace files you explicitly pick via **+ Add Files**.
3. **Orchestration Handoffs:** In multi-stage modes (Pipeline, Iterative Loop, Parallel Council, Deep Audit), intermediate model outputs are passed to downstream configured models as part of the orchestration workflow.

---

## 3. Third-Party AI Providers (Direct Transmission)

Requests are sent **directly over HTTPS** from your local machine to the endpoints you configure (or piped locally to your configured CLI executables):

| Provider | Default Endpoint | Provider Privacy Policy & Terms |
| :--- | :--- | :--- |
| **Anthropic (Claude)** | `https://api.anthropic.com/v1/messages` | https://www.anthropic.com/legal/privacy |
| **OpenAI (ChatGPT)** | `https://api.openai.com/v1/responses` | https://openai.com/policies/privacy-policy |
| **Google (Gemini)** | `https://generativelanguage.googleapis.com/v1beta/interactions` | https://ai.google.dev/gemini-api/terms |
| **Custom Base URL / Local CLI** | User-specified endpoint or local binary (`claude`, `codex`, `gemini`) | Governed by your chosen provider or self-hosted gateway |

When calling OpenAI Responses API or Google Gemini Interactions API, the plugin explicitly sets `"store": false` in request payloads by default to minimize server-side state retention where supported by the provider API.

You are responsible for ensuring that sending proprietary or personal data to your configured AI providers complies with your organization's security and data governance policies.

---

## 4. Credential & Local Storage

- **API Keys:** Stored exclusively using **JetBrains PasswordSafe** (backed by macOS Keychain, Windows Credential Manager, or Linux Secret Service). API keys are **never** written to `tri-model-orchestrator.xml`, project files, or IDE logs. Optional environment variable fallbacks (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` / `GOOGLE_API_KEY`) are read in memory only.
- **Non-Secret Settings:** Provider enablement flags, model IDs, base URLs, role assignments, and orchestration preferences are stored locally in JetBrains IDE configuration (`tri-model-orchestrator.xml`).
- **Session History:** Held solely in memory during your IDE session and discarded when you click **New** or close the project.

---

## 5. Your Rights (GDPR / EEA & Global Users)

Because the plugin maintainer does not collect, store, or process your personal data on any server:

- **Access, Rectification, and Erasure:** You retain full local control. You can inspect or delete stored API keys at any time in **Settings → Tools → Tri-Model AI Orchestrator** (or your OS Keychain), clear session history with **New**, or uninstall the plugin to remove all local state.
- **Third-Party Provider Rights:** To exercise data rights regarding prompts processed by Anthropic, OpenAI, or Google, please consult their respective privacy portals linked in Section 3.

---

## 6. Changes & Contact

Material changes to this Privacy Policy will be documented in the repository history and `CHANGELOG.md`. For privacy inquiries, contact **Oto Abaiadze** at `oabaiadze@gmail.com` or open an issue at https://github.com/Obelisk121/tri-model-orchestrator/issues.
