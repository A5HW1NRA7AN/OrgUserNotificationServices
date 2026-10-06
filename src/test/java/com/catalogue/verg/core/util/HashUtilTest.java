package com.catalogue.verg.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** encode() must hash plaintext but never re-hash a caller's BCrypt hash. */
class HashUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodeHashesPlaintext() {
        String hash = HashUtil.encode("correct-horse");

        assertThat(HashUtil.isHash(hash)).isTrue();
        assertThat(HashUtil.matches("correct-horse", hash)).isTrue();
    }

    @Test
    void encodeKeepsAnExistingHash() {
        // The portals send pre-hashed credentials; hashing them again would break login.
        String hash = HashUtil.encode("correct-horse");

        assertThat(HashUtil.encode(hash)).isEqualTo(hash);
    }

    @Test
    void aSecretThatOnlyStartsLikeAHashIsStillHashed() {
        String raw = "$2a$10$notAFullHash";

        String encoded = HashUtil.encode(raw);

        assertThat(encoded).isNotEqualTo(raw);
        assertThat(HashUtil.matches(raw, encoded)).isTrue();
    }

    @Test
    void hashSecretsLeavesAPreHashedPinAlone() {
        String pinHash = HashUtil.encode("482913");
        JsonNode payload = MAPPER.createObjectNode().put("password", "plain-pw").put("pin", pinHash);

        JsonNode hashed = HashUtil.hashSecrets(payload, "password", "pin");

        assertThat(hashed.get("pin").asText()).isEqualTo(pinHash);
        assertThat(HashUtil.matches("plain-pw", hashed.get("password").asText())).isTrue();
    }
}
