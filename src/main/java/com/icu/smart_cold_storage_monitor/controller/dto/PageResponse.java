package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.PagedResult;
import java.util.List;
import java.util.function.Function;

/** Envelope for paged history responses; {@code page} is zero-based and {@code size} is the applied page size. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, long totalPages) {

    public static <S, T> PageResponse<T> from(PagedResult<S> result, Function<? super S, ? extends T> mapper) {
        PagedResult<T> mapped = result.map(mapper);
        return new PageResponse<>(mapped.items(), mapped.page(), mapped.size(), mapped.totalItems(),
                mapped.totalPages());
    }
}
