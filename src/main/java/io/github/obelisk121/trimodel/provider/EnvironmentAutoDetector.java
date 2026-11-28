package io.github.obelisk121.trimodel.provider;

import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelSettings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Cross-platform (Linux, Windows, macOS) environment auto-detector for Tri-Model AI Orchestrator.
 * Automatically discovers installed and signed-in official CLIs (claude, codex, gemini, agy),
 * environment API keys and custom base URLs, optimal connection modes, OS-compatible CLI arguments,
 * and live available models from provider APIs/CLIs.
 */
public final class EnvironmentAutoDetector {

    public static final String AGY_DEFAULT_ARGS = "--output-format text --disable-slash-commands -p \"{prompt}\"";

    public record ProviderDetection(
            ModelProviderType provider,
            ProviderConfig suggestedConfig,
            CliModelClient.CliAuthStatus cliStatus,
            ModelDiscoveryService.DiscoveryResult discovery,
            boolean ready,
            String summary
    ) {
    }

    public record EnvironmentReport(
            String osLabel,
            Map<ModelProviderType, ProviderDetection> providers,
            String summary
    ) {
    }

    public interface Probe {
        String osName();

        String osArch();

        Map<String, String> env();

        String storedApiKey(ModelProviderType type);

        boolean hasOAuthSession(ModelProviderType type);

        CliModelClient.CliAuthStatus checkCli(ModelProviderType type, String executable);

        ModelDiscoveryService.DiscoveryResult discoverModels(
                ModelProviderType type,
                ProviderConfig config,
                String apiKeyOverride,
                BooleanSupplier isCancelled
        );
    }

    public static Probe systemProbe() {
        ModelDiscoveryService discovery = new ModelDiscoveryService();
        return new Probe() {
            @Override
            public String osName() {
                return System.getProperty("os.name", "");
            }

            @Override
            public String osArch() {
                return System.getProperty("os.arch", "");
            }

            @Override
            public Map<String, String> env() {
                return System.getenv();
            }

            @Override
            public String storedApiKey(ModelProviderType type) {
                return SecretStorageService.getStoredApiKey(type);
            }

            @Override
            public boolean hasOAuthSession(ModelProviderType type) {
                return OAuthSessionStore.hasUsableSession(type);
            }

            @Override
            public CliModelClient.CliAuthStatus checkCli(ModelProviderType type, String executable) {
                return CliModelClient.checkCliAuthStatus(type, executable);
            }

            @Override
            public ModelDiscoveryService.DiscoveryResult discoverModels(
                    ModelProviderType type,
                    ProviderConfig config,
                    String apiKeyOverride,
                    BooleanSupplier isCancelled
            ) {
                return discovery.fetchAvailableModels(type, config, apiKeyOverride, true, isCancelled);
            }
        };
    }

    private final Probe probe;

    public EnvironmentAutoDetector() {
        this(systemProbe());
    }

    public EnvironmentAutoDetector(Probe probe) {
        this.probe = probe == null ? systemProbe() : probe;
    }

    public static String formatOsLabel(String osName, String osArch) {
        String arch = (osArch == null || osArch.isBlank()) ? "" : " (" + osArch.trim() + ")";
        if (CliModelClient.isMacOs(osName)) return "macOS" + arch;
        if (CliModelClient.isWindowsOs(osName)) return "Windows" + arch;
        if (CliModelClient.isLinuxOs(osName)) return "Linux" + arch;
        return (osName == null || osName.isBlank() ? "Unknown OS" : osName.trim()) + arch;
    }

    public String currentOsLabel() {
        return formatOsLabel(probe.osName(), probe.osArch());
    }

    /**
     * Detects all three providers in parallel so auto-detection completes quickly even when querying CLIs/APIs.
     */
    public EnvironmentReport detectAll(
            Map<ModelProviderType, ProviderConfig> currentConfigs,
            Map<ModelProviderType, String> apiKeyOverrides,
            boolean discoverModels,
            BooleanSupplier isCancelled
    ) {
        ExecutorService pool = Executors.newFixedThreadPool(ModelProviderType.values().length, r -> {
            Thread t = new Thread(r, "TriModel-AutoDetect");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<ModelProviderType, CompletableFuture<ProviderDetection>> futures = new EnumMap<>(ModelProviderType.class);
            for (ModelProviderType type : ModelProviderType.values()) {
                ProviderConfig cur = currentConfigs != null ? currentConfigs.get(type) : null;
                String overrideKey = apiKeyOverrides != null ? apiKeyOverrides.get(type) : null;
                futures.put(type, CompletableFuture.supplyAsync(
                        () -> detectProvider(type, cur, overrideKey, discoverModels, isCancelled), pool));
            }
            Map<ModelProviderType, ProviderDetection> results = new EnumMap<>(ModelProviderType.class);
            int readyCount = 0;
            List<String> readyNames = new ArrayList<>();
            for (ModelProviderType type : ModelProviderType.values()) {
                ProviderDetection det = futures.get(type).join();
                results.put(type, det);
                if (det.ready()) {
                    readyCount++;
                    readyNames.add(type.getShortName());
                }
            }
            String osLabel = currentOsLabel();
            String summary = readyCount > 0
                    ? "Auto-detected on " + osLabel + ": " + String.join(", ", readyNames) + " ready (" + readyCount + "/3)"
                    : "Scanned " + osLabel + ": sign in via Browser or paste an API key to connect models";
            return new EnvironmentReport(osLabel, results, summary);
        } finally {
            pool.shutdownNow();
        }
    }

    public ProviderDetection detectProvider(
            ModelProviderType type,
            ProviderConfig currentConfig,
            String apiKeyOverride,
            boolean discoverModels,
            BooleanSupplier isCancelled
    ) {
        BooleanSupplier cancel = isCancelled == null ? () -> false : isCancelled;
        ProviderConfig suggested = (currentConfig == null ? ProviderConfig.defaultsFor(type) : currentConfig.copy());
        Map<String, String> env = probe.env();

        // 1. Detect environment Base URL override if user hasn't set a custom non-default URL
        String envBaseUrl = detectEnvBaseUrl(type, env);
        if (!envBaseUrl.isBlank() && isDefaultOrBlankBaseUrl(type, suggested.baseUrl)) {
            suggested.baseUrl = envBaseUrl;
        }

        // 2. Inspect credentials (OAuth session, stored PasswordSafe key / override, env API key)
        boolean hasOAuth = probe.hasOAuthSession(type);
        String storedKey = (apiKeyOverride != null && !apiKeyOverride.isBlank())
                ? apiKeyOverride.trim()
                : safeTrim(probe.storedApiKey(type));
        boolean hasStoredKey = !storedKey.isBlank();
        String envKey = detectEnvApiKey(type, env);
        boolean hasEnvKey = !envKey.isBlank();

        // 3. Inspect candidate CLIs for this provider (including gemini <-> agy fallback for Gemini)
        CliCandidate bestCli = pickBestCli(type, suggested.cliExecutable, discoverModels, cancel);
        if (bestCli != null) {
            suggested.cliExecutable = bestCli.executable;
            suggested.cliArgs = defaultArgsForExecutable(type, bestCli.executable, suggested.cliArgs);
        }

        // 4. Choose the highest-readiness connection mode
        boolean ready;
        String modeReason;
        if (hasOAuth) {
            suggested.connectionMode = ConnectionMode.OAUTH_BROWSER;
            ready = true;
            modeReason = "active OAuth session";
        } else if (hasStoredKey) {
            suggested.connectionMode = ConnectionMode.REST_API;
            ready = true;
            modeReason = "saved API key";
        } else if (bestCli != null && bestCli.effectivelySignedIn) {
            suggested.connectionMode = ConnectionMode.LOCAL_CLI;
            ready = true;
            modeReason = bestCli.status.detail();
        } else if (hasEnvKey) {
            suggested.connectionMode = ConnectionMode.REST_API;
            ready = true;
            modeReason = type.getEnvVarName() + " env var";
        } else if (bestCli != null && bestCli.status.installed()) {
            suggested.connectionMode = ConnectionMode.LOCAL_CLI;
            ready = false;
            modeReason = bestCli.executable + " CLI installed (sign in required)";
        } else {
            suggested.connectionMode = ConnectionMode.REST_API;
            ready = false;
            modeReason = "not configured yet";
        }

        if (ready) {
            suggested.enabled = true;
        }

        // 5. Discover live available models and ensure selected model is available
        ModelDiscoveryService.DiscoveryResult discovery = ModelDiscoveryService.DiscoveryResult.error("");
        if (discoverModels && !cancel.getAsBoolean()) {
            if (suggested.connectionMode == ConnectionMode.LOCAL_CLI
                    && bestCli != null && bestCli.preFetchedDiscovery != null
                    && bestCli.preFetchedDiscovery.success()) {
                discovery = bestCli.preFetchedDiscovery;
            } else if (ready || (suggested.connectionMode == ConnectionMode.LOCAL_CLI && bestCli != null && bestCli.status.installed())) {
                String keyToUse = suggested.connectionMode == ConnectionMode.REST_API
                        ? (hasStoredKey ? storedKey : envKey)
                        : "";
                discovery = probe.discoverModels(type, suggested, keyToUse, cancel);
            }
            if (discovery.success() && !discovery.models().isEmpty()) {
                if (suggested.modelId == null || suggested.modelId.isBlank()
                        || !discovery.containsModelId(suggested.modelId)) {
                    suggested.modelId = discovery.models().get(0).id();
                }
            }
        }

        String modelSuffix = (suggested.modelId != null && !suggested.modelId.isBlank())
                ? " [" + suggested.modelId + "]" : "";
        String summary = type.getShortName() + ": " + modeReason + modelSuffix;
        CliModelClient.CliAuthStatus cliStatus = bestCli != null
                ? bestCli.status
                : new CliModelClient.CliAuthStatus(false, "", CliModelClient.AuthState.NOT_INSTALLED,
                type.getDefaultCliExecutable() + " CLI not installed");

        return new ProviderDetection(type, suggested, cliStatus, discovery, ready, summary);
    }

    /**
     * Runs conservative first-launch auto-detection once on fresh installations (never overwrites
     * providers that already have stored API keys, active OAuth sessions, or explicit models).
     */
    public EnvironmentReport applyFirstRunAutoDetect(TriModelSettings settings, BooleanSupplier isCancelled) {
        Map<ModelProviderType, ProviderConfig> current = new EnumMap<>(ModelProviderType.class);
        for (ModelProviderType type : ModelProviderType.values()) {
            current.put(type, settings.getProviderConfig(type).copy());
        }
        EnvironmentReport report = detectAll(current, Map.of(), true, isCancelled);
        for (ModelProviderType type : ModelProviderType.values()) {
            ProviderConfig existing = settings.getProviderConfig(type);
            ProviderDetection det = report.providers().get(type);
            if (det == null) continue;
            // Only overwrite if user hasn't already explicitly configured a key/OAuth/model
            boolean alreadyConfigured = !safeTrim(probe.storedApiKey(type)).isBlank()
                    || probe.hasOAuthSession(type)
                    || !safeTrim(existing.modelId).isBlank();
            if (!alreadyConfigured) {
                settings.setProviderConfig(type, det.suggestedConfig());
            }
        }
        settings.setAutoDetectedOnce(true);
        settings.notifySettingsChanged();
        return report;
    }

    private record CliCandidate(
            String executable,
            CliModelClient.CliAuthStatus status,
            boolean effectivelySignedIn,
            ModelDiscoveryService.DiscoveryResult preFetchedDiscovery
    ) {
    }

    private CliCandidate pickBestCli(
            ModelProviderType type,
            String existingExecutable,
            boolean probeAgyLiveModels,
            BooleanSupplier isCancelled
    ) {
        Set<String> candidates = new LinkedHashSet<>();
        if (type == ModelProviderType.GEMINI) {
            // Google deprecated individual OAuth on legacy gemini-cli in favor of Antigravity CLI (agy).
            // Always prioritize agy first for Google Gemini, falling back to legacy gemini CLI if agy is absent.
            candidates.add("agy");
            if (existingExecutable != null && !existingExecutable.isBlank()
                    && !"gemini".equals(CliModelClient.canonicalCliName(existingExecutable))) {
                candidates.add(existingExecutable.trim());
            }
            candidates.add("gemini");
        } else {
            if (existingExecutable != null && !existingExecutable.isBlank()) {
                candidates.add(existingExecutable.trim());
            }
            candidates.add(type.getDefaultCliExecutable());
        }

        List<CliCandidate> installed = new ArrayList<>();
        for (String exe : candidates) {
            CliModelClient.CliAuthStatus status = probe.checkCli(type, exe);
            if (!status.installed()) {
                continue;
            }
            boolean signedIn = status.state() == CliModelClient.AuthState.SIGNED_IN;
            ModelDiscoveryService.DiscoveryResult agyDisc = null;
            // agy CLI doesn't have an auth status subcommand; listing models verifies active authentication
            if ("agy".equals(CliModelClient.canonicalCliName(exe)) && probeAgyLiveModels && !isCancelled.getAsBoolean()) {
                ProviderConfig probeCfg = ProviderConfig.defaultsFor(type);
                probeCfg.connectionMode = ConnectionMode.LOCAL_CLI;
                probeCfg.cliExecutable = exe;
                probeCfg.cliArgs = AGY_DEFAULT_ARGS;
                agyDisc = probe.discoverModels(type, probeCfg, "", isCancelled);
                if (agyDisc.success() && !agyDisc.models().isEmpty()) {
                    signedIn = true;
                    status = new CliModelClient.CliAuthStatus(true, status.resolvedPath(),
                            CliModelClient.AuthState.SIGNED_IN, "agy CLI ready (" + agyDisc.models().size() + " models)");
                }
            }
            installed.add(new CliCandidate(exe, status, signedIn, agyDisc));
        }

        if (installed.isEmpty()) {
            return null;
        }
        // Prefer an installed CLI that is already signed in (preserving candidate priority order)
        for (CliCandidate cand : installed) {
            if (cand.effectivelySignedIn) {
                return cand;
            }
        }
        return installed.get(0);
    }

    public static String defaultArgsForExecutable(ModelProviderType type, String executable, String existingArgs) {
        String canonical = CliModelClient.canonicalCliName(executable);
        String existing = existingArgs == null ? "" : existingArgs.trim();
        if ("agy".equals(canonical)) {
            if (existing.isBlank() || existing.contains("-o ") || existing.contains("-p \"\"") || !existing.contains("{prompt}")) {
                return AGY_DEFAULT_ARGS;
            }
            return existing;
        }
        if ("gemini".equals(canonical)) {
            if (existing.isBlank() || existing.contains("--disable-slash-commands") || existing.equals("-p \"\" -o text")) {
                return "-p \"\" --output-format text";
            }
            return existing;
        }
        if ("codex".equals(canonical)) {
            if (existing.isBlank() || "exec -".equals(existing)) {
                return ModelProviderType.CHATGPT.getDefaultCliArgs();
            }
            return existing;
        }
        if ("claude".equals(canonical)) {
            if (existing.isBlank()) {
                return ModelProviderType.CLAUDE.getDefaultCliArgs();
            }
            return existing;
        }
        return existing.isBlank() ? type.getDefaultCliArgs() : existing;
    }

    public static String detectEnvBaseUrl(ModelProviderType type, Map<String, String> env) {
        if (env == null) return "";
        List<String> keys = switch (type) {
            case CLAUDE -> List.of("ANTHROPIC_BASE_URL");
            case CHATGPT -> List.of("OPENAI_BASE_URL", "OPENAI_API_BASE");
            case GEMINI -> List.of("GOOGLE_GEMINI_BASE_URL", "GEMINI_API_BASE_URL");
        };
        for (String key : keys) {
            String val = CliModelClient.getEnvCaseInsensitive(env, key);
            if (val != null && !val.isBlank()) {
                String trimmed = val.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("https://") || lower.startsWith("http://")) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    public static String detectEnvApiKey(ModelProviderType type, Map<String, String> env) {
        if (env == null) return "";
        String primary = CliModelClient.getEnvCaseInsensitive(env, type.getEnvVarName());
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (type == ModelProviderType.GEMINI) {
            String googleKey = CliModelClient.getEnvCaseInsensitive(env, "GOOGLE_API_KEY");
            if (googleKey != null && !googleKey.isBlank()) {
                return googleKey.trim();
            }
        }
        return "";
    }

    private static boolean isDefaultOrBlankBaseUrl(ModelProviderType type, String url) {
        if (url == null || url.isBlank()) return true;
        return trimSlashes(url).equalsIgnoreCase(trimSlashes(type.getDefaultBaseUrl()));
    }

    private static String trimSlashes(String s) {
        String r = s.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
