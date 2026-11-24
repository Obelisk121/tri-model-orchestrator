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
            "claude-sonnet-5-5",
            List.of(
                    "claude-sonnet-5-5",
                    "claude-opus-5-5",
                    "claude-fable-5-2",
                    "claude-haiku-5-5"
            ),
            "ANTHROPIC_API_KEY",
            "claude",
            "-p --output-format text",
            "#C96442",
            "#EA8C6E"
    ),
    CHATGPT(
            "ChatGPT",
            "OpenAI ChatGPT",
            "https://api.openai.com/v1",
            "gpt-6.2-sol",
            List.of(
                    "gpt-6.2-sol",
                    "gpt-6.2-astra",
                    "gpt-6.1-terra",
                    "gpt-6-luna"
            ),
            "OPENAI_API_KEY",
            "codex",
            "exec -",
            "#0F9D78",
            "#2AD1A3"
    ),
    GEMINI(
            "Gemini",
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-4-neon",
            List.of(
                    "gemini-4-neon",
                    "gemini-4-carbon",
                    "gemini-4-argon",
                    "gemini-4-hydrogen",
                    "gemini-3.8-flash"
            ),
            "GEMINI_API_KEY",
            "gemini",
            "-p \"\"",
            "#2563EB",
            "#60A5FA"
    );

    private final String shortName;
    private final String fullName;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final List<String> modelPresets;
    private final String envVarName;
    private final String defaultCliExecutable;
    private final String defaultCliArgs;
    private final String lightAccentHex;
    private final String darkAccentHex;

    ModelProviderType(
            String shortName,
            String fullName,
            String defaultBaseUrl,
            String defaultModel,
            List<String> modelPresets,
            String envVarName,
            String defaultCliExecutable,
            String defaultCliArgs,
            String lightAccentHex,
            String darkAccentHex
    ) {
        this.shortName = shortName;
        this.fullName = fullName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.modelPresets = modelPresets;
        this.envVarName = envVarName;
        this.defaultCliExecutable = defaultCliExecutable;
        this.defaultCliArgs = defaultCliArgs;
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

    public String getDefaultModel() {
        return defaultModel;
    }

    public List<String> getModelPresets() {
        return modelPresets;
    }

    public String getEnvVarName() {
        return envVarName;
    }

    public String getDefaultCliExecutable() {
        return defaultCliExecutable;
    }

    public String getDefaultCliArgs() {
        return defaultCliArgs;
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
