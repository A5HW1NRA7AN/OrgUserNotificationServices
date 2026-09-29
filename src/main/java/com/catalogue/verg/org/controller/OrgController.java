package com.catalogue.verg.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.config.LifecyclePolicy;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.org.service.OrgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/org")
public class OrgController {
    @Autowired
    private OrgService orgService;

    @Autowired
    private LifecyclePolicy lifecyclePolicy;

    /** Key this catalogue is looked up by in the lifecycle switches. */
    private static final String CATALOGUE_NAME = "org";

    //@PostMapping("/v1/create")
    public ResponseEntity<CustomResponse> create(@RequestBody JsonNode orgDetails) {
        CustomResponse response = orgService.createOrg(orgDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    @PostMapping("/v1/draft")
    public ResponseEntity<CustomResponse> draft(@RequestBody JsonNode orgDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = orgService.draftOrg(orgDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Creates a record (full validation): PENDING with the lifecycle on, ACTIVE with it off.
    @PostMapping("/v1/add")
    public ResponseEntity<CustomResponse> add(@RequestBody JsonNode orgDetails) {
        CustomResponse response = orgService.createOrg(orgDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: (re-)submit an existing DRAFT/REWORK record for approval (PENDING, full validation)
    @PutMapping("/v1/add/{id}")
    public ResponseEntity<CustomResponse> addById(@PathVariable String id, @RequestBody JsonNode orgDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = orgService.addOrg(id, orgDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    @PutMapping("/v1/approve")
    public ResponseEntity<CustomResponse> approve(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = orgService.approveOrg(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK
    @PutMapping("/v1/review")
    public ResponseEntity<CustomResponse> review(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = orgService.reviewOrg(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // ACTIVE <-> INACTIVE only; not lifecycle-gated, as with it off this is the only way offline.
    @PutMapping("/v1/toggle/{id}")
    public ResponseEntity<CustomResponse> toggle(@PathVariable String id) {
        CustomResponse response = orgService.toggleStatus(id);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/v1/search")
    public ResponseEntity<?> search(@RequestBody SearchCriteria searchCriteria) {
        CustomResponse response = orgService.searchOrg(searchCriteria);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/v1/read/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        CustomResponse response = orgService.read(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/v1/update/{id}")
    public ResponseEntity<CustomResponse> update(@PathVariable String id, @RequestBody JsonNode orgDetails) {
        CustomResponse response = orgService.updateOrg(id, orgDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @DeleteMapping("/v1/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        CustomResponse response = orgService.delete(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/v1/import")
    public ResponseEntity<CustomResponse> importData(@RequestParam("file") MultipartFile file) {
        CustomResponse response = orgService.importData(file);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    @PostMapping("/v1/loadFromPrimary")
    public ResponseEntity<CustomResponse> loadFromPrimary() {
        CustomResponse response = orgService.loadFromPrimaryOrg();
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}