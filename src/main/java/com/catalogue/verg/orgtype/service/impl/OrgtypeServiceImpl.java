package com.catalogue.verg.orgtype.service.impl;

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
import com.catalogue.verg.core.util.LifecycleUtil;
import com.catalogue.verg.core.util.PayloadValidation;
import com.catalogue.verg.core.util.VergProperties;
// import com.catalogue.verg.core.service.AuditLogService;
import com.catalogue.verg.core.service.ImportService;
import com.catalogue.verg.core.service.LoadFromPrimaryService;
import com.catalogue.verg.core.util.PrimaryKeyUtil;
import com.catalogue.verg.orgtype.entity.OrgtypeEntity;
import com.catalogue.verg.orgtype.repository.OrgtypeRepository;
import com.catalogue.verg.orgtype.service.OrgtypeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
// import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class OrgtypeServiceImpl implements OrgtypeService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private OrgtypeRepository orgtypeRepository;

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

    /** Catalogue name on every audit row; also its key in the lifecycle switches. */
    private static final String AUDIT_ENTITY_NAME = "orgtype";

    private Logger logger = LoggerFactory.getLogger(OrgtypeServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createOrgtype(JsonNode orgtypeEntity) {
        log.info("OrgtypeServiceImpl::createOrgtype:entered the method: " + orgtypeEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.ORGTYPE_VALIDATION_FILE_JSON, orgtypeEntity);

        log.debug("OrgtypeServiceImpl::createOrgtype:validated the payload");
        try {
            log.info("OrgtypeServiceImpl::createOrgtype:creating orgtype");
            OrgtypeEntity orgtypeEntity1 = new OrgtypeEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.ORGTYPE_VALIDATION_FILE_JSON);
            orgtypeEntity1.setOrgtypeId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            orgtypeEntity1.setCreatedOn(currentTime);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeEntity1.setStatus(initialStatus);
            orgtypeEntity1.setData(orgtypeEntity);

            orgtypeRepository.save(orgtypeEntity1);

            log.info("OrgtypeServiceImpl::createOrgtype::persisted orgtype in postgres");
            ObjectNode jsonNode = buildDocument(orgtypeEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticOrgtypeJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.ORGTYPE_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("OrgtypeServiceImpl::createOrgtype::persisted orgtype in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), orgtypeEntity,
            //         orgtypeEntity1.getCreatedOn(), orgtypeEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchOrgtype(SearchCriteria searchCriteria) {
        log.info("OrgtypeServiceImpl::searchOrgtype");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("OrgtypeServiceImpl::searchOrgtype: orgtype search result fetched from redis");
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
                    esUtilService.searchDocuments(Constants.ORGTYPE_INDEX_NAME, searchCriteria);
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
    public CustomResponse assignOrgtype(JsonNode orgtypeEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("OrgtypeServiceImpl::read:inside the method");
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
                log.info("OrgtypeServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
                if (entityOptional.isPresent()) {
                    OrgtypeEntity orgtypeEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(orgtypeEntity.getData(),
                            orgtypeEntity.getStatus(), orgtypeEntity.getCreatedOn(),
                            orgtypeEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("OrgtypeServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = orgtypeEntity.getCreatedOn();
                    auditUpdatedOn = orgtypeEntity.getUpdatedOn();
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
    public CustomResponse updateOrgtype(String id, JsonNode orgtypeEntity) {
        log.info("OrgtypeServiceImpl::updateOrgtype:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("OrgtypeServiceImpl::updateOrgtype:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.ORGTYPE_VALIDATION_FILE_JSON, orgtypeEntity);
        log.debug("OrgtypeServiceImpl::updateOrgtype:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("OrgtypeServiceImpl::updateOrgtype:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            OrgtypeEntity orgtypeEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(orgtypeEntity1.getStatus())) {
                log.warn("OrgtypeServiceImpl::updateOrgtype:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgtypeEntity1.setData(orgtypeEntity);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeRepository.save(orgtypeEntity1);
            log.info("OrgtypeServiceImpl::updateOrgtype:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(orgtypeEntity, orgtypeEntity1.getStatus(),
                    orgtypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgtypeJsonPath());
            log.info("OrgtypeServiceImpl::updateOrgtype:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("OrgtypeServiceImpl::updateOrgtype:refreshed cache for id: {}", id);

            map.put(Constants.ORGTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("OrgtypeServiceImpl::updateOrgtype:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("OrgtypeServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("OrgtypeServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("OrgtypeServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            OrgtypeEntity orgtypeEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(orgtypeEntity.getStatus())) {
                log.warn("OrgtypeServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            orgtypeEntity.setStatus(Constants.DELETED);
            orgtypeEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            orgtypeRepository.save(orgtypeEntity);
            log.info("OrgtypeServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.ORGTYPE_INDEX_NAME);
            log.info("OrgtypeServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("OrgtypeServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         orgtypeEntity.getData(), orgtypeEntity.getData(),
            //         orgtypeEntity.getCreatedOn(), orgtypeEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("OrgtypeServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("OrgtypeServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.ORGTYPE_VALIDATION_FILE_JSON,
                this::createOrgtype
        );
    }

    @Override
    public CustomResponse loadFromPrimaryOrgtype() {
        log.info("OrgtypeServiceImpl::loadFromPrimaryOrgtype::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.ORGTYPE_INDEX_NAME,
                vergProperties.getElasticOrgtypeJsonPath(),
                orgtypeRepository.findAll(),
                OrgtypeEntity::getOrgtypeId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftOrgtype(JsonNode orgtypeEntity) {
        log.info("OrgtypeServiceImpl::draftOrgtype:entered the method: " + orgtypeEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.ORGTYPE_VALIDATION_FILE_JSON, orgtypeEntity);
        log.debug("OrgtypeServiceImpl::draftOrgtype:validated the payload (relaxed)");
        try {
            OrgtypeEntity orgtypeEntity1 = new OrgtypeEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.ORGTYPE_VALIDATION_FILE_JSON);
            orgtypeEntity1.setOrgtypeId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgtypeEntity1.setCreatedOn(currentTime);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeEntity1.setStatus(Constants.DRAFT);
            orgtypeEntity1.setData(orgtypeEntity);

            orgtypeRepository.save(orgtypeEntity1);
            log.info("OrgtypeServiceImpl::draftOrgtype::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(orgtypeEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticOrgtypeJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.ORGTYPE_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), orgtypeEntity,
            //         orgtypeEntity1.getCreatedOn(), orgtypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addOrgtype(String id, JsonNode orgtypeEntity) {
        log.info("OrgtypeServiceImpl::addOrgtype:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.ORGTYPE_VALIDATION_FILE_JSON, orgtypeEntity);
        log.debug("OrgtypeServiceImpl::addOrgtype:validated the payload");
        try {
            Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgtypeEntity orgtypeEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(orgtypeEntity1.getStatus())) {
                log.warn("OrgtypeServiceImpl::addOrgtype:record {} not in DRAFT/REWORK (status={})",
                        id, orgtypeEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = orgtypeEntity1.getData();
            orgtypeEntity1.setData(orgtypeEntity);
            orgtypeEntity1.setStatus(Constants.PENDING);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeRepository.save(orgtypeEntity1);
            log.info("OrgtypeServiceImpl::addOrgtype:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(orgtypeEntity, Constants.PENDING,
                    orgtypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgtypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORGTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, orgtypeEntity,
            //         orgtypeEntity1.getCreatedOn(), orgtypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveOrgtype(LifecycleRequest request) {
        log.info("OrgtypeServiceImpl::approveOrgtype:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewOrgtype(LifecycleRequest request) {
        log.info("OrgtypeServiceImpl::reviewOrgtype:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("OrgtypeServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgtypeEntity orgtypeEntity1 = entityOptional.get();
            String currentStatus = orgtypeEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("OrgtypeServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgtypeEntity1.setStatus(newStatus);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeRepository.save(orgtypeEntity1);
            log.info("OrgtypeServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(orgtypeEntity1.getData(), newStatus,
                    orgtypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgtypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORGTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         orgtypeEntity1.getData(), orgtypeEntity1.getData(),
            //         orgtypeEntity1.getCreatedOn(), orgtypeEntity1.getUpdatedOn());
            return response;
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
            log.warn("OrgtypeServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<OrgtypeEntity> entityOptional = orgtypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgtypeEntity orgtypeEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(orgtypeEntity1.getStatus())) {
                log.warn("OrgtypeServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, orgtypeEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgtypeEntity1.setStatus(targetStatus);
            orgtypeEntity1.setUpdatedOn(currentTime);
            orgtypeRepository.save(orgtypeEntity1);
            log.info("OrgtypeServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(orgtypeEntity1.getData(), targetStatus,
                    orgtypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORGTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgtypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORGTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         orgtypeEntity1.getData(), orgtypeEntity1.getData(),
            //         orgtypeEntity1.getCreatedOn(), orgtypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** ES/redis/read projection: payload + status + ISO timestamps (whitelisted in esOrgtypeRequiredFields.json). */
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