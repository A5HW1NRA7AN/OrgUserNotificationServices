package com.catalogue.verg.notification.service;

import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.NotificationTemplateFilterRequest;
import com.catalogue.verg.core.dto.NotificationTemplateRequest;
import com.catalogue.verg.notification.entity.NotificationTemplate;
import com.catalogue.verg.notification.repository.NotificationTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationTemplateService {

    private final NotificationTemplateRepository repository;

    public NotificationTemplateService(
            NotificationTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CustomResponse createTemplate(
            NotificationTemplateRequest request) {

        CustomResponse response = new CustomResponse();

        boolean exists =
                repository.existsByTemplateCodeAndIsDeletedFalse(
                        request.getTemplateCode()
                );

        if (exists) {
            response.setMessage(
                    "Notification template already exists for template code"
            );
            response.setResponseCode(HttpStatus.CONFLICT);

            return response;
        }

        NotificationTemplate template = new NotificationTemplate();

        template.setTemplateModule(
                request.getTemplateModule()
        );

        template.setTemplateCode(
                request.getTemplateCode()
        );

        template.setTemplateName(
                request.getTemplateName()
        );

        template.setNotificationChannel(
                request.getNotificationChannel()
        );

        template.setDescription(
                request.getDescription()
        );

        template.setSubject(
                request.getSubject()
        );

        template.setTemplateContent(
                request.getTemplateContent()
        );

        template.setTemplateVariables(
                request.getTemplateVariables()
        );

        template.setStatus(
                request.getStatus()
        );
        template.setReceiver(
                request.getReceiver()
        );

        template.setIsEnabled(true);
        template.setIsDeleted(false);

        LocalDateTime now = LocalDateTime.now();

        template.setCreatedAt(now);
        template.setUpdatedAt(now);

        NotificationTemplate savedTemplate =
                repository.save(template);

        response.setMessage(
                "Notification template created successfully"
        );

        response.setResponseCode(
                HttpStatus.CREATED
        );

        response.getResult().put(
                "notificationTemplate",
                savedTemplate
        );

        return response;
    }

    @Transactional
    public CustomResponse updateTemplate(
            Long id,
            NotificationTemplateRequest request) {

        CustomResponse response = new CustomResponse();

        NotificationTemplate template =
                repository.findByIdAndIsDeletedFalse(id)
                        .orElse(null);

        if (template == null) {
            response.setMessage(
                    "Notification template not found"
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );

            return response;
        }

        // Is the requested template code held by another active template?
        boolean exists =
                repository.existsByTemplateCodeAndIsDeletedFalse(
                        request.getTemplateCode()
                );

        if (exists
                && !template.getTemplateCode()
                .equals(request.getTemplateCode())) {

            response.setMessage(
                    "Notification template already exists for template code"
            );
            response.setResponseCode(
                    HttpStatus.CONFLICT
            );

            return response;
        }

        template.setTemplateModule(
                request.getTemplateModule()
        );

        template.setTemplateCode(
                request.getTemplateCode()
        );

        template.setTemplateName(
                request.getTemplateName()
        );

        template.setNotificationChannel(
                request.getNotificationChannel()
        );

        template.setSubject(
                request.getSubject()
        );

        template.setTemplateContent(
                request.getTemplateContent()
        );

        template.setTemplateVariables(
                request.getTemplateVariables()
        );

        template.setDescription(
                request.getDescription()
        );

        template.setUpdatedAt(
                LocalDateTime.now()
        );

        template.setStatus(
                request.getStatus()
        );

        template.setReceiver(
                request.getReceiver()
        );

        NotificationTemplate updatedTemplate =
                repository.save(template);

        response.setMessage(
                "Notification template updated successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        response.getResult().put(
                "notificationTemplate",
                updatedTemplate
        );

        return response;
    }

    @Transactional
    public CustomResponse deleteTemplate(Long id) {

        CustomResponse response = new CustomResponse();

        NotificationTemplate template =
                repository.findByIdAndIsDeletedFalse(id)
                        .orElse(null);

        if (template == null) {
            response.setMessage(
                    "Notification template not found"
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );

            return response;
        }

        template.setIsDeleted(true);
        template.setIsEnabled(false);
        template.setUpdatedAt(
                LocalDateTime.now()
        );

        repository.save(template);

        response.setMessage(
                "Notification template deleted successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getTemplateById(Long id) {

        CustomResponse response = new CustomResponse();

        NotificationTemplate template =
                repository.findByIdAndIsDeletedFalse(id)
                        .orElse(null);

        if (template == null) {
            response.setMessage(
                    "Notification template not found"
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );

            return response;
        }

        response.setMessage(
                "Notification template fetched successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        response.getResult().put(
                "notificationTemplate",
                template
        );

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getTemplates(
            int page,
            int size,
            String search) {

        CustomResponse response = new CustomResponse();

        if (page < 0) {
            response.setMessage("Page cannot be less than 0");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (size <= 0) {
            response.setMessage("Page size must be greater than 0");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.DESC,
                        "createdAt"
                )
        );

        Page<NotificationTemplate> templatePage;

        if (search == null || search.trim().isEmpty()) {

            templatePage =
                    repository.findByIsDeletedFalse(
                            pageable
                    );

        } else {

            templatePage =
                    repository.searchTemplates(
                            search.trim(),
                            pageable
                    );
        }

        response.setMessage(
                "Notification templates fetched successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        response.getResult().put(
                "templates",
                templatePage.getContent()
        );

        response.getResult().put(
                "currentPage",
                templatePage.getNumber()
        );

        response.getResult().put(
                "pageSize",
                templatePage.getSize()
        );

        response.getResult().put(
                "totalElements",
                templatePage.getTotalElements()
        );

        response.getResult().put(
                "totalPages",
                templatePage.getTotalPages()
        );

        response.getResult().put(
                "first",
                templatePage.isFirst()
        );

        response.getResult().put(
                "last",
                templatePage.isLast()
        );

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getTemplatesAdmin(
            NotificationTemplateFilterRequest request) {

        CustomResponse response = new CustomResponse();

        int page = request.getPage();
        int size = request.getSize();

        // Validate pagination
        if (page < 0) {
            response.setMessage("Page cannot be less than 0");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (size <= 0) {
            response.setMessage("Page size must be greater than 0");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.DESC,
                        "createdAt"
                )
        );

        Page<NotificationTemplate> templatePage;

        String search = request.getSearch();
        String status = request.getStatus();
        String module = request.getModule();
        String receiver = request.getReceiver();

        // No filters
        if ((search == null || search.trim().isEmpty())
                && (status == null || status.equalsIgnoreCase("All"))
                && (module == null || module.equalsIgnoreCase("All"))
                && (receiver == null || receiver.equalsIgnoreCase("All"))) {

            templatePage =
                    repository.findByIsDeletedFalse(pageable);

        } else {

            // Apply filters
            templatePage =
                    repository.searchTemplatesAdmin(
                            search != null ? search.trim() : "",
                            status,
                            module,
                            receiver,
                            pageable
                    );
        }

        response.setMessage(
                "Notification templates fetched successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        response.getResult().put(
                "templates",
                templatePage.getContent()
        );

        response.getResult().put(
                "currentPage",
                templatePage.getNumber()
        );

        response.getResult().put(
                "pageSize",
                templatePage.getSize()
        );

        response.getResult().put(
                "totalElements",
                templatePage.getTotalElements()
        );

        response.getResult().put(
                "totalPages",
                templatePage.getTotalPages()
        );

        response.getResult().put(
                "first",
                templatePage.isFirst()
        );

        response.getResult().put(
                "last",
                templatePage.isLast()
        );

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getModules() {

        CustomResponse response = new CustomResponse();

        List<String> modules = repository.findDistinctModules();

        response.setMessage("Modules fetched successfully");
        response.setResponseCode(HttpStatus.OK);

        response.getResult().put("modules", modules);

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getStatuses() {

        CustomResponse response = new CustomResponse();

        List<String> statuses = repository.findDistinctStatuses();

        response.setMessage("Statuses fetched successfully");
        response.setResponseCode(HttpStatus.OK);

        response.getResult().put("statuses", statuses);

        return response;
    }

    @Transactional(readOnly = true)
    public CustomResponse getReceivers() {

        CustomResponse response = new CustomResponse();

        List<String> receivers = repository.findDistinctReceivers();

        response.setMessage("Receivers fetched successfully");
        response.setResponseCode(HttpStatus.OK);

        response.getResult().put("receivers", receivers);

        return response;
    }
}