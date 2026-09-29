package com.catalogue.verg.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import org.springframework.web.multipart.MultipartFile;


public interface UserService {

    CustomResponse createUser(JsonNode userEntity);

    CustomResponse updateUser(String id, JsonNode userEntity);

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    CustomResponse draftUser(JsonNode userEntity);

    // Lifecycle: (re-)submit a DRAFT/REWORK record for approval -> PENDING (full validation)
    CustomResponse addUser(String id, JsonNode userEntity);

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    CustomResponse approveUser(LifecycleRequest request);

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK | PENDING
    CustomResponse reviewUser(LifecycleRequest request);

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status)
    CustomResponse toggleStatus(String id);

    // Verifies {email, password}: registered email, matching BCrypt hash, ACTIVE record
    CustomResponse verifyUser(JsonNode verifyRequest);

    CustomResponse searchUser(SearchCriteria searchCriteria);

    CustomResponse assignUser(JsonNode userEntity, String token);

    CustomResponse read(String id);

    CustomResponse delete(String id);

    CustomResponse importData(MultipartFile file);

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    CustomResponse loadFromPrimaryUser();
}