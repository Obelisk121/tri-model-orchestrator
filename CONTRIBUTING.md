# Contributing to Tri-Model AI Orchestrator

Contributions, bug reports, and feature ideas are welcome!

## Prerequisites
- **Java 21+** (JDK 21 or PyCharm's bundled JetBrains Runtime)
- **PyCharm 2024.2+** (or Gradle 9+ for IntelliJ Platform Gradle Plugin 2.x)

## Local Development & Verification

1. Clone the repository:
   ```bash
   git clone https://github.com/Obelisk121/tri-model-orchestrator.git
   cd tri-model-orchestrator
   ```
2. Build, run all automated tests, and verify Marketplace compliance:
   ```bash
   ./scripts/build-plugin.sh
   ```
   Or using Gradle:
   ```bash
   gradle buildPlugin verifyPlugin
   ```
3. Install `build/distributions/tri-model-orchestrator-<version>.zip` via **PyCharm → Settings → Plugins → ⚙ → Install Plugin from Disk...** to test manually inside PyCharm.

## Pull Request Guidelines
- Follow existing Java 21 style and keep external runtime dependencies at zero unless strongly justified.
- Never log, persist in XML, or hardcode API keys or tokens.
- Keep JetBrains Marketplace rules intact (no forbidden product names in `<id>` or `<name>`, maintain `PRIVACY.md` accuracy for any network behavior changes).
- Add or update tests in `TriModelPluginTestSuite.java` for all behavioral changes and verify `./scripts/build-plugin.sh` passes cleanly with zero warnings.
- Contributions are licensed under the project's [Apache License 2.0](LICENSE).
