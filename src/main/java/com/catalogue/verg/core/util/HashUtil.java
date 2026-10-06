package com.catalogue.verg.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Pattern;

/** BCrypt for stored credentials; salted per call, so verify with matches(), never by re-hashing. */
@Slf4j
public final class HashUtil {

    /** Cost 10: ~50-100ms per hash, affordable for bulk import yet slow to brute-force. */
    private static final int STRENGTH = 10;

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(STRENGTH);

    /** A complete BCrypt hash; the portals send credentials already hashed. */
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private HashUtil() {
    }

    /** BCrypt hash (60 chars) of a raw secret; an existing hash is returned as is. Throws for null. */
    public static String encode(String rawSecret) {
        if (rawSecret == null) {
            throw new IllegalArgumentException("HashUtil::rawSecret must not be null");
        }
        // Hashing a caller's hash again would store a hash of a hash and break login.
        return isHash(rawSecret) ? rawSecret : ENCODER.encode(rawSecret);
    }

    /** True if the value is already a BCrypt hash. */
    public static boolean isHash(String value) {
        return value != null && BCRYPT_HASH.matcher(value).matches();
    }

    /** True if the secret matches the stored hash; false for nulls or a malformed hash. */
    public static boolean matches(String rawSecret, String encodedSecret) {
        if (rawSecret == null || encodedSecret == null) {
            return false;
        }
        return ENCODER.matches(rawSecret, encodedSecret);
    }

    /** Copy of the payload with the given fields hashed; absent fields are skipped. */
    public static JsonNode hashSecrets(JsonNode payload, String... fields) {
        if (payload == null || !payload.isObject()) {
            log.warn("HashUtil::hashSecrets::payload is not an object, nothing to hash");
            return payload;
        }
        ObjectNode copy = ((ObjectNode) payload).deepCopy();
        for (String field : fields) {
            if (payload.hasNonNull(field)) {
                copy.put(field, encode(payload.get(field).asText()));
            } else {
                log.warn("HashUtil::hashSecrets::no {} on the payload, nothing to hash", field);
            }
        }
        return copy;
    }
}
