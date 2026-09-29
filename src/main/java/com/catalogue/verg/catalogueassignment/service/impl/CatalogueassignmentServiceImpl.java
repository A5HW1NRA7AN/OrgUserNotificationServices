package com.catalogue.verg.catalogueassignment.service.impl;

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
import com.catalogue.verg.catalogueassignment.entity.CatalogueassignmentEntity;
import com.catalogue.verg.catalogueassignment.repository.CatalogueassignmentRepository;
import com.catalogue.verg.catalogueassignment.service.CatalogueassignmentService;
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
public class CatalogueassignmentServiceImpl implements CatalogueassignmentService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private CatalogueassignmentRepository catalogueassignmentRepository;

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
    private static final String AUDIT_ENTITY_NAME = "catalogueassignment";

    private Logger logger = LoggerFactory.getLogger(CatalogueassignmentServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createCatalogueassignment(JsonNode catalogueassignmentEntity) {
        log.info("CatalogueassignmentServiceImpl::createCatalogueassignment:entered the method: " + catalogueassignmentEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON, catalogueassignmentEntity);

        log.debug("CatalogueassignmentServiceImpl::createCatalogueassignment:validated the payload");
        try {
            log.info("CatalogueassignmentServiceImpl::createCatalogueassignment:creating catalogueassignment");
            CatalogueassignmentEntity catalogueassignmentEntity1 = new CatalogueassignmentEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON);
            catalogueassignmentEntity1.setCatalogueassignmentId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            catalogueassignmentEntity1.setCreatedOn(currentTime);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentEntity1.setStatus(initialStatus);
            catalogueassignmentEntity1.setData(catalogueassignmentEntity);

            catalogueassignmentRepository.save(catalogueassignmentEntity1);

            log.info("CatalogueassignmentServiceImpl::createCatalogueassignment::persisted catalogueassignment in postgres");
            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticCatalogueassignmentJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("CatalogueassignmentServiceImpl::createCatalogueassignment::persisted catalogueassignment in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), catalogueassignmentEntity,
            //         catalogueassignmentEntity1.getCreatedOn(), catalogueassignmentEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchCatalogueassignment(SearchCriteria searchCriteria) {
        log.info("CatalogueassignmentServiceImpl::searchCatalogueassignment");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("CatalogueassignmentServiceImpl::searchCatalogueassignment: catalogueassignment search result fetched from redis");
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
                    esUtilService.searchDocuments(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, searchCriteria);
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
    public CustomResponse assignCatalogueassignment(JsonNode catalogueassignmentEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("CatalogueassignmentServiceImpl::read:inside the method");
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
                log.info("CatalogueassignmentServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
                if (entityOptional.isPresent()) {
                    CatalogueassignmentEntity catalogueassignmentEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(catalogueassignmentEntity.getData(),
                            catalogueassignmentEntity.getStatus(), catalogueassignmentEntity.getCreatedOn(),
                            catalogueassignmentEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("CatalogueassignmentServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = catalogueassignmentEntity.getCreatedOn();
                    auditUpdatedOn = catalogueassignmentEntity.getUpdatedOn();
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
    public CustomResponse updateCatalogueassignment(String id, JsonNode catalogueassignmentEntity) {
        log.info("CatalogueassignmentServiceImpl::updateCatalogueassignment:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("CatalogueassignmentServiceImpl::updateCatalogueassignment:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON, catalogueassignmentEntity);
        log.debug("CatalogueassignmentServiceImpl::updateCatalogueassignment:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("CatalogueassignmentServiceImpl::updateCatalogueassignment:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            CatalogueassignmentEntity catalogueassignmentEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(catalogueassignmentEntity1.getStatus())) {
                log.warn("CatalogueassignmentServiceImpl::updateCatalogueassignment:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueassignmentEntity1.setData(catalogueassignmentEntity);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentRepository.save(catalogueassignmentEntity1);
            log.info("CatalogueassignmentServiceImpl::updateCatalogueassignment:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity, catalogueassignmentEntity1.getStatus(),
                    catalogueassignmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueassignmentJsonPath());
            log.info("CatalogueassignmentServiceImpl::updateCatalogueassignment:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("CatalogueassignmentServiceImpl::updateCatalogueassignment:refreshed cache for id: {}", id);

            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("CatalogueassignmentServiceImpl::updateCatalogueassignment:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("CatalogueassignmentServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("CatalogueassignmentServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("CatalogueassignmentServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            CatalogueassignmentEntity catalogueassignmentEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(catalogueassignmentEntity.getStatus())) {
                log.warn("CatalogueassignmentServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            catalogueassignmentEntity.setStatus(Constants.DELETED);
            catalogueassignmentEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            catalogueassignmentRepository.save(catalogueassignmentEntity);
            log.info("CatalogueassignmentServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.CATALOGUEASSIGNMENT_INDEX_NAME);
            log.info("CatalogueassignmentServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("CatalogueassignmentServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         catalogueassignmentEntity.getData(), catalogueassignmentEntity.getData(),
            //         catalogueassignmentEntity.getCreatedOn(), catalogueassignmentEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("CatalogueassignmentServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("CatalogueassignmentServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON,
                this::createCatalogueassignment
        );
    }

    @Override
    public CustomResponse loadFromPrimaryCatalogueassignment() {
        log.info("CatalogueassignmentServiceImpl::loadFromPrimaryCatalogueassignment::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.CATALOGUEASSIGNMENT_INDEX_NAME,
                vergProperties.getElasticCatalogueassignmentJsonPath(),
                catalogueassignmentRepository.findAll(),
                CatalogueassignmentEntity::getCatalogueassignmentId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftCatalogueassignment(JsonNode catalogueassignmentEntity) {
        log.info("CatalogueassignmentServiceImpl::draftCatalogueassignment:entered the method: " + catalogueassignmentEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON, catalogueassignmentEntity);
        log.debug("CatalogueassignmentServiceImpl::draftCatalogueassignment:validated the payload (relaxed)");
        try {
            CatalogueassignmentEntity catalogueassignmentEntity1 = new CatalogueassignmentEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON);
            catalogueassignmentEntity1.setCatalogueassignmentId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueassignmentEntity1.setCreatedOn(currentTime);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentEntity1.setStatus(Constants.DRAFT);
            catalogueassignmentEntity1.setData(catalogueassignmentEntity);

            catalogueassignmentRepository.save(catalogueassignmentEntity1);
            log.info("CatalogueassignmentServiceImpl::draftCatalogueassignment::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticCatalogueassignmentJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), catalogueassignmentEntity,
            //         catalogueassignmentEntity1.getCreatedOn(), catalogueassignmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addCatalogueassignment(String id, JsonNode catalogueassignmentEntity) {
        log.info("CatalogueassignmentServiceImpl::addCatalogueassignment:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.CATALOGUEASSIGNMENT_VALIDATION_FILE_JSON, catalogueassignmentEntity);
        log.debug("CatalogueassignmentServiceImpl::addCatalogueassignment:validated the payload");
        try {
            Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueassignmentEntity catalogueassignmentEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(catalogueassignmentEntity1.getStatus())) {
                log.warn("CatalogueassignmentServiceImpl::addCatalogueassignment:record {} not in DRAFT/REWORK (status={})",
                        id, catalogueassignmentEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = catalogueassignmentEntity1.getData();
            catalogueassignmentEntity1.setData(catalogueassignmentEntity);
            catalogueassignmentEntity1.setStatus(Constants.PENDING);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentRepository.save(catalogueassignmentEntity1);
            log.info("CatalogueassignmentServiceImpl::addCatalogueassignment:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity, Constants.PENDING,
                    catalogueassignmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueassignmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, catalogueassignmentEntity,
            //         catalogueassignmentEntity1.getCreatedOn(), catalogueassignmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveCatalogueassignment(LifecycleRequest request) {
        log.info("CatalogueassignmentServiceImpl::approveCatalogueassignment:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewCatalogueassignment(LifecycleRequest request) {
        log.info("CatalogueassignmentServiceImpl::reviewCatalogueassignment:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("CatalogueassignmentServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueassignmentEntity catalogueassignmentEntity1 = entityOptional.get();
            String currentStatus = catalogueassignmentEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("CatalogueassignmentServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueassignmentEntity1.setStatus(newStatus);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentRepository.save(catalogueassignmentEntity1);
            log.info("CatalogueassignmentServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity1.getData(), newStatus,
                    catalogueassignmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueassignmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         catalogueassignmentEntity1.getData(), catalogueassignmentEntity1.getData(),
            //         catalogueassignmentEntity1.getCreatedOn(), catalogueassignmentEntity1.getUpdatedOn());
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
            log.warn("CatalogueassignmentServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<CatalogueassignmentEntity> entityOptional = catalogueassignmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueassignmentEntity catalogueassignmentEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(catalogueassignmentEntity1.getStatus())) {
                log.warn("CatalogueassignmentServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, catalogueassignmentEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueassignmentEntity1.setStatus(targetStatus);
            catalogueassignmentEntity1.setUpdatedOn(currentTime);
            catalogueassignmentRepository.save(catalogueassignmentEntity1);
            log.info("CatalogueassignmentServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(catalogueassignmentEntity1.getData(), targetStatus,
                    catalogueassignmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUEASSIGNMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueassignmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUEASSIGNMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         catalogueassignmentEntity1.getData(), catalogueassignmentEntity1.getData(),
            //         catalogueassignmentEntity1.getCreatedOn(), catalogueassignmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** ES/redis/read projection: payload + status + ISO timestamps (whitelisted in esCatalogueassignmentRequiredFields.json). */
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