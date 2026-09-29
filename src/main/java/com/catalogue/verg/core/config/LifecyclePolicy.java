package com.catalogue.verg.core.config;

import com.catalogue.verg.core.exception.CustomException;
import com.catalogue.verg.core.util.Constants;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Whether the lifecycle applies to a catalogue: the global switch AND its override (absent = on). */
// No class-level @Getter/@Setter (would bind `live`), and keep the default constructor (field defaults).
@Component
@ConfigurationProperties(prefix = "catalogue.lifecycle")
@Slf4j
public class LifecyclePolicy {

    /** Service-wide master switch. false => no catalogue follows the lifecycle. */
    private boolean enabled = true;

    /** Per-catalogue overrides as bound from configuration. Absent key = enabled. */
    private Map<String, Boolean> entities = new HashMap<>();

    /** Normalised, mutable view of {@link #entities}. Not a binding target. */
    private final Map<String, Boolean> live = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Boolean> getEntities() {
        return entities;
    }

    public void setEntities(Map<String, Boolean> entities) {
        this.entities = entities;
    }

    @PostConstruct
    void seedFromConfig() {
        entities.forEach((key, value) -> live.put(normalize(key), Boolean.TRUE.equals(value)));
        log.info("LifecyclePolicy: catalogue.lifecycle.enabled={} overrides={}", enabled, live);
    }

    /** Effective policy for a catalogue: the global switch AND its per-catalogue override. */
    public boolean isEnabledFor(String catalogue) {
        return enabled && live.getOrDefault(normalize(catalogue), Boolean.TRUE);
    }

    /** PENDING when the lifecycle runs for this catalogue, ACTIVE when it does not. */
    public String initialStatus(String catalogue) {
        return isEnabledFor(catalogue) ? Constants.PENDING : Constants.ACTIVE;
    }

    /** 404s a lifecycle-only endpoint when the lifecycle is off for this catalogue. */
    public void requireEnabled(String catalogue) {
        if (!isEnabledFor(catalogue)) {
            log.debug("LifecyclePolicy::requireEnabled:lifecycle is disabled for catalogue: {}", catalogue);
            throw new CustomException(Constants.LIFECYCLE_DISABLED,
                    "Lifecycle is disabled for catalogue '" + catalogue + "'", HttpStatus.NOT_FOUND);
        }
    }

    /** Flips a catalogue at runtime; in-memory and per instance, so a restart resets it. */
    public void setEnabledFor(String catalogue, boolean lifecycleEnabled) {
        live.put(normalize(catalogue), lifecycleEnabled);
        log.info("LifecyclePolicy::setEnabledFor:{}={}", normalize(catalogue), lifecycleEnabled);
    }

    /** Canonical key, ignoring case and separators, matching main.py's service_name_lower. */
    private static String normalize(String catalogue) {
        return catalogue == null
                ? ""
                : catalogue.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
