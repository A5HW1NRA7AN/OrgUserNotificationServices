package com.catalogue.verg.core.util;

import java.util.Set;

/** The lifecycle state machine shared by every catalogue; ACTIVE is set only by review. */
public final class LifecycleUtil {

    private LifecycleUtil() {
    }

    /** Statuses from which {@code add PUT /add/{id}} may (re-)submit a record to PENDING. */
    public static final Set<String> ADD_PROMOTABLE = Set.of(Constants.DRAFT, Constants.REWORK);

    /** {@code approve} acts on a PENDING record. */
    public static final String APPROVE_FROM = Constants.PENDING;
    public static final Set<String> APPROVE_TARGETS =
            Set.of(Constants.APPROVED, Constants.REJECTED, Constants.REWORK);

    /** {@code review} acts on an APPROVED record. */
    public static final String REVIEW_FROM = Constants.APPROVED;
    public static final Set<String> REVIEW_TARGETS =
            Set.of(Constants.ACTIVE, Constants.REJECTED, Constants.REWORK, Constants.PENDING);

    /** Maps the alias PUBLISHED to ACTIVE; any other value (or null) is returned as is. */
    public static String normalizeTarget(String status) {
        if (status == null) {
            return null;
        }
        String upper = status.trim().toUpperCase();
        return Constants.PUBLISHED.equals(upper) ? Constants.ACTIVE : upper;
    }
}
