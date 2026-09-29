package com.catalogue.verg.core.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body for approve/review: the record id and the requested target status. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleRequest {
    private String id;
    private String status;
}
