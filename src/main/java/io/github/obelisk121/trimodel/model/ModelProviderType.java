package io.github.obelisk121.trimodel.model;

import java.util.List;

/**
 * Supported AI model providers orchestrated by the plugin.
 */
public enum ModelProviderType {
    CLAUDE(
            "Claude",
            "Anthropic Claude",
            "https://api.anthropic.com",
            "ANTHROPIC_API_KEY",
            "https://console.anthropic.com/settings/keys",
            "https://console.anthropic.com/",
            "claude",
            "-p --output-format text",
            "auth login",
            "auth logout",
            "brew install --cask claude-code",
            "https://console.anthropic.com/oauth/authorize",
            "https://console.anthropic.com/v1/oauth/token",
            "user:profile",
            "#C96442",
            "#EA8C6E"
    ),
    CHATGPT(
            "ChatGPT",
            "OpenAI ChatGPT",
            "https://api.openai.com/v1",
            "OPENAI_API_KEY",
            "https://platform.openai.com/api-keys",
            "https://platform.openai.com/",
            "codex",
            "exec --skip-git-repo-check --ephemeral --color never -s read-only -",
            "login",
            "logout",
            "npm install -g @openai/codex",
            "https://auth.openai.com/oauth/authorize",
            "https://auth.openai.com/oauth/token",
            "openid profile email",
            "#0F9D78",
            "#2AD1A3"
    ),
    GEMINI(
            "Gemini",
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            "GEMINI_API_KEY",
            "https://aistudio.google.com/apikey",
            "https://console.cloud.google.com/apis/credentials",
            "agy",
            "--output-format text --disable-slash-commands -p \"{prompt}\"",
            "--output-format text --disable-slash-commands -p \"Reply OK\"",
            "",
            "curl -fsSL https://antigravity.google/cli/install.sh | bash",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/generative-language.retriever",
            "#2563EB",
            "#60A5FA"
    );

    private final String shortName;
    private final String fullName;
    private final String defaultBaseUrl;
    private final String envVarName;
    private final String apiKeyConsoleUrl;
    private final String oauthConsoleUrl;
    private final String defaultCliExecutable;
    private final String defaultCliArgs;
    private final String defaultCliLoginArgs;
    private final String defaultCliLogoutArgs;
    private final String cliInstallHint;
    private final String defaultOAuthAuthorizeUrl;
    private final String defaultOAuthTokenUrl;
    private final String defaultOAuthScopes;
    private final String lightAccentHex;
    private final String darkAccentHex;

    ModelProviderType(
            String shortName,
            String fullName,
            String defaultBaseUrl,
            String envVarName,
            String apiKeyConsoleUrl,
            String oauthConsoleUrl,
            String defaultCliExecutable,
            String defaultCliArgs,
            String defaultCliLoginArgs,
            String defaultCliLogoutArgs,
            String cliInstallHint,
            String defaultOAuthAuthorizeUrl,
            String defaultOAuthTokenUrl,
            String defaultOAuthScopes,
            String lightAccentHex,
            String darkAccentHex
    ) {
        this.shortName = shortName;
        this.fullName = fullName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.envVarName = envVarName;
        this.apiKeyConsoleUrl = apiKeyConsoleUrl;
        this.oauthConsoleUrl = oauthConsoleUrl;
        this.defaultCliExecutable = defaultCliExecutable;
        this.defaultCliArgs = defaultCliArgs;
        this.defaultCliLoginArgs = defaultCliLoginArgs;
        this.defaultCliLogoutArgs = defaultCliLogoutArgs;
        this.cliInstallHint = cliInstallHint;
        this.defaultOAuthAuthorizeUrl = defaultOAuthAuthorizeUrl;
        this.defaultOAuthTokenUrl = defaultOAuthTokenUrl;
        this.defaultOAuthScopes = defaultOAuthScopes;
        this.lightAccentHex = lightAccentHex;
        this.darkAccentHex = darkAccentHex;
    }

    public String getShortName() {
        return shortName;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /**
     * Models are discovered dynamically from live provider APIs or CLIs rather than hardcoded.
     * Returns empty string when no model has been fetched/selected yet.
     */
    public String getDefaultModel() {
        return "";
    }

    /**
     * Returns an empty list; available models are fetched live via {@code ModelDiscoveryService}.
     */
    public List<String> getModelPresets() {
        return List.of();
    }

    public String getEnvVarName() {
        return envVarName;
    }

    public String getApiKeyConsoleUrl() {
        return apiKeyConsoleUrl;
    }

    public String getOAuthConsoleUrl() {
        return oauthConsoleUrl;
    }

    public String getDefaultCliExecutable() {
        return defaultCliExecutable;
    }

    public String getDefaultCliArgs() {
        return defaultCliArgs;
    }

    public String getDefaultCliLoginArgs() {
        return defaultCliLoginArgs;
    }

    public String getDefaultCliLogoutArgs() {
        return defaultCliLogoutArgs;
    }

    public String getCliInstallHint() {
        return getCliInstallHint(detectOsName());
    }

    /**
     * Returns an OS-appropriate install command for macOS, Linux, or Windows.
     */
    public String getCliInstallHint(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        boolean isWin = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin") || os.contains("osx");
        return switch (this) {
            case CLAUDE -> {
                if (isWin) yield "irm https://claude.ai/install.ps1 | iex";
                if (isMac) yield "brew install --cask claude-code";
                yield "curl -fsSL https://claude.ai/install.sh | bash";
            }
            case CHATGPT -> "npm install -g @openai/codex";
            case GEMINI -> isWin
                    ? "irm https://antigravity.google/cli/install.ps1 | iex"
                    : "curl -fsSL https://antigravity.google/cli/install.sh | bash";
        };
    }

    private static String detectOsName() {
        return System.getProperty("os.name", "");
    }

    public String getDefaultOAuthAuthorizeUrl() {
        return defaultOAuthAuthorizeUrl;
    }

    public String getDefaultOAuthTokenUrl() {
        return defaultOAuthTokenUrl;
    }

    public String getDefaultOAuthScopes() {
        return defaultOAuthScopes;
    }

    public String getLightAccentHex() {
        return lightAccentHex;
    }

    public String getDarkAccentHex() {
        return darkAccentHex;
    }

    @Override
    public String toString() {
        return shortName;
    }
}
