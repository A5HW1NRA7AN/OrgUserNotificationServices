package com.catalogue.verg.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/** Client for auth_service: creates, updates, revokes and deletes the auth identity with the record. */
@Slf4j
@Service
public class AuthUserService {

    static final String AUTH_USER_CREATE_PATH = "/auth/v1/auth_user_create";
    static final String AUTH_USER_UPDATE_PATH = "/auth/v1/auth_user_update";
    static final String AUTH_USER_REVOKE_PATH = "/auth/v1/auth_user_revoke";
    static final String AUTH_USER_DELETE_PATH = "/auth/v1/auth_user_delete";
    static final String AUTH_USER_NOT_FOUND_CODE = "AUTH_USER_NOT_FOUND";

    /** Header auth_service expects the api key on; change here if that contract changes. */
    static final String API_KEY_HEADER = "apiKey";

    @Value("${auth.service.url}")
    private String authServiceUrl;

    @Value("${auth.service.api.key}")
    private String authServiceApiKey;

    @Autowired
    private RestTemplate restTemplate;

    /** Publishes the user via auth_user_create; callers gate on the returned status. */
    public ResponseEntity<Map<String, Object>> createAuthUser(String firstName, String lastName, String email,
                                                              String userId, String orgId, String functionalRole,
                                                              String orgName, String displayName) {
        return post(AUTH_USER_CREATE_PATH,
                userRequest(firstName, lastName, email, userId, orgId, functionalRole, orgName, displayName), userId);
    }

    /** Syncs a profile edit via auth_user_update (replace: an omitted optional field is cleared). */
    public ResponseEntity<Map<String, Object>> updateAuthUser(String firstName, String lastName, String email,
                                                              String userId, String orgId, String functionalRole,
                                                              String orgName, String displayName) {
        return post(AUTH_USER_UPDATE_PATH,
                userRequest(firstName, lastName, email, userId, orgId, functionalRole, orgName, displayName), userId);
    }

    /** The body auth_user_create and auth_user_update share. */
    private Map<String, Object> userRequest(String firstName, String lastName, String email, String userId,
                                            String orgId, String functionalRole, String orgName, String displayName) {
        // auth_service's request contract; functionalRole and email are required there.
        Map<String, Object> request = new HashMap<>();
        request.put("firstName", firstName);
        request.put("lastName", lastName);
        request.put("email", email);
        request.put("userId", userId);
        request.put("orgId", orgId);
        request.put("functionalRole", functionalRole);
        request.put("orgName", orgName);
        request.put("displayName", displayName);
        return request;
    }

    /** Blocks the account in auth_service when a record leaves ACTIVE. Idempotent. */
    public ResponseEntity<Map<String, Object>> revokeAuthUser(String userId) {
        try {
            return post(AUTH_USER_REVOKE_PATH, Map.of("userId", userId), userId);
        } catch (HttpClientErrorException.NotFound e) {
            // Revoked but no Keycloak identity to disable; matched on the code so a bad URL still fails.
            if (!e.getResponseBodyAsString().contains(AUTH_USER_NOT_FOUND_CODE)) {
                throw e;
            }
            log.warn("AuthUserService::revokeAuthUser::no auth identity for userId: {}, nothing to disable", userId);
            return ResponseEntity.noContent().build();
        }
    }

    /** Removes the auth identity and its tokens when a record is deleted. Idempotent. */
    public ResponseEntity<Map<String, Object>> deleteAuthUser(String userId) {
        return post(AUTH_USER_DELETE_PATH, Map.of("userId", userId), userId);
    }

    /** Posts to auth_service; 4xx/5xx and connection errors propagate to the caller. */
    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> request, String userId) {
        String uri = buildUri(path);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authServiceApiKey != null && !authServiceApiKey.isBlank()) {
            headers.set(API_KEY_HEADER, authServiceApiKey.trim());
        } else {
            log.warn("AuthUserService::post::no api key configured, calling auth_service without the {} header",
                    API_KEY_HEADER);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        log.info("AuthUserService::post::posting to {} for userId: {}", uri, userId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(uri, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        log.info("AuthUserService::post::auth_service responded with status: {} for userId: {}",
                response.getStatusCode(), userId);
        return response;
    }

    private String buildUri(String path) {
        if (authServiceUrl == null || authServiceUrl.isBlank()) {
            throw new IllegalStateException("AuthUserService::auth.service.url is not configured");
        }
        String base = authServiceUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
