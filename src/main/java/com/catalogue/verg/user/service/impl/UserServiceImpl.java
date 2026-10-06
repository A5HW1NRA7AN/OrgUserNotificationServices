package com.catalogue.verg.user.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.catalogue.verg.core.cache.CacheService;
import com.catalogue.verg.core.config.LifecyclePolicy;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.dto.RespParam;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.core.elasticsearch.dto.SearchResult;
import com.catalogue.verg.core.elasticsearch.service.ESUtilService;
import com.catalogue.verg.core.exception.CustomException;
import com.catalogue.verg.core.util.Constants;
import com.catalogue.verg.core.util.HashUtil;
import com.catalogue.verg.core.util.LifecycleUtil;
import com.catalogue.verg.core.util.PayloadValidation;
import com.catalogue.verg.core.util.VergProperties;
// import com.catalogue.verg.core.service.AuditLogService;
import com.catalogue.verg.core.service.AuthUserService;
import com.catalogue.verg.core.service.ImportService;
import com.catalogue.verg.core.service.LoadFromPrimaryService;
import com.catalogue.verg.core.util.PrimaryKeyUtil;
import com.catalogue.verg.user.entity.UserEntity;
import com.catalogue.verg.user.repository.UserRepository;
import com.catalogue.verg.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
// import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;


@Service
@Slf4j
public class UserServiceImpl implements UserService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ESUtilService esUtilService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Autowired
    private VergProperties vergProperties;

    @Autowired
    private ImportService importService;

    @Autowired
    private LoadFromPrimaryService loadFromPrimaryService;

    // @Autowired
    // private AuditLogService auditLogService;

    @Autowired
    private LifecyclePolicy lifecyclePolicy;

    @Autowired
    private AuthUserService authUserService;

    /** Catalogue name on every audit row; also its key in the lifecycle switches. */
    private static final String AUDIT_ENTITY_NAME = "user";

    private Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    private static final Pattern SIX_DIGIT_PIN = Pattern.compile("^[0-9]{6}$");

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createUser(JsonNode userEntity) {
        log.info("UserServiceImpl::createUser:entered the method: " + userEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.USER_VALIDATION_FILE_JSON, userEntity);
        requireSixDigitPin(userEntity);

        log.debug("UserServiceImpl::createUser:validated the payload");

        // Generate Primary Key up front: auth_service is told the userId this catalogue will use.
        String primaryID = primaryKeyUtil.generateKey(Constants.USER_VALIDATION_FILE_JSON);

        // Only a record that goes live on create (lifecycle off) gets an auth identity here.
        if (!lifecyclePolicy.isEnabledFor(AUDIT_ENTITY_NAME)) {
            // Nothing is persisted unless auth_service accepts; outside the try so it is not a 500.
            requireAuth(authUserService.createAuthUser(
                    textValue(userEntity, Constants.FIRST_NAME),
                    textValue(userEntity, Constants.LAST_NAME),
                    textValue(userEntity, Constants.EMAIL),
                    primaryID,
                    textValue(userEntity, Constants.ORG_ID_RQST),
                    textValue(userEntity, Constants.FUNCTIONAL_ROLE),
                    textValue(userEntity, Constants.ORG_NAME),
                    textValue(userEntity, Constants.DISPLAY_NAME)), primaryID, Constants.AUTH_USER_CREATE_FAILED);
            log.info("UserServiceImpl::createUser::auth user created, proceeding to persist userId: {}", primaryID);
        } 
        // Hash the credentials before they reach postgres, ES or redis; raw values are not stored.
        JsonNode userPayload = HashUtil.hashSecrets(userEntity, Constants.PASSWORD, Constants.PIN);

        try {
            log.info("UserServiceImpl::createUser:creating user");
            UserEntity userEntity1 = new UserEntity();
            userEntity1.setUserId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());

            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            userEntity1.setCreatedOn(currentTime);
            userEntity1.setUpdatedOn(currentTime);
            userEntity1.setStatus(initialStatus);
            userEntity1.setData(userPayload);

            userRepository.save(userEntity1);

            log.info("UserServiceImpl::createUser::persisted user in postgres");
            ObjectNode jsonNode = buildDocument(userPayload, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticUserJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.USER_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("UserServiceImpl::createUser::persisted user in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), userEntity,
            //         userEntity1.getCreatedOn(), userEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchUser(SearchCriteria searchCriteria) {
        log.info("UserServiceImpl::searchUser");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("UserServiceImpl::searchUser: user search result fetched from redis");
            response.getResult().put(Constants.RESULT, searchResult);
            createSuccessResponse(response);
            // auditLogService.logAudit(null, AUDIT_ENTITY_NAME, "search", null, null,
            //         objectMapper.valueToTree(searchResult), null, null);
            return response;
        }
        String searchString = searchCriteria.getSearchString();
        if (searchString != null && searchString.length() < 2) {
            createErrorResponse(response, "Minimum 3 characters are required to search",
                    HttpStatus.BAD_REQUEST,
                    Constants.FAILED_CONST);
            return response;
        }
        try {
            searchResult =
                    esUtilService.searchDocuments(Constants.USER_INDEX_NAME, searchCriteria);
            response.getResult().put(Constants.RESULT, searchResult);
            createSuccessResponse(response);
            // auditLogService.logAudit(null, AUDIT_ENTITY_NAME, "search", null, null,
            //         objectMapper.valueToTree(searchResult), null, null);
            return response;
        } catch (Exception e) {
            createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
                    Constants.FAILED_CONST);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
                            TimeUnit.SECONDS);
            return response;
        }
    }

    @Override
    public CustomResponse verifyUser(JsonNode verifyRequest) {
        log.info("UserServiceImpl::verifyUser:entered the method");
        CustomResponse response = new CustomResponse();
        String email = textValue(verifyRequest, Constants.EMAIL);
        String password = textValue(verifyRequest, Constants.PASSWORD);

        if (StringUtils.isEmpty(email) || StringUtils.isEmpty(password)) {
            log.warn("UserServiceImpl::verifyUser:email or password missing on the payload");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.EMAIL_PASSWORD_REQUIRED);
            return response;
        }

        try {
            // 1. Does the email exist? Postgres only: the password hash is never indexed.
            UserEntity user = findUserByEmail(email);
            if (user == null) {
                log.warn("UserServiceImpl::verifyUser:no user found for the given email");
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }

            // 2. Does the password match? BCrypt salts per hash, so only matches() can check it.
            if (!HashUtil.matches(password, textValue(user.getData(), Constants.PASSWORD))) {
                log.warn("UserServiceImpl::verifyUser:password mismatch for the given email");
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }

            // 3. Is the record live? DRAFT / PENDING / INACTIVE / DELETED must not verify.
            String status = user.getStatus();
            if (!Constants.ACTIVE.equals(status)) {
                log.warn("UserServiceImpl::verifyUser:user is {}, not ACTIVE", status);
                response.setResponseCode(HttpStatus.FORBIDDEN);
                response.setMessage(Constants.USER_NOT_ACTIVE);
                return response;
            }

            // 4. Has the password expired? Only revealed after the right password.
            if (expired(user.getData(), Constants.PASSWORD_EXPIRES_ON)) {
                log.warn("UserServiceImpl::verifyUser:password expired for userId: {}", user.getUserId());
                response.setResponseCode(HttpStatus.FORBIDDEN);
                response.setMessage(Constants.PASSWORD_EXPIRED);
                return response;
            }

            log.info("UserServiceImpl::verifyUser:credentials verified for userId: {}",
                    user.getUserId());
            response.getResult().put(Constants.USER_ID_RQST, user.getUserId());
            response.getResult().put(Constants.EMAIL, email);
            response.getResult().put(Constants.STATUS, status);
            response.setMessage(Constants.SUCCESSFULLY_VERIFIED);
            response.setResponseCode(HttpStatus.OK);
            return response;
        } catch (Exception e) {
            log.error("UserServiceImpl::verifyUser:error while verifying the user", e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse verifyPin(JsonNode verifyRequest) {
        log.info("UserServiceImpl::verifyPin:entered the method");
        CustomResponse response = new CustomResponse();
        String userId = textValue(verifyRequest, Constants.USER_ID_RQST);
        String pin = textValue(verifyRequest, Constants.PIN);

        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(pin)) {
            log.warn("UserServiceImpl::verifyPin:userId or pin missing on the payload");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.USER_ID_PIN_REQUIRED);
            return response;
        }

        try {
            // 1. Does the user exist? Postgres only: the pin hash is never indexed.
            Optional<UserEntity> entityOptional = userRepository.findById(userId);
            if (entityOptional.isEmpty()) {
                log.warn("UserServiceImpl::verifyPin:no user found for userId: {}", userId);
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }
            UserEntity user = entityOptional.get();

            // 2. Does the pin match the stored hash?
            if (!HashUtil.matches(pin, textValue(user.getData(), Constants.PIN))) {
                log.warn("UserServiceImpl::verifyPin:pin mismatch for userId: {}", userId);
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }

            // 3. Is the record live?
            String status = user.getStatus();
            if (!Constants.ACTIVE.equals(status)) {
                log.warn("UserServiceImpl::verifyPin:user is {}, not ACTIVE", status);
                response.setResponseCode(HttpStatus.FORBIDDEN);
                response.setMessage(Constants.USER_NOT_ACTIVE);
                return response;
            }

            // 4. Has the pin expired? Only revealed after the right pin.
            if (expired(user.getData(), Constants.PIN_EXPIRES_ON)) {
                log.warn("UserServiceImpl::verifyPin:pin expired for userId: {}", userId);
                response.setResponseCode(HttpStatus.FORBIDDEN);
                response.setMessage(Constants.PIN_EXPIRED);
                return response;
            }

            log.info("UserServiceImpl::verifyPin:pin verified for userId: {}", userId);
            response.getResult().put(Constants.USER_ID_RQST, userId);
            response.getResult().put(Constants.STATUS, status);
            response.setMessage(Constants.SUCCESSFULLY_VERIFIED);
            response.setResponseCode(HttpStatus.OK);
            return response;
        } catch (Exception e) {
            log.error("UserServiceImpl::verifyPin:error while verifying the pin", e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse updatePin(String id, JsonNode pinRequest) {
        log.info("UserServiceImpl::updatePin:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        String currentPassword = textValue(pinRequest, Constants.CURRENT_PASSWORD);
        String newPin = textValue(pinRequest, Constants.NEW_PIN);

        if (StringUtils.isEmpty(id) || StringUtils.isEmpty(currentPassword) || StringUtils.isEmpty(newPin)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.PIN_CHANGE_FIELDS_REQUIRED);
            return response;
        }
        // The user types the new pin here, so a hash is not accepted.
        if (!SIX_DIGIT_PIN.matcher(newPin).matches()) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.PIN_MUST_BE_SIX_DIGITS);
            return response;
        }

        Optional<UserEntity> entityOptional = userRepository.findById(id);
        if (entityOptional.isEmpty()) {
            response.setResponseCode(HttpStatus.NOT_FOUND);
            response.setMessage(Constants.INVALID_ID);
            return response;
        }
        UserEntity user = entityOptional.get();

        // Only the account's own password may change its pin.
        if (!HashUtil.matches(currentPassword, textValue(user.getData(), Constants.PASSWORD))) {
            log.warn("UserServiceImpl::updatePin:password mismatch for id: {}", id);
            response.setResponseCode(HttpStatus.UNAUTHORIZED);
            response.setMessage(Constants.INVALID_CREDENTIALS);
            return response;
        }
        if (!Constants.ACTIVE.equals(user.getStatus())) {
            response.setResponseCode(HttpStatus.FORBIDDEN);
            response.setMessage(Constants.USER_NOT_ACTIVE);
            return response;
        }

        // The stored record with the new pin; updateUser hashes it, carries the password forward and re-indexes.
        ObjectNode payload = ((ObjectNode) user.getData()).deepCopy();
        payload.remove(Constants.PASSWORD);
        payload.put(Constants.PIN, newPin);
        // The caller's expiry for the new pin, or none: a stale past date must not expire it at once.
        String pinExpiresOn = textValue(pinRequest, Constants.PIN_EXPIRES_ON);
        if (StringUtils.isNotBlank(pinExpiresOn)) {
            payload.put(Constants.PIN_EXPIRES_ON, pinExpiresOn);
        } else {
            payload.remove(Constants.PIN_EXPIRES_ON);
        }
        return updateUser(id, payload);
    }

    /** True when the ISO-8601 date in this field has passed; blank or unparseable never expires. */
    private boolean expired(JsonNode data, String field) {
        String value = textValue(data, field);
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            log.warn("UserServiceImpl::expired:unparseable {} '{}', treated as not expired", field, value);
            return false;
        }
    }

    /** Rejects a plaintext pin that is not 6 digits; a pre-hashed pin is left to its sender. */
    private void requireSixDigitPin(JsonNode payload) {
        String pin = textValue(payload, Constants.PIN);
        if (StringUtils.isNotBlank(pin) && !HashUtil.isHash(pin) && !SIX_DIGIT_PIN.matcher(pin).matches()) {
            throw new CustomException(Constants.FAILED_CONST, Constants.PIN_MUST_BE_SIX_DIGITS,
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** Aborts with a 502 unless auth_service accepted the call; nothing is persisted before it. */
    private void requireAuth(ResponseEntity<Map<String, Object>> authResponse, String userId, String failure) {
        if (authResponse == null || !authResponse.getStatusCode().is2xxSuccessful()) {
            log.error("UserServiceImpl::requireAuth::auth_service returned a non-2xx status: {} for userId: {}",
                    authResponse == null ? "no response" : authResponse.getStatusCode(), userId);
            throw new CustomException(Constants.FAILED_CONST, failure, HttpStatus.BAD_GATEWAY);
        }
    }

    /** Hashes supplied credentials and carries omitted ones forward from the stored record. */
    private JsonNode mergeCredentials(JsonNode incoming, JsonNode stored) {
        if (incoming == null || !incoming.isObject()) {
            return incoming;
        }
        ObjectNode merged = ((ObjectNode) incoming).deepCopy();
        for (String field : Constants.CREDENTIAL_FIELDS) {
            String submitted = incoming.hasNonNull(field) ? incoming.get(field).asText() : null;
            if (StringUtils.isNotBlank(submitted)) {
                merged.put(field, HashUtil.encode(submitted));
            } else if (stored != null && stored.hasNonNull(field)) {
                merged.set(field, stored.get(field));
            } else {
                // Neither supplied nor stored: let validation refuse it.
                merged.remove(field);
            }
        }
        return merged;
    }

    /** The user for a login email, or null; oldest record wins until emails are unique. */
    private UserEntity findUserByEmail(String email) {
        List<UserEntity> matches = userRepository.findByEmail(email);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            log.warn("UserServiceImpl::findUserByEmail:{} records share this email", matches.size());
        }
        return matches.get(0);
    }

    @Override
    public CustomResponse assignUser(JsonNode userEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("UserServiceImpl::read:inside the method");
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        JsonNode auditAfter = null;
        Timestamp auditCreatedOn = null;
        Timestamp auditUpdatedOn = null;
        try {
            String cachedJson = cacheService.getCache(id);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("UserServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<UserEntity> entityOptional = userRepository.findById(id);
                if (entityOptional.isPresent()) {
                    UserEntity userEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(userEntity.getData(),
                            userEntity.getStatus(), userEntity.getCreatedOn(),
                            userEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("UserServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = userEntity.getCreatedOn();
                    auditUpdatedOn = userEntity.getUpdatedOn();
                } else {
                    response.setResponseCode(HttpStatus.NOT_FOUND);
                    response.setMessage(Constants.INVALID_ID);
                }
            }
        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, "error while processing",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // if (auditAfter != null) {
        //     auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "read", null, null, auditAfter,
        //             auditCreatedOn, auditUpdatedOn);
        // }
        return response;
    }

    @Override
    public CustomResponse updateUser(String id, JsonNode userEntity) {
        log.info("UserServiceImpl::updateUser:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("UserServiceImpl::updateUser:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<UserEntity> entityOptional = userRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("UserServiceImpl::updateUser:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            UserEntity userEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(userEntity1.getStatus())) {
                log.warn("UserServiceImpl::updateUser:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Validated after the merge: omitted credentials carry forward from the stored record.
            requireSixDigitPin(userEntity);
            JsonNode userPayload = mergeCredentials(userEntity, userEntity1.getData());
            payloadValidation.validatePayload(Constants.USER_VALIDATION_FILE_JSON, userPayload);
            log.debug("UserServiceImpl::updateUser:validated the payload");

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setData(userPayload);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::updateUser:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(userPayload, userEntity1.getStatus(),
                    userEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticUserJsonPath());
            log.info("UserServiceImpl::updateUser:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("UserServiceImpl::updateUser:refreshed cache for id: {}", id);

            // Best-effort sync to auth_service: only ACTIVE/INACTIVE records have an identity there.
            String status = userEntity1.getStatus();
            if (Constants.ACTIVE.equals(status) || Constants.IN_ACTIVE.equals(status)) {
                try {
                    authUserService.updateAuthUser(
                            textValue(userPayload, Constants.FIRST_NAME),
                            textValue(userPayload, Constants.LAST_NAME),
                            textValue(userPayload, Constants.EMAIL),
                            id,
                            textValue(userPayload, Constants.ORG_ID_RQST),
                            textValue(userPayload, Constants.FUNCTIONAL_ROLE),
                            textValue(userPayload, Constants.ORG_NAME),
                            textValue(userPayload, Constants.DISPLAY_NAME));
                } catch (Exception e) {
                    log.error("UserServiceImpl::updateUser:auth_user_update failed for id: {}", id, e);
                }
            }

            map.put(Constants.USER_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (CustomException e) {
            // Keep the validation 400 rather than re-wrapping it as a 500.
            throw e;
        } catch (Exception e) {
            log.error("UserServiceImpl::updateUser:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("UserServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("UserServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<UserEntity> entityOptional = userRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("UserServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            UserEntity userEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(userEntity.getStatus())) {
                log.warn("UserServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Auth first: once soft-deleted, a retry would never reach auth_service again.
            requireAuth(authUserService.deleteAuthUser(id), id, Constants.AUTH_USER_DELETE_FAILED);

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            userEntity.setStatus(Constants.DELETED);
            userEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            userRepository.save(userEntity);
            log.info("UserServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.USER_INDEX_NAME);
            log.info("UserServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("UserServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         userEntity.getData(), userEntity.getData(),
            //         userEntity.getCreatedOn(), userEntity.getUpdatedOn());
            return response;

        } catch (CustomException e) {
            // Keep the auth 502 rather than re-wrapping it as a 500.
            throw e;
        } catch (Exception e) {
            log.error("UserServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("UserServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.USER_VALIDATION_FILE_JSON,
                this::createUser
        );
    }

    @Override
    public CustomResponse loadFromPrimaryUser() {
        log.info("UserServiceImpl::loadFromPrimaryUser::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.USER_INDEX_NAME,
                vergProperties.getElasticUserJsonPath(),
                userRepository.findAll(),
                UserEntity::getUserId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftUser(JsonNode userEntity) {
        log.info("UserServiceImpl::draftUser:entered the method: " + userEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.USER_VALIDATION_FILE_JSON, userEntity);
        requireSixDigitPin(userEntity);
        log.debug("UserServiceImpl::draftUser:validated the payload (relaxed)");
        try {
            UserEntity userEntity1 = new UserEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.USER_VALIDATION_FILE_JSON);
            userEntity1.setUserId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setCreatedOn(currentTime);
            userEntity1.setUpdatedOn(currentTime);
            userEntity1.setStatus(Constants.DRAFT);
            JsonNode userPayload = HashUtil.hashSecrets(userEntity, Constants.PASSWORD, Constants.PIN);
            userEntity1.setData(userPayload);

            userRepository.save(userEntity1);
            log.info("UserServiceImpl::draftUser::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(userPayload, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticUserJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.USER_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), userEntity,
            //         userEntity1.getCreatedOn(), userEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addUser(String id, JsonNode userEntity) {
        log.info("UserServiceImpl::addUser:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.USER_VALIDATION_FILE_JSON, userEntity);
        requireSixDigitPin(userEntity);
        log.debug("UserServiceImpl::addUser:validated the payload");
        try {
            Optional<UserEntity> entityOptional = userRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            UserEntity userEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(userEntity1.getStatus())) {
                log.warn("UserServiceImpl::addUser:record {} not in DRAFT/REWORK (status={})",
                        id, userEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = userEntity1.getData();
            JsonNode userPayload = HashUtil.hashSecrets(userEntity, Constants.PASSWORD, Constants.PIN);
            userEntity1.setData(userPayload);
            userEntity1.setStatus(Constants.PENDING);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::addUser:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(userPayload, Constants.PENDING,
                    userEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticUserJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.USER_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, userEntity,
            //         userEntity1.getCreatedOn(), userEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveUser(LifecycleRequest request) {
        log.info("UserServiceImpl::approveUser:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewUser(LifecycleRequest request) {
        log.info("UserServiceImpl::reviewUser:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("UserServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<UserEntity> entityOptional = userRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            UserEntity userEntity1 = entityOptional.get();
            String currentStatus = userEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("UserServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            // Auth first, both ways: revoke on deactivation, auth_user_create re-enables.
            JsonNode data = userEntity1.getData();
            boolean deactivating = Constants.IN_ACTIVE.equals(newStatus);
            requireAuth(deactivating
                            ? authUserService.revokeAuthUser(id)
                            : authUserService.createAuthUser(
                                    textValue(data, Constants.FIRST_NAME),
                                    textValue(data, Constants.LAST_NAME),
                                    textValue(data, Constants.EMAIL),
                                    id,
                                    textValue(data, Constants.ORG_ID_RQST),
                                    textValue(data, Constants.FUNCTIONAL_ROLE),
                                    textValue(data, Constants.ORG_NAME),
                                    textValue(data, Constants.DISPLAY_NAME)),
                    id, deactivating ? Constants.AUTH_USER_REVOKE_FAILED : Constants.AUTH_USER_CREATE_FAILED);

            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setStatus(newStatus);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(userEntity1.getData(), newStatus,
                    userEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticUserJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.USER_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         userEntity1.getData(), userEntity1.getData(),
            //         userEntity1.getCreatedOn(), userEntity1.getUpdatedOn());
            return response;
        } catch (CustomException e) {
            // Keep the auth 502 rather than re-wrapping it as a 500.
            throw e;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Shared approve/review transition: checks target and current status, then persists everywhere. */
    private CustomResponse transitionStatus(LifecycleRequest request, String operation,
                                            String requiredCurrentStatus, Set<String> allowedTargets) {
        CustomResponse response = new CustomResponse();
        if (request == null || StringUtils.isEmpty(request.getId())) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        String id = request.getId();
        String targetStatus = LifecycleUtil.normalizeTarget(request.getStatus());
        if (targetStatus == null || !allowedTargets.contains(targetStatus)) {
            log.warn("UserServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<UserEntity> entityOptional = userRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            UserEntity userEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(userEntity1.getStatus())) {
                log.warn("UserServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, userEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setStatus(targetStatus);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(userEntity1.getData(), targetStatus,
                    userEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticUserJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.USER_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         userEntity1.getData(), userEntity1.getData(),
            //         userEntity1.getCreatedOn(), userEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Null-safe read of a string field off the request payload. */
    private String textValue(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field)) {
            return null;
        }
        return payload.get(field).asText();
    }

    /** ES/redis/read projection: payload + status + ISO timestamps (whitelisted in esUserRequiredFields.json). */
    private ObjectNode buildDocument(JsonNode data, String status, Timestamp createdOn, Timestamp updatedOn) {
        ObjectNode node = objectMapper.createObjectNode();
        if (data != null && data.isObject()) {
            node.setAll((ObjectNode) data);
        }
        // Credentials stay in postgres: this projection feeds ES, redis and every response.
        node.remove(Constants.CREDENTIAL_FIELDS);
        node.put(Constants.STATUS, status);
        if (createdOn != null) {
            node.put(Constants.CREATED_ON, createdOn.toInstant().toString());
        }
        if (updatedOn != null) {
            node.put(Constants.UPDATED_ON, updatedOn.toInstant().toString());
        }
        return node;
    }

    public void createSuccessResponse(CustomResponse response) {
        response.setParams(new RespParam());
        response.getParams().setStatus(Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
    }

    public String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = objectMapper.writeValueAsString(requestPayload);
                return JWT.create()
                        .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
                        .sign(Algorithm.HMAC256(Constants.JWT_SECRET_KEY));
            } catch (JsonProcessingException e) {
                // logger.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    public void createErrorResponse(
            CustomResponse response, String errorMessage, HttpStatus httpStatus, String status) {
        response.setParams(new RespParam());
        response.getParams().setStatus(status);
        response.setResponseCode(httpStatus);
    }
}