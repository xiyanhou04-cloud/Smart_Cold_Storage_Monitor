package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;

/**
 * Zero-based page request shared by history endpoints (design doc §5: default 50 records, page size capped at 200).
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /**
     * Applies defaults to missing values and caps {@code size} at {@link #MAX_SIZE}.
     *
     * @throws InvalidInputException if {@code page} is negative or {@code size} is less than 1
     */
    public static PageQuery of(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new InvalidInputException("page", "Page must be 0 or greater.");
        }
        if (s < 1) {
            throw new InvalidInputException("size", "Size must be at least 1.");
        }
        return new PageQuery(p, Math.min(s, MAX_SIZE));
    }

    /** Rows to skip; a {@code long} so very large page numbers cannot overflow. */
    public long offset() {
        return (long) page * size;
    }
}
