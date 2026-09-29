package com.catalogue.verg.catalogue.service.impl;

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
import com.catalogue.verg.catalogue.entity.CatalogueEntity;
import com.catalogue.verg.catalogue.repository.CatalogueRepository;
import com.catalogue.verg.catalogue.service.CatalogueService;
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
public class CatalogueServiceImpl implements CatalogueService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private CatalogueRepository catalogueRepository;

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
    private static final String AUDIT_ENTITY_NAME = "catalogue";

    private Logger logger = LoggerFactory.getLogger(CatalogueServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createCatalogue(JsonNode catalogueEntity) {
        log.info("CatalogueServiceImpl::createCatalogue:entered the method: " + catalogueEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.CATALOGUE_VALIDATION_FILE_JSON, catalogueEntity);

        log.debug("CatalogueServiceImpl::createCatalogue:validated the payload");
        try {
            log.info("CatalogueServiceImpl::createCatalogue:creating catalogue");
            CatalogueEntity catalogueEntity1 = new CatalogueEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.CATALOGUE_VALIDATION_FILE_JSON);
            catalogueEntity1.setCatalogueId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            catalogueEntity1.setCreatedOn(currentTime);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueEntity1.setStatus(initialStatus);
            catalogueEntity1.setData(catalogueEntity);

            catalogueRepository.save(catalogueEntity1);

            log.info("CatalogueServiceImpl::createCatalogue::persisted catalogue in postgres");
            ObjectNode jsonNode = buildDocument(catalogueEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticCatalogueJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.CATALOGUE_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("CatalogueServiceImpl::createCatalogue::persisted catalogue in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), catalogueEntity,
            //         catalogueEntity1.getCreatedOn(), catalogueEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchCatalogue(SearchCriteria searchCriteria) {
        log.info("CatalogueServiceImpl::searchCatalogue");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("CatalogueServiceImpl::searchCatalogue: catalogue search result fetched from redis");
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
                    esUtilService.searchDocuments(Constants.CATALOGUE_INDEX_NAME, searchCriteria);
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
    public CustomResponse assignCatalogue(JsonNode catalogueEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("CatalogueServiceImpl::read:inside the method");
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
                log.info("CatalogueServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
                if (entityOptional.isPresent()) {
                    CatalogueEntity catalogueEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(catalogueEntity.getData(),
                            catalogueEntity.getStatus(), catalogueEntity.getCreatedOn(),
                            catalogueEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("CatalogueServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = catalogueEntity.getCreatedOn();
                    auditUpdatedOn = catalogueEntity.getUpdatedOn();
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
    public CustomResponse updateCatalogue(String id, JsonNode catalogueEntity) {
        log.info("CatalogueServiceImpl::updateCatalogue:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("CatalogueServiceImpl::updateCatalogue:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.CATALOGUE_VALIDATION_FILE_JSON, catalogueEntity);
        log.debug("CatalogueServiceImpl::updateCatalogue:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("CatalogueServiceImpl::updateCatalogue:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            CatalogueEntity catalogueEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(catalogueEntity1.getStatus())) {
                log.warn("CatalogueServiceImpl::updateCatalogue:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueEntity1.setData(catalogueEntity);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueRepository.save(catalogueEntity1);
            log.info("CatalogueServiceImpl::updateCatalogue:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(catalogueEntity, catalogueEntity1.getStatus(),
                    catalogueEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueJsonPath());
            log.info("CatalogueServiceImpl::updateCatalogue:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("CatalogueServiceImpl::updateCatalogue:refreshed cache for id: {}", id);

            map.put(Constants.CATALOGUE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("CatalogueServiceImpl::updateCatalogue:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("CatalogueServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("CatalogueServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("CatalogueServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            CatalogueEntity catalogueEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(catalogueEntity.getStatus())) {
                log.warn("CatalogueServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            catalogueEntity.setStatus(Constants.DELETED);
            catalogueEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            catalogueRepository.save(catalogueEntity);
            log.info("CatalogueServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.CATALOGUE_INDEX_NAME);
            log.info("CatalogueServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("CatalogueServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         catalogueEntity.getData(), catalogueEntity.getData(),
            //         catalogueEntity.getCreatedOn(), catalogueEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("CatalogueServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("CatalogueServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.CATALOGUE_VALIDATION_FILE_JSON,
                this::createCatalogue
        );
    }

    @Override
    public CustomResponse loadFromPrimaryCatalogue() {
        log.info("CatalogueServiceImpl::loadFromPrimaryCatalogue::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.CATALOGUE_INDEX_NAME,
                vergProperties.getElasticCatalogueJsonPath(),
                catalogueRepository.findAll(),
                CatalogueEntity::getCatalogueId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftCatalogue(JsonNode catalogueEntity) {
        log.info("CatalogueServiceImpl::draftCatalogue:entered the method: " + catalogueEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.CATALOGUE_VALIDATION_FILE_JSON, catalogueEntity);
        log.debug("CatalogueServiceImpl::draftCatalogue:validated the payload (relaxed)");
        try {
            CatalogueEntity catalogueEntity1 = new CatalogueEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.CATALOGUE_VALIDATION_FILE_JSON);
            catalogueEntity1.setCatalogueId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueEntity1.setCreatedOn(currentTime);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueEntity1.setStatus(Constants.DRAFT);
            catalogueEntity1.setData(catalogueEntity);

            catalogueRepository.save(catalogueEntity1);
            log.info("CatalogueServiceImpl::draftCatalogue::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(catalogueEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticCatalogueJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.CATALOGUE_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), catalogueEntity,
            //         catalogueEntity1.getCreatedOn(), catalogueEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addCatalogue(String id, JsonNode catalogueEntity) {
        log.info("CatalogueServiceImpl::addCatalogue:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.CATALOGUE_VALIDATION_FILE_JSON, catalogueEntity);
        log.debug("CatalogueServiceImpl::addCatalogue:validated the payload");
        try {
            Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueEntity catalogueEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(catalogueEntity1.getStatus())) {
                log.warn("CatalogueServiceImpl::addCatalogue:record {} not in DRAFT/REWORK (status={})",
                        id, catalogueEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = catalogueEntity1.getData();
            catalogueEntity1.setData(catalogueEntity);
            catalogueEntity1.setStatus(Constants.PENDING);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueRepository.save(catalogueEntity1);
            log.info("CatalogueServiceImpl::addCatalogue:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(catalogueEntity, Constants.PENDING,
                    catalogueEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, catalogueEntity,
            //         catalogueEntity1.getCreatedOn(), catalogueEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveCatalogue(LifecycleRequest request) {
        log.info("CatalogueServiceImpl::approveCatalogue:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewCatalogue(LifecycleRequest request) {
        log.info("CatalogueServiceImpl::reviewCatalogue:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("CatalogueServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueEntity catalogueEntity1 = entityOptional.get();
            String currentStatus = catalogueEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("CatalogueServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueEntity1.setStatus(newStatus);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueRepository.save(catalogueEntity1);
            log.info("CatalogueServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(catalogueEntity1.getData(), newStatus,
                    catalogueEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         catalogueEntity1.getData(), catalogueEntity1.getData(),
            //         catalogueEntity1.getCreatedOn(), catalogueEntity1.getUpdatedOn());
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
            log.warn("CatalogueServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<CatalogueEntity> entityOptional = catalogueRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            CatalogueEntity catalogueEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(catalogueEntity1.getStatus())) {
                log.warn("CatalogueServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, catalogueEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            catalogueEntity1.setStatus(targetStatus);
            catalogueEntity1.setUpdatedOn(currentTime);
            catalogueRepository.save(catalogueEntity1);
            log.info("CatalogueServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(catalogueEntity1.getData(), targetStatus,
                    catalogueEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.CATALOGUE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticCatalogueJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.CATALOGUE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         catalogueEntity1.getData(), catalogueEntity1.getData(),
            //         catalogueEntity1.getCreatedOn(), catalogueEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** ES/redis/read projection: payload + status + ISO timestamps (whitelisted in esCatalogueRequiredFields.json). */
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