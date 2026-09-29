package com.catalogue.verg.user.service.impl;

import com.catalogue.verg.core.cache.CacheService;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.elasticsearch.service.ESUtilService;
import com.catalogue.verg.core.exception.CustomException;
import com.catalogue.verg.core.service.AuthUserService;
import com.catalogue.verg.core.util.Constants;
import com.catalogue.verg.core.util.HashUtil;
import com.catalogue.verg.core.util.PayloadValidation;
import com.catalogue.verg.core.util.VergProperties;
import com.catalogue.verg.user.entity.UserEntity;
import com.catalogue.verg.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String USER_ID = "user-000000000001";
    private static final String EMAIL = "asha.rao@example.org";
    private static final String PASSWORD = "correct-horse";
    private static final String PIN = "482913";

    @Mock private PayloadValidation payloadValidation;
    @Mock private UserRepository userRepository;
    @Mock private ESUtilService esUtilService;
    @Mock private CacheService cacheService;
    @Mock private VergProperties vergProperties;
    @Mock private AuthUserService authUserService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private UserServiceImpl userService;

    // BCrypt is ~50-100ms per hash, so hash once for the whole class rather than per test.
    private static final String PASSWORD_HASH = HashUtil.encode(PASSWORD);
    private static final String PIN_HASH = HashUtil.encode(PIN);

    private UserEntity stored;

    @BeforeEach
    void setUp() {
        ObjectNode data = objectMapper.createObjectNode()
                .put(Constants.FIRST_NAME, "Asha")
                .put(Constants.EMAIL, EMAIL)
                .put(Constants.PASSWORD, PASSWORD_HASH)
                .put(Constants.PIN, PIN_HASH);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        stored = new UserEntity(USER_ID, data, now, now, Constants.ACTIVE);
    }

    // ---------------------------------------------------------------- credentials never leave postgres

    @Test
    void readFromPostgresReturnsAndCachesNoCredentials() {
        when(cacheService.getCache(USER_ID)).thenReturn(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));

        CustomResponse response = userService.read(USER_ID);

        assertNoCredentials(objectMapper.valueToTree(response.getResult().get(Constants.RESULT)));
        ArgumentCaptor<Object> cached = ArgumentCaptor.forClass(Object.class);
        verify(cacheService).putCache(eq(USER_ID), cached.capture());
        assertNoCredentials(objectMapper.valueToTree(cached.getValue()));
    }

    @Test
    void updateHashesASuppliedPasswordAndCarriesForwardAnOmittedPin() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        ObjectNode incoming = objectMapper.createObjectNode()
                .put(Constants.FIRST_NAME, "Asha")
                .put(Constants.EMAIL, EMAIL)
                .put(Constants.PASSWORD, "new-secret");

        CustomResponse response = userService.updateUser(USER_ID, incoming);

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(saved.capture());
        JsonNode data = saved.getValue().getData();
        assertThat(data.get(Constants.PASSWORD).asText()).isNotEqualTo("new-secret");
        assertThat(HashUtil.matches("new-secret", data.get(Constants.PASSWORD).asText())).isTrue();
        assertThat(data.get(Constants.PIN).asText()).isEqualTo(PIN_HASH);

        // Validation sees the merged payload, which is what satisfies the schema's required list.
        ArgumentCaptor<JsonNode> validated = ArgumentCaptor.forClass(JsonNode.class);
        verify(payloadValidation).validatePayload(anyString(), validated.capture());
        assertThat(validated.getValue().has(Constants.PIN)).isTrue();

        ArgumentCaptor<Map<String, Object>> indexed = mapCaptor();
        verify(esUtilService).updateDocument(anyString(), any(), eq(USER_ID), indexed.capture(), any());
        assertNoCredentials(objectMapper.valueToTree(indexed.getValue()));
        ArgumentCaptor<Object> cached = ArgumentCaptor.forClass(Object.class);
        verify(cacheService).putCache(eq(USER_ID), cached.capture());
        assertNoCredentials(objectMapper.valueToTree(cached.getValue()));
        assertNoCredentials(objectMapper.valueToTree(response.getResult()));
    }

    // ---------------------------------------------------------------- verifyUser

    @Test
    void verifyReturnsTheUserIdForTheRightPassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(List.of(stored));

        CustomResponse response = userService.verifyUser(verifyRequest(EMAIL, PASSWORD));

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getResult()).containsEntry(Constants.USER_ID_RQST, USER_ID);
        assertNoCredentials(objectMapper.valueToTree(response.getResult()));
        // The login path must never read Elasticsearch: the hash is not there, and must not return.
        verifyNoInteractions(esUtilService);
    }

    @Test
    void verifyRejectsAWrongPassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(List.of(stored));

        CustomResponse response = userService.verifyUser(verifyRequest(EMAIL, "wrong"));

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(esUtilService);
    }

    @Test
    void verifyRejectsAnUnknownEmailWithTheSameResponseAsAWrongPassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(List.of());

        CustomResponse response = userService.verifyUser(verifyRequest(EMAIL, PASSWORD));

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getMessage()).isEqualTo(Constants.INVALID_CREDENTIALS);
        verifyNoInteractions(esUtilService);
    }

    @Test
    void verifyRejectsARecordThatIsNotActive() {
        stored.setStatus(Constants.IN_ACTIVE);
        when(userRepository.findByEmail(EMAIL)).thenReturn(List.of(stored));

        CustomResponse response = userService.verifyUser(verifyRequest(EMAIL, PASSWORD));

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(esUtilService);
    }

    @Test
    void verifyRejectsAMissingEmailWithoutTouchingAnyStore() {
        CustomResponse response = userService.verifyUser(verifyRequest(null, PASSWORD));

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(userRepository, esUtilService);
    }

    // ---------------------------------------------------------------- auth lifecycle sync

    @Test
    void deactivatingRevokesInAuthBeforeTheLocalWrite() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.revokeAuthUser(USER_ID)).thenReturn(ResponseEntity.ok(Map.of()));

        CustomResponse response = userService.toggleStatus(USER_ID);

        assertThat(response.getResponseCode()).isEqualTo(HttpStatus.OK);
        InOrder order = inOrder(authUserService, userRepository);
        order.verify(authUserService).revokeAuthUser(USER_ID);
        order.verify(userRepository).save(stored);
        assertThat(stored.getStatus()).isEqualTo(Constants.IN_ACTIVE);
    }

    @Test
    void reactivatingRestoresTheAuthIdentityRatherThanRevoking() {
        stored.setStatus(Constants.IN_ACTIVE);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.createAuthUser(any(), any(), eq(EMAIL), eq(USER_ID), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(Map.of()));

        userService.toggleStatus(USER_ID);

        verify(authUserService, never()).revokeAuthUser(any());
        assertThat(stored.getStatus()).isEqualTo(Constants.ACTIVE);
    }

    @Test
    void aFailedRevokeLeavesTheRecordActive() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.revokeAuthUser(USER_ID)).thenThrow(new ResourceAccessException("auth down"));

        assertThatThrownBy(() -> userService.toggleStatus(USER_ID)).isInstanceOf(CustomException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(esUtilService, cacheService);
    }

    @Test
    void aNon2xxAuthResponseSurfacesAs502RatherThanAGeneric500() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.revokeAuthUser(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> userService.toggleStatus(USER_ID))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).isEqualTo(Constants.AUTH_USER_REVOKE_FAILED);
                });
    }

    @Test
    void deleteRemovesTheAuthIdentityBeforeTheSoftDelete() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.deleteAuthUser(USER_ID)).thenReturn(ResponseEntity.ok(Map.of()));

        userService.delete(USER_ID);

        InOrder order = inOrder(authUserService, userRepository);
        order.verify(authUserService).deleteAuthUser(USER_ID);
        order.verify(userRepository).save(stored);
        assertThat(stored.getStatus()).isEqualTo(Constants.DELETED);
    }

    @Test
    void aFailedAuthDeleteLeavesTheRecordUntouched() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        when(authUserService.deleteAuthUser(USER_ID)).thenThrow(new ResourceAccessException("auth down"));

        assertThatThrownBy(() -> userService.delete(USER_ID)).isInstanceOf(CustomException.class);

        assertThat(stored.getStatus()).isEqualTo(Constants.ACTIVE);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(esUtilService, cacheService);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode verifyRequest(String email, String password) {
        ObjectNode node = objectMapper.createObjectNode();
        if (email != null) node.put(Constants.EMAIL, email);
        if (password != null) node.put(Constants.PASSWORD, password);
        return node;
    }

    private static void assertNoCredentials(JsonNode node) {
        assertThat(node.has(Constants.PASSWORD)).as("password present in %s", node).isFalse();
        assertThat(node.has(Constants.PIN)).as("pin present in %s", node).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
