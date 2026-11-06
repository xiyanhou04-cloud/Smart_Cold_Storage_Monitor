package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void defaultsToFirstPageOf50() {
        assertThat(PageQuery.of(null, null)).isEqualTo(new PageQuery(0, 50));
    }

    @Test
    void capsSizeAt200() {
        assertThat(PageQuery.of(0, 1000).size()).isEqualTo(200);
        assertThat(PageQuery.of(0, 200).size()).isEqualTo(200);
    }

    @Test
    void rejectsNegativePageAndNonPositiveSize() {
        assertThatThrownBy(() -> PageQuery.of(-1, 10))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.getField()).isEqualTo("page"));
        assertThatThrownBy(() -> PageQuery.of(0, 0))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.getField()).isEqualTo("size"));
    }

    @Test
    void offsetDoesNotOverflow() {
        assertThat(PageQuery.of(Integer.MAX_VALUE, 200).offset()).isEqualTo((long) Integer.MAX_VALUE * 200);
    }

    @Test
    void totalPagesRoundsUp() {
        assertThat(new PagedResult<>(java.util.List.of(), 0, 2, 5).totalPages()).isEqualTo(3);
        assertThat(new PagedResult<>(java.util.List.of(), 0, 2, 0).totalPages()).isZero();
    }
}
