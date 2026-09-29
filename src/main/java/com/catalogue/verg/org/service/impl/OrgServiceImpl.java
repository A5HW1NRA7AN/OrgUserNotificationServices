package com.catalogue.verg.org.service.impl;

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
import com.catalogue.verg.org.entity.OrgEntity;
import com.catalogue.verg.org.repository.OrgRepository;
import com.catalogue.verg.org.service.OrgService;
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
public class OrgServiceImpl implements OrgService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private OrgRepository orgRepository;

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
    private static final String AUDIT_ENTITY_NAME = "org";

    private Logger logger = LoggerFactory.getLogger(OrgServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createOrg(JsonNode orgEntity) {
        log.info("OrgServiceImpl::createOrg:entered the method: " + orgEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.ORG_VALIDATION_FILE_JSON, orgEntity);

        log.debug("OrgServiceImpl::createOrg:validated the payload");
        try {
            log.info("OrgServiceImpl::createOrg:creating org");
            OrgEntity orgEntity1 = new OrgEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.ORG_VALIDATION_FILE_JSON);
            orgEntity1.setOrgId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            orgEntity1.setCreatedOn(currentTime);
            orgEntity1.setUpdatedOn(currentTime);
            orgEntity1.setStatus(initialStatus);
            orgEntity1.setData(orgEntity);

            orgRepository.save(orgEntity1);

            log.info("OrgServiceImpl::createOrg::persisted org in postgres");
            ObjectNode jsonNode = buildDocument(orgEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticOrgJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.ORG_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("OrgServiceImpl::createOrg::persisted org in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), orgEntity,
            //         orgEntity1.getCreatedOn(), orgEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchOrg(SearchCriteria searchCriteria) {
        log.info("OrgServiceImpl::searchOrg");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("OrgServiceImpl::searchOrg: org search result fetched from redis");
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
                    esUtilService.searchDocuments(Constants.ORG_INDEX_NAME, searchCriteria);
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
    public CustomResponse assignOrg(JsonNode orgEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("OrgServiceImpl::read:inside the method");
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
                log.info("OrgServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<OrgEntity> entityOptional = orgRepository.findById(id);
                if (entityOptional.isPresent()) {
                    OrgEntity orgEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(orgEntity.getData(),
                            orgEntity.getStatus(), orgEntity.getCreatedOn(),
                            orgEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("OrgServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = orgEntity.getCreatedOn();
                    auditUpdatedOn = orgEntity.getUpdatedOn();
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
    public CustomResponse updateOrg(String id, JsonNode orgEntity) {
        log.info("OrgServiceImpl::updateOrg:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("OrgServiceImpl::updateOrg:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.ORG_VALIDATION_FILE_JSON, orgEntity);
        log.debug("OrgServiceImpl::updateOrg:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<OrgEntity> entityOptional = orgRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("OrgServiceImpl::updateOrg:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            OrgEntity orgEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(orgEntity1.getStatus())) {
                log.warn("OrgServiceImpl::updateOrg:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgEntity1.setData(orgEntity);
            orgEntity1.setUpdatedOn(currentTime);
            orgRepository.save(orgEntity1);
            log.info("OrgServiceImpl::updateOrg:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(orgEntity, orgEntity1.getStatus(),
                    orgEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgJsonPath());
            log.info("OrgServiceImpl::updateOrg:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("OrgServiceImpl::updateOrg:refreshed cache for id: {}", id);

            map.put(Constants.ORG_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("OrgServiceImpl::updateOrg:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("OrgServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("OrgServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<OrgEntity> entityOptional = orgRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("OrgServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            OrgEntity orgEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(orgEntity.getStatus())) {
                log.warn("OrgServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            orgEntity.setStatus(Constants.DELETED);
            orgEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            orgRepository.save(orgEntity);
            log.info("OrgServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.ORG_INDEX_NAME);
            log.info("OrgServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("OrgServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         orgEntity.getData(), orgEntity.getData(),
            //         orgEntity.getCreatedOn(), orgEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("OrgServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("OrgServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.ORG_VALIDATION_FILE_JSON,
                this::createOrg
        );
    }

    @Override
    public CustomResponse loadFromPrimaryOrg() {
        log.info("OrgServiceImpl::loadFromPrimaryOrg::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.ORG_INDEX_NAME,
                vergProperties.getElasticOrgJsonPath(),
                orgRepository.findAll(),
                OrgEntity::getOrgId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftOrg(JsonNode orgEntity) {
        log.info("OrgServiceImpl::draftOrg:entered the method: " + orgEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.ORG_VALIDATION_FILE_JSON, orgEntity);
        log.debug("OrgServiceImpl::draftOrg:validated the payload (relaxed)");
        try {
            OrgEntity orgEntity1 = new OrgEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.ORG_VALIDATION_FILE_JSON);
            orgEntity1.setOrgId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgEntity1.setCreatedOn(currentTime);
            orgEntity1.setUpdatedOn(currentTime);
            orgEntity1.setStatus(Constants.DRAFT);
            orgEntity1.setData(orgEntity);

            orgRepository.save(orgEntity1);
            log.info("OrgServiceImpl::draftOrg::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(orgEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticOrgJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.ORG_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), orgEntity,
            //         orgEntity1.getCreatedOn(), orgEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addOrg(String id, JsonNode orgEntity) {
        log.info("OrgServiceImpl::addOrg:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.ORG_VALIDATION_FILE_JSON, orgEntity);
        log.debug("OrgServiceImpl::addOrg:validated the payload");
        try {
            Optional<OrgEntity> entityOptional = orgRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgEntity orgEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(orgEntity1.getStatus())) {
                log.warn("OrgServiceImpl::addOrg:record {} not in DRAFT/REWORK (status={})",
                        id, orgEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = orgEntity1.getData();
            orgEntity1.setData(orgEntity);
            orgEntity1.setStatus(Constants.PENDING);
            orgEntity1.setUpdatedOn(currentTime);
            orgRepository.save(orgEntity1);
            log.info("OrgServiceImpl::addOrg:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(orgEntity, Constants.PENDING,
                    orgEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORG_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, orgEntity,
            //         orgEntity1.getCreatedOn(), orgEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveOrg(LifecycleRequest request) {
        log.info("OrgServiceImpl::approveOrg:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewOrg(LifecycleRequest request) {
        log.info("OrgServiceImpl::reviewOrg:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("OrgServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<OrgEntity> entityOptional = orgRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgEntity orgEntity1 = entityOptional.get();
            String currentStatus = orgEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("OrgServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgEntity1.setStatus(newStatus);
            orgEntity1.setUpdatedOn(currentTime);
            orgRepository.save(orgEntity1);
            log.info("OrgServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(orgEntity1.getData(), newStatus,
                    orgEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORG_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         orgEntity1.getData(), orgEntity1.getData(),
            //         orgEntity1.getCreatedOn(), orgEntity1.getUpdatedOn());
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
            log.warn("OrgServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<OrgEntity> entityOptional = orgRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            OrgEntity orgEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(orgEntity1.getStatus())) {
                log.warn("OrgServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, orgEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            orgEntity1.setStatus(targetStatus);
            orgEntity1.setUpdatedOn(currentTime);
            orgRepository.save(orgEntity1);
            log.info("OrgServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(orgEntity1.getData(), targetStatus,
                    orgEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.ORG_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticOrgJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.ORG_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         orgEntity1.getData(), orgEntity1.getData(),
            //         orgEntity1.getCreatedOn(), orgEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** ES/redis/read projection: payload + status + ISO timestamps (whitelisted in esOrgRequiredFields.json). */
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