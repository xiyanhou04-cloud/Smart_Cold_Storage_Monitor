package com.icu.smart_cold_storage_monitor.service;

import java.util.List;
import java.util.function.Function;

/** One page of a history query together with the total number of matching records. */
public record PagedResult<T>(List<T> items, int page, int size, long totalItems) {

    public PagedResult {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalItems == 0 ? 0 : (totalItems + size - 1) / size;
    }

    public <R> PagedResult<R> map(Function<? super T, ? extends R> mapper) {
        return new PagedResult<>(items.stream().<R>map(mapper).toList(), page, size, totalItems);
    }
}
