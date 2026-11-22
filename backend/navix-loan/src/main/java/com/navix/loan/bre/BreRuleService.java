package com.navix.loan.bre;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlag;
import com.navix.common.featureflag.FeatureFlagRepository;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.bre.BreRuleCatalog.Rule;
import com.navix.loan.bre.BreRuleCatalog.Threshold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-editable BRE switches and numbers. On/off rides on {@code feature_flag} (read uncached), numbers
 * on {@code bre_threshold}; a missing row means the {@link BreRuleCatalog} default, which is today's
 * behaviour. Depends only on the flag and threshold stores so every decision service can inject it.
 */
@Service
@RequiredArgsConstructor
public class BreRuleService {

    private static final Logger log = LoggerFactory.getLogger(BreRuleService.class);

    public record BreThresholdView(String key, String label, String unit, long value, long defaultValue,
                                   long min, long max, String updatedBy, Instant updatedAt) {
    }

    public record BreRuleView(String key, String group, String title, String checks, String outcome,
                              String source, boolean toggleable, String flagKey, Boolean enabled,
                              String updatedBy, Instant updatedAt, List<BreThresholdView> thresholds) {
    }

    private final FeatureFlagService featureFlags;
    private final FeatureFlagRepository flagRepository;
    private final BreThresholdRepository thresholdRepository;

    /** Whether the rule is enforced right now. Unknown or flag-less rule keys are a programming error. */
    public boolean isOn(String ruleKey) {
        Rule rule = BreRuleCatalog.BY_KEY.get(ruleKey);
        if (rule == null || rule.flagKey() == null) {
            throw new IllegalArgumentException("No switchable BRE rule: " + ruleKey);
        }
        return featureFlags.isEnabled(rule.flagKey(), rule.defaultOn());
    }

    /** The stored value, else the catalog default. */
    @Transactional(readOnly = true)
    public int threshold(String key) {
        Threshold t = BreRuleCatalog.THRESHOLDS.get(key);
        if (t == null) {
            throw new IllegalArgumentException("No BRE threshold: " + key);
        }
        return (int) thresholdRepository.findByThresholdKey(key).map(BreThreshold::getValue)
                .orElse(t.defaultValue()).longValue();
    }

    @Transactional(readOnly = true)
    public List<BreRuleView> list() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "BRE rules are visible to staff only");
        }
        return BreRuleCatalog.RULES.stream().map(this::view).toList();
    }

    @Transactional
    public BreRuleView setEnabled(String ruleKey, boolean enabled) {
        requireAdmin();
        Rule rule = BreRuleCatalog.BY_KEY.get(ruleKey);
        if (rule == null) {
            throw new BusinessException("BRE_RULE_NOT_FOUND", "Unknown BRE rule: " + ruleKey);
        }
        if (!rule.toggleable()) {
            throw new BusinessException("BRE_RULE_NOT_TOGGLEABLE", "This rule cannot be switched on or off");
        }
        FeatureFlag flag = flagRepository.findByFlagKey(rule.flagKey()).orElseGet(() -> {
            FeatureFlag f = new FeatureFlag();
            f.setFlagKey(rule.flagKey());
            f.setDescription("BRE rule: " + rule.title());
            return f;
        });
        flag.setEnabled(enabled);
        flagRepository.save(flag);
        log.info("BRE rule {} set {} by {}", ruleKey, enabled ? "ON" : "OFF", ActorContext.get().id());
        return view(rule);
    }

    @Transactional
    public BreThresholdView setThreshold(String key, Number value) {
        requireAdmin();
        Threshold t = BreRuleCatalog.THRESHOLDS.get(key);
        if (t == null) {
            throw new BusinessException("BRE_THRESHOLD_NOT_FOUND", "Unknown BRE threshold: " + key);
        }
        if (value == null || value.doubleValue() != value.longValue()
                || value.longValue() < t.min() || value.longValue() > t.max()) {
            throw new BusinessException("BRE_THRESHOLD_OUT_OF_RANGE",
                    t.label() + " must be a whole number between " + t.min() + " and " + t.max());
        }
        BreThreshold row = thresholdRepository.findByThresholdKey(key).orElseGet(() -> {
            BreThreshold n = new BreThreshold();
            n.setThresholdKey(key);
            return n;
        });
        row.setValue(value.longValue());
        thresholdRepository.save(row);
        log.info("BRE threshold {} set to {} by {}", key, value.longValue(), ActorContext.get().id());
        return thresholdView(t);
    }

    private BreRuleView view(Rule rule) {
        Optional<FeatureFlag> flag = rule.flagKey() == null ? Optional.empty()
                : flagRepository.findByFlagKey(rule.flagKey());
        Boolean enabled = rule.flagKey() == null ? null
                : flag.map(FeatureFlag::isEnabled).orElse(rule.defaultOn());
        return new BreRuleView(rule.key(), rule.group().name(), rule.title(), rule.checks(), rule.outcome(),
                rule.source(), rule.toggleable(), rule.flagKey(), enabled,
                flag.map(FeatureFlag::getUpdatedBy).orElse(null),
                flag.map(FeatureFlag::getUpdatedAt).orElse(null),
                rule.thresholds().stream().map(this::thresholdView).toList());
    }

    private BreThresholdView thresholdView(Threshold t) {
        Optional<BreThreshold> row = thresholdRepository.findByThresholdKey(t.key());
        return new BreThresholdView(t.key(), t.label(), t.unit(),
                row.map(BreThreshold::getValue).orElse(t.defaultValue()), t.defaultValue(), t.min(), t.max(),
                row.map(BreThreshold::getUpdatedBy).orElse(null),
                row.map(BreThreshold::getUpdatedAt).orElse(null));
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
