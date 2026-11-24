package io.github.obelisk121.trimodel.model;

public enum ConnectionMode {
    REST_API("Official REST API (API Key)"),
    OAUTH_BROWSER("Browser OAuth (Temporary Session)"),
    LOCAL_CLI("Local CLI Tool");

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
