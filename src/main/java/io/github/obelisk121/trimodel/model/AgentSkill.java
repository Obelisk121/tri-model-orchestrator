package io.github.obelisk121.trimodel.model;

/**
 * Composable behavioral skills enforced on orchestrated agents to guarantee independent
 * judgment, claim-by-claim scrutiny, anti-sycophancy, and high-precision engineering detail.
 */
public enum AgentSkill {
    NO_BLIND_AGREEMENT(
            "Anti-Sycophancy (No Blind Agreement)",
            "Forbids rubber-stamping or deferential praise; treats every peer claim as an unverified hypothesis that must be challenged or proven."
    ),
    TOPIC_BY_TOPIC_AUDIT(
            "Topic-by-Topic Peer Analysis",
            "Requires decomposing every topic and claim raised by prior agents and rating each as [VERIFIED], [DISPUTED], [MISSING], or [UNPROVEN]."
    ),
    DETAIL_PRECISION(
            "High-Precision Detail & Evidence",
            "Bans vague advice; requires exact symbols, file/line references, boundary values, failure scenarios, exception types, and complexity."
    ),
    ANONYMIZED_BLIND_REVIEW(
            "Blind Peer Labels (De-Biased Handoff)",
            "Hides peer model brand names (Claude/ChatGPT/Gemini) in handoffs so agents judge strictly on technical merit without brand bias."
    ),
    DEVILS_ADVOCATE(
            "Devil's Advocate Stress-Testing",
            "Actively constructs concrete breaking inputs, race conditions, and counter-designs to try to falsify peer proposals before acceptance."
    );

    private final String displayName;
    private final String description;

    AgentSkill(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
