package com.catalogue.verg.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthUserServiceTest {

    private static final String BASE = "http://auth:8080";
    private static final String USER_ID = "user-000000000001";

    @Mock private RestTemplate restTemplate;
    @InjectMocks private AuthUserService authUserService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authUserService, "authServiceUrl", BASE + "/");
        ReflectionTestUtils.setField(authUserService, "authServiceApiKey", "test-key");
    }

    @Test
    void createPostsToTheCreatePath() {
        stubExchange(ResponseEntity.ok(Map.of()));

        authUserService.createAuthUser("Asha", "Rao", "asha@example.org", USER_ID,
                "org-1", "FIELD_OFFICER", "Org", "Asha R");

        HttpEntity<Map<String, Object>> sent = captureExchange(BASE + AuthUserService.AUTH_USER_CREATE_PATH);
        assertThat(sent.getBody()).containsEntry("userId", USER_ID).containsEntry("email", "asha@example.org");
        assertThat(sent.getHeaders().getFirst(AuthUserService.API_KEY_HEADER)).isEqualTo("test-key");
    }

    @Test
    void revokePostsToTheRevokePath() {
        stubExchange(ResponseEntity.ok(Map.of()));

        authUserService.revokeAuthUser(USER_ID);

        HttpEntity<Map<String, Object>> sent = captureExchange(BASE + AuthUserService.AUTH_USER_REVOKE_PATH);
        assertThat(sent.getBody()).isEqualTo(Map.of("userId", USER_ID));
    }

    @Test
    void deletePostsToTheDeletePath() {
        stubExchange(ResponseEntity.ok(Map.of()));

        authUserService.deleteAuthUser(USER_ID);

        HttpEntity<Map<String, Object>> sent = captureExchange(BASE + AuthUserService.AUTH_USER_DELETE_PATH);
        assertThat(sent.getBody()).isEqualTo(Map.of("userId", USER_ID));
    }

    @Test
    void revokeTreatsAnAbsentAuthIdentityAsSuccess() {
        stubExchangeThrows(notFound("{\"code\":\"AUTH_USER_NOT_FOUND\",\"message\":\"...\"}"));

        ResponseEntity<Map<String, Object>> response = authUserService.revokeAuthUser(USER_ID);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void revokeStillFailsOnAnyOther404SoAMisroutedUrlFailsClosed() {
        stubExchangeThrows(notFound("<html>Not Found</html>"));

        assertThatThrownBy(() -> authUserService.revokeAuthUser(USER_ID))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void updatePostsToTheUpdatePath() {
        stubExchange(ResponseEntity.ok(Map.of()));

        authUserService.updateAuthUser("Asha", "Rao", "asha@example.org", USER_ID,
                "org-1", "FIELD_OFFICER", "Org", "Asha R");

        HttpEntity<Map<String, Object>> sent = captureExchange(BASE + AuthUserService.AUTH_USER_UPDATE_PATH);
        assertThat(sent.getBody()).containsEntry("userId", USER_ID).containsEntry("displayName", "Asha R");
    }

    // ---------------------------------------------------------------- helpers

    private static HttpClientErrorException notFound(String body) {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void stubExchange(ResponseEntity<Map<String, Object>> response) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubExchangeThrows(RuntimeException e) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenThrow(e);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpEntity<Map<String, Object>> captureExchange(String expectedUri) {
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(expectedUri), eq(HttpMethod.POST), entity.capture(),
                any(ParameterizedTypeReference.class));
        return entity.getValue();
    }
}
