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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
// import java.util.UUID;
import java.util.concurrent.TimeUnit;


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

    /**
     * Catalogue name recorded on every audit row emitted by this service. Doubles as the key
     * this catalogue is looked up by in the lifecycle switches ({@link LifecyclePolicy}).
     */
    private static final String AUDIT_ENTITY_NAME = "user";

    /**
     * Login-identifier uniqueness. Declared here rather than in {@code Constants} because that
     * file is owned by the {@code main.py} generator, which rewrites it per catalogue.
     */
    private static final String PHONE_NUMBER = "phoneNumber";
    private static final String EMAIL_ALREADY_REGISTERED = "Email already registered";
    private static final String PHONE_ALREADY_REGISTERED = "Phone number already registered";

    /**
     * BCrypt output is self-describing — {@code $2a$<cost>$<22-char salt><31-char hash>} — which
     * lets {@link #hashRawSecrets} tell a plaintext secret from one already hashed by an earlier
     * write.
     */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createUser(JsonNode userEntity) {
        log.info("UserServiceImpl::createUser:entered the method: " + userEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.USER_VALIDATION_FILE_JSON, userEntity);

        log.debug("UserServiceImpl::createUser:validated the payload");

        // Canonicalise the login identifiers, then reject the payload if another live record
        // already holds them. Both run before the auth_service call below, so a duplicate is
        // never published to Keycloak first, and outside the try so the 409 is not re-wrapped
        // as a generic 500.
        JsonNode userRequest = normaliseIdentifiers(userEntity);
        ensureIdentifiersUnique(userRequest, null);

        // Generate Primary Key up front: auth_service is told the userId this catalogue will use.
        String primaryID = primaryKeyUtil.generateKey(Constants.USER_VALIDATION_FILE_JSON);

        // The auth identity is only created when the record goes live on create, i.e. when the
        // lifecycle is off for this catalogue (catalogue.lifecycle.entities.user=false). With the
        // lifecycle on, the record starts PENDING and has no business existing in auth_service yet.
        if (!lifecyclePolicy.isEnabledFor(AUDIT_ENTITY_NAME)) {
            // auth_service owns the identity: nothing is persisted here unless it accepts the user.
            // Kept outside the try below so a rejection is not re-wrapped as a generic 500.
            ResponseEntity<Map<String, Object>> authResponse = authUserService.createAuthUser(
                    textValue(userRequest, Constants.FIRST_NAME),
                    textValue(userRequest, Constants.LAST_NAME),
                    textValue(userRequest, Constants.EMAIL),
                    primaryID,
                    textValue(userRequest, Constants.ORG_ID_RQST),
                    textValue(userRequest, Constants.ENTITY_TYPE));
            if (authResponse == null || !authResponse.getStatusCode().is2xxSuccessful()) {
                log.error("UserServiceImpl::createUser::auth_service returned a non-2xx status: {}",
                        authResponse == null ? "no response" : authResponse.getStatusCode());
                throw new CustomException(Constants.FAILED_CONST, Constants.AUTH_USER_CREATE_FAILED,
                        HttpStatus.BAD_GATEWAY);
            }
            log.info("UserServiceImpl::createUser::auth user created, proceeding to persist userId: {}", primaryID);
        } 
        // Hash the credentials before they reach postgres, ES or redis; raw values are not stored.
        JsonNode userPayload = HashUtil.hashSecrets(userRequest, Constants.PASSWORD, Constants.PIN);

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
        // Normalised for the lookup below: emails are stored lower-cased, and the ES keyword
        // filter is case-sensitive, so the raw input would only match if the caller reproduced
        // the exact casing used at registration.
        String email = normaliseEmail(textValue(verifyRequest, Constants.EMAIL));
        String password = textValue(verifyRequest, Constants.PASSWORD);

        if (StringUtils.isEmpty(email) || StringUtils.isEmpty(password)) {
            log.warn("UserServiceImpl::verifyUser:email or password missing on the payload");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.EMAIL_PASSWORD_REQUIRED);
            return response;
        }

        try {
            // 1. Does the email exist? Exact match against the indexed record.
            JsonNode userDocument = findIndexedUserByEmail(email);
            if (userDocument == null) {
                log.warn("UserServiceImpl::verifyUser:no indexed user found for the given email");
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }

            // 2. Does the plaintext password match the stored hash? BCrypt salts per call, so the
            // stored value can only be checked with matches() — never by re-hashing and comparing.
            if (!HashUtil.matches(password, textValue(userDocument, Constants.PASSWORD))) {
                log.warn("UserServiceImpl::verifyUser:password mismatch for the given email");
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                response.setMessage(Constants.INVALID_CREDENTIALS);
                return response;
            }

            // 3. Is the record live? DRAFT / PENDING / INACTIVE / DELETED must not verify.
            String status = textValue(userDocument, Constants.STATUS);
            if (!Constants.ACTIVE.equals(status)) {
                log.warn("UserServiceImpl::verifyUser:user is {}, not ACTIVE", status);
                response.setResponseCode(HttpStatus.FORBIDDEN);
                response.setMessage(Constants.USER_NOT_ACTIVE);
                return response;
            }

            log.info("UserServiceImpl::verifyUser:credentials verified for userId: {}",
                    textValue(userDocument, Constants.ID));
            response.getResult().put(Constants.USER_ID_RQST, textValue(userDocument, Constants.ID));
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

   
    private JsonNode findIndexedUserByEmail(String email) throws Exception {
        SearchCriteria searchCriteria = new SearchCriteria();
        HashMap<String, Object> filterCriteriaMap = new HashMap<>();
        filterCriteriaMap.put(Constants.EMAIL, email);
        searchCriteria.setFilterCriteriaMap(filterCriteriaMap);
        searchCriteria.setRequestedFields(
                List.of(Constants.EMAIL, Constants.PASSWORD, Constants.STATUS));
        searchCriteria.setPageNumber(0);
        searchCriteria.setPageSize(1);

        SearchResult searchResult =
                esUtilService.searchDocuments(Constants.USER_INDEX_NAME, searchCriteria);
        JsonNode data = searchResult == null ? null : searchResult.getData();
        if (data == null || !data.isArray() || data.size() == 0) {
            return null;
        }
        return data.get(0);
    }

    /**
     * Returns a copy of the payload with any plaintext credentials replaced by their BCrypt hash,
     * so raw values never reach postgres, Elasticsearch or Redis. {@link #createUser} hashes via
     * {@link HashUtil#hashSecrets}; the update / draft / promote paths use this instead because
     * they can receive a payload that already carries hashed credentials — {@code read} echoes
     * them, so a client doing read-then-write would round-trip a hash back in. Re-hashing that
     * value would lock the user out, since the stored hash would no longer correspond to any
     * password they know. Already-hashed fields are therefore left alone.
     */
    private JsonNode hashRawSecrets(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        ObjectNode copy = payload.deepCopy();
        for (String field : List.of(Constants.PASSWORD, Constants.PIN)) {
            String value = textValue(copy, field);
            if (StringUtils.isEmpty(value) || BCRYPT_HASH.matcher(value).matches()) {
                continue;
            }
            copy.put(field, HashUtil.encode(value));
        }
        return copy;
    }

    /**
     * Canonical form of an email address: trimmed and lower-cased. Applied on every write and on
     * the verify lookup, so a user who registered as {@code A.User@x.org} can sign in whatever
     * casing they type. Also what makes the uniqueness check below meaningful — without it, a
     * duplicate is admitted just by changing one letter's case, and Keycloak (which compares
     * addresses case-insensitively) would then reject the record this catalogue accepted.
     */
    private String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a copy of the payload with the login identifiers canonicalised — email trimmed and
     * lower-cased, phone number trimmed. Blank or absent fields are left as they are, so relaxed
     * (draft) payloads pass through untouched.
     */
    private JsonNode normaliseIdentifiers(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        ObjectNode normalised = payload.deepCopy();
        String email = textValue(normalised, Constants.EMAIL);
        if (!StringUtils.isEmpty(email)) {
            normalised.put(Constants.EMAIL, normaliseEmail(email));
        }
        String phoneNumber = textValue(normalised, PHONE_NUMBER);
        if (!StringUtils.isEmpty(phoneNumber)) {
            normalised.put(PHONE_NUMBER, phoneNumber.trim());
        }
        return normalised;
    }

    /**
     * Rejects the payload with a 409 when its email or phone number is already held by another
     * live record. Must be called with the normalised payload.
     *
     * <p>The catalogue owns this check rather than the auth layer: email is what {@link #verifyUser}
     * resolves a user by, so without uniqueness that lookup is undefined — it returns whichever
     * document Elasticsearch scored first, and with it the userId that ends up in the issued
     * token. auth_service holds no user table and sees one publish at a time, and Keycloak's own
     * duplicate rejection never fires for a DRAFT or PENDING user that has not reached it yet.
     *
     * <p>{@code user_index} is the right thing to check against because it is exactly the set of
     * live records: every write path indexes, and {@link #delete} removes the document — so a
     * soft-deleted user releases its email and phone for re-registration, matching what
     * auth_user_delete does in Keycloak. INACTIVE records stay indexed and keep holding theirs.
     *
     * @param selfUserId record being updated, whose own values must not count as a collision;
     *                   null on create.
     */
    private void ensureIdentifiersUnique(JsonNode payload, String selfUserId) {
        if (payload == null || !payload.isObject()) {
            return;
        }
        ensureFieldUnique(Constants.EMAIL, textValue(payload, Constants.EMAIL), selfUserId,
                EMAIL_ALREADY_REGISTERED);
        ensureFieldUnique(PHONE_NUMBER, textValue(payload, PHONE_NUMBER), selfUserId,
                PHONE_ALREADY_REGISTERED);
    }

    private void ensureFieldUnique(String field, String value, String selfUserId, String message) {
        if (StringUtils.isEmpty(value)) {
            // Relaxed draft payloads may omit the field entirely; nothing to collide with yet.
            return;
        }
        String holderId;
        try {
            holderId = findIdentifierHolder(field, value, selfUserId);
        } catch (Exception e) {
            log.error("UserServiceImpl::ensureFieldUnique:lookup failed for field: {}", field, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // Thrown outside the try so the 409 is not caught above and re-wrapped as a generic 500.
        if (holderId != null) {
            log.warn("UserServiceImpl::ensureFieldUnique:{} already held by userId: {}",
                    field, holderId);
            throw new CustomException(Constants.FAILED_CONST, message, HttpStatus.CONFLICT);
        }
    }

    /** userId of a live record already holding the value, or null when the value is free. */
    private String findIdentifierHolder(String field, String value, String selfUserId)
            throws Exception {
        SearchCriteria searchCriteria = new SearchCriteria();
        HashMap<String, Object> filterCriteriaMap = new HashMap<>();
        filterCriteriaMap.put(field, value);
        searchCriteria.setFilterCriteriaMap(filterCriteriaMap);
        searchCriteria.setRequestedFields(List.of(field));
        searchCriteria.setPageNumber(0);
        // Two hits, not one: on update the record's own document is a legitimate match, and a
        // second holder behind it must still be visible.
        searchCriteria.setPageSize(2);

        SearchResult searchResult =
                esUtilService.searchDocuments(Constants.USER_INDEX_NAME, searchCriteria);
        JsonNode data = searchResult == null ? null : searchResult.getData();
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode hit : data) {
            String hitId = textValue(hit, Constants.ID);
            if (!StringUtils.isEmpty(hitId) && !hitId.equals(selfUserId)) {
                return hitId;
            }
        }
        return null;
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

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.USER_VALIDATION_FILE_JSON, userEntity);
        log.debug("UserServiceImpl::updateUser:validated the payload");

        // Enforced on update as well as create: otherwise a duplicate is reached simply by
        // editing an existing record's email onto one already in use. The record's own values
        // are excluded, so a no-op update of the same email is not a collision with itself.
        JsonNode userRequest = hashRawSecrets(normaliseIdentifiers(userEntity));
        ensureIdentifiersUnique(userRequest, id);

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

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setData(userRequest);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::updateUser:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(userRequest, userEntity1.getStatus(),
                    userEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.USER_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticUserJsonPath());
            log.info("UserServiceImpl::updateUser:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("UserServiceImpl::updateUser:refreshed cache for id: {}", id);

            map.put(Constants.USER_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

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
        log.debug("UserServiceImpl::draftUser:validated the payload (relaxed)");

        // Drafts are indexed, so they hold their identifiers and must be checked too — a draft
        // is the one case Keycloak cannot catch, since it never reaches Keycloak. Relaxed
        // validation allows the fields to be absent, which the check treats as nothing to claim.
        JsonNode userRequest = hashRawSecrets(normaliseIdentifiers(userEntity));
        ensureIdentifiersUnique(userRequest, null);

        try {
            UserEntity userEntity1 = new UserEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.USER_VALIDATION_FILE_JSON);
            userEntity1.setUserId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            userEntity1.setCreatedOn(currentTime);
            userEntity1.setUpdatedOn(currentTime);
            userEntity1.setStatus(Constants.DRAFT);
            userEntity1.setData(userRequest);

            userRepository.save(userEntity1);
            log.info("UserServiceImpl::draftUser::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(userRequest, Constants.DRAFT, currentTime, currentTime);
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
        log.debug("UserServiceImpl::addUser:validated the payload");

        // The promotion carries a full payload that may differ from the draft, so the
        // identifiers are re-checked here rather than trusted from draftUser.
        JsonNode userRequest = hashRawSecrets(normaliseIdentifiers(userEntity));
        ensureIdentifiersUnique(userRequest, id);

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
            userEntity1.setData(userRequest);
            userEntity1.setStatus(Constants.PENDING);
            userEntity1.setUpdatedOn(currentTime);
            userRepository.save(userEntity1);
            log.info("UserServiceImpl::addUser:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(userRequest, Constants.PENDING,
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
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Shared status-transition logic for approve/review. Validates the id and requested target status,
     * enforces the required current status, then persists the new status to Postgres, ES and Redis.
     */
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

    /**
     * Null-safe read of a string field off the request payload.
     */
    private String textValue(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field)) {
            return null;
        }
        return payload.get(field).asText();
    }

    /**
     * Builds the projection stored in Elasticsearch and Redis (and returned by read): the payload
     * plus the lifecycle status and the Postgres createdOn/updatedOn timestamps (ISO-8601). ES keeps
     * only whitelisted keys, so status/createdOn/updatedOn must be present in esUserRequiredFields.json.
     */
    private ObjectNode buildDocument(JsonNode data, String status, Timestamp createdOn, Timestamp updatedOn) {
        ObjectNode node = objectMapper.createObjectNode();
        if (data != null && data.isObject()) {
            node.setAll((ObjectNode) data);
        }
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