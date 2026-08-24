package com.catalogue.verg.core.util;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class VergProperties {

        @Value("${spring.redis.cacheTtl}")
        private long searchResultRedisTtl;

        @Value("${search.string.max.regex.length}")
        private int searchStringMaxRegexLength;
        @Value("${elastic.required.field.user.json.path}")
        private String elasticUserJsonPath;
    
        @Value("${elastic.required.field.org.json.path}")
        private String elasticOrgJsonPath;
        @Value("${elastic.required.field.catalogue.json.path}")
        private String elasticCatalogueJsonPath;
    
        @Value("${elastic.required.field.catalogueassignment.json.path}")
        private String elasticCatalogueassignmentJsonPath;
    
        @Value("${elastic.required.field.departmenttype.json.path}")
        private String elasticDepartmenttypeJsonPath;
    }