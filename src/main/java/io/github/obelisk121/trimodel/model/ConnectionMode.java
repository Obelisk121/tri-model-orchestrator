package io.github.obelisk121.trimodel.model;

public enum ConnectionMode {
    LOCAL_CLI("Browser Sign-In (No Client ID — Official CLI)"),
    REST_API("API Key (Paste & Go)"),
    OAUTH_BROWSER("Custom OAuth 2.0 (Own Client ID / Enterprise)");

    private final String displayName;

    ConnectionMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
