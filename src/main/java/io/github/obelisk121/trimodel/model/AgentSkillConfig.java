package io.github.obelisk121.trimodel.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Serializable set of active AgentSkills plus curated presets for simple one-click selection.
 */
public class AgentSkillConfig {

    public enum Preset {
        INDEPENDENT_PRECISE(
                "Independent & Precise",
                "Anti-sycophancy + topic-by-topic peer audit + high-precision details + blind peer labels (Recommended)."
        ),
        STRICT_ADVERSARIAL(
                "Strict Adversarial (Devil's Advocate)",
                "Maximum rigor: all independent skills PLUS mandatory breaking-case construction and counter-designs."
        ),
        BALANCED(
                "Balanced (Lighter)",
                "Standard collaborative orchestration without strict adversarial verification matrices."
        ),
        CUSTOM(
                "Custom Skills",
                "Custom combination configured in Settings -> Tools -> Tri-Model AI Orchestrator."
        );

        private final String displayName;
        private final String description;

        Preset(String displayName, String description) {
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

    public boolean noBlindAgreement = true;
    public boolean topicByTopicAudit = true;
    public boolean detailPrecision = true;
    public boolean anonymizedBlindReview = true;
    public boolean devilsAdvocate = false;

    public AgentSkillConfig() {
    }

    public static AgentSkillConfig forPreset(Preset preset) {
        AgentSkillConfig cfg = new AgentSkillConfig();
        if (preset == null || preset == Preset.INDEPENDENT_PRECISE) {
            cfg.noBlindAgreement = true;
            cfg.topicByTopicAudit = true;
            cfg.detailPrecision = true;
            cfg.anonymizedBlindReview = true;
            cfg.devilsAdvocate = false;
        } else if (preset == Preset.STRICT_ADVERSARIAL) {
            cfg.noBlindAgreement = true;
            cfg.topicByTopicAudit = true;
            cfg.detailPrecision = true;
            cfg.anonymizedBlindReview = true;
            cfg.devilsAdvocate = true;
        } else if (preset == Preset.BALANCED) {
            cfg.noBlindAgreement = false;
            cfg.topicByTopicAudit = false;
            cfg.detailPrecision = true;
            cfg.anonymizedBlindReview = false;
            cfg.devilsAdvocate = false;
        }
        return cfg;
    }

    public Preset detectPreset() {
        if (noBlindAgreement && topicByTopicAudit && detailPrecision && anonymizedBlindReview && !devilsAdvocate) {
            return Preset.INDEPENDENT_PRECISE;
        }
        if (noBlindAgreement && topicByTopicAudit && detailPrecision && anonymizedBlindReview && devilsAdvocate) {
            return Preset.STRICT_ADVERSARIAL;
        }
        if (!noBlindAgreement && !topicByTopicAudit && detailPrecision && !anonymizedBlindReview && !devilsAdvocate) {
            return Preset.BALANCED;
        }
        return Preset.CUSTOM;
    }

    public boolean isEnabled(AgentSkill skill) {
        if (skill == null) return false;
        return switch (skill) {
            case NO_BLIND_AGREEMENT -> noBlindAgreement;
            case TOPIC_BY_TOPIC_AUDIT -> topicByTopicAudit;
            case DETAIL_PRECISION -> detailPrecision;
            case ANONYMIZED_BLIND_REVIEW -> anonymizedBlindReview;
            case DEVILS_ADVOCATE -> devilsAdvocate;
        };
    }

    public Set<AgentSkill> enabledSkills() {
        EnumSet<AgentSkill> set = EnumSet.noneOf(AgentSkill.class);
        for (AgentSkill s : AgentSkill.values()) {
            if (isEnabled(s)) {
                set.add(s);
            }
        }
        return set;
    }

    public AgentSkillConfig copy() {
        AgentSkillConfig c = new AgentSkillConfig();
        c.noBlindAgreement = this.noBlindAgreement;
        c.topicByTopicAudit = this.topicByTopicAudit;
        c.detailPrecision = this.detailPrecision;
        c.anonymizedBlindReview = this.anonymizedBlindReview;
        c.devilsAdvocate = this.devilsAdvocate;
        return c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentSkillConfig that)) return false;
        return noBlindAgreement == that.noBlindAgreement
                && topicByTopicAudit == that.topicByTopicAudit
                && detailPrecision == that.detailPrecision
                && anonymizedBlindReview == that.anonymizedBlindReview
                && devilsAdvocate == that.devilsAdvocate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(noBlindAgreement, topicByTopicAudit, detailPrecision, anonymizedBlindReview, devilsAdvocate);
    }
}
