package com.catalogue.verg.catalogue.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.config.LifecyclePolicy;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.catalogue.service.CatalogueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/catalogue")
public class CatalogueController {
    @Autowired
    private CatalogueService catalogueService;

    @Autowired
    private LifecyclePolicy lifecyclePolicy;

    /** Key this catalogue is looked up by in the lifecycle switches. */
    private static final String CATALOGUE_NAME = "catalogue";

    //@PostMapping("/v1/create")
    public ResponseEntity<CustomResponse> create(@RequestBody JsonNode catalogueDetails) {
        CustomResponse response = catalogueService.createCatalogue(catalogueDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    @PostMapping("/v1/draft")
    public ResponseEntity<CustomResponse> draft(@RequestBody JsonNode catalogueDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = catalogueService.draftCatalogue(catalogueDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Creates a record (full validation): PENDING with the lifecycle on, ACTIVE with it off.
    @PostMapping("/v1/add")
    public ResponseEntity<CustomResponse> add(@RequestBody JsonNode catalogueDetails) {
        CustomResponse response = catalogueService.createCatalogue(catalogueDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: (re-)submit an existing DRAFT/REWORK record for approval (PENDING, full validation)
    @PutMapping("/v1/add/{id}")
    public ResponseEntity<CustomResponse> addById(@PathVariable String id, @RequestBody JsonNode catalogueDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = catalogueService.addCatalogue(id, catalogueDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    @PutMapping("/v1/approve")
    public ResponseEntity<CustomResponse> approve(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = catalogueService.approveCatalogue(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK
    @PutMapping("/v1/review")
    public ResponseEntity<CustomResponse> review(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = catalogueService.reviewCatalogue(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // ACTIVE <-> INACTIVE only; not lifecycle-gated, as with it off this is the only way offline.
    @PutMapping("/v1/toggle/{id}")
    public ResponseEntity<CustomResponse> toggle(@PathVariable String id) {
        CustomResponse response = catalogueService.toggleStatus(id);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/v1/search")
    public ResponseEntity<?> search(@RequestBody SearchCriteria searchCriteria) {
        CustomResponse response = catalogueService.searchCatalogue(searchCriteria);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/v1/read/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        CustomResponse response = catalogueService.read(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/v1/update/{id}")
    public ResponseEntity<CustomResponse> update(@PathVariable String id, @RequestBody JsonNode catalogueDetails) {
        CustomResponse response = catalogueService.updateCatalogue(id, catalogueDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @DeleteMapping("/v1/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        CustomResponse response = catalogueService.delete(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/v1/import")
    public ResponseEntity<CustomResponse> importData(@RequestParam("file") MultipartFile file) {
        CustomResponse response = catalogueService.importData(file);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    @PostMapping("/v1/loadFromPrimary")
    public ResponseEntity<CustomResponse> loadFromPrimary() {
        CustomResponse response = catalogueService.loadFromPrimaryCatalogue();
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}