package com.alejandro.mtostock.application.dto.common;

import java.util.List;

/**
 * Generic response DTO for paginated API results.
 */
public record PageResponse<T>(
        List<T> content,
        PageMetadataResponse page
) {
}