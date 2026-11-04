package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private AuditEventRepository repository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        when(repository.insert(any())).thenReturn(1L);
        service = new AuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loginFailureStoresAttemptedUsernameWithoutActor() {
        service.recordLoginFailure("alice", "BAD_CREDENTIALS");

        AuditEvent event = captured();
        assertThat(event.actorId()).isNull();
        assertThat(event.attemptedUsername()).isEqualTo("alice");
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_FAILURE);
        assertThat(event.result()).isEqualTo(AuditResult.FAILURE);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.details()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    void loginSuccessRecordsActorAndTarget() {
        service.recordLoginSuccess(7L);

        AuditEvent event = captured();
        assertThat(event.actorId()).isEqualTo(7L);
        assertThat(event.attemptedUsername()).isNull();
        assertThat(event.targetType()).isEqualTo("USER");
        assertThat(event.targetId()).isEqualTo("7");
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test
    void accessDeniedUsesDeniedResult() {
        service.recordAccessDenied(null, "POST /api/auth/logout", "CSRF_INVALID");

        AuditEvent event = captured();
        assertThat(event.result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.targetType()).isEqualTo("ENDPOINT");
        assertThat(event.targetId()).isEqualTo("POST /api/auth/logout");
    }

    @Test
    void longTargetIsBounded() {
        service.recordAccessDenied(null, "GET /" + "a".repeat(1000), "FORBIDDEN");

        assertThat(captured().targetId()).hasSize(AuditService.MAX_TEXT_LENGTH);
    }

    @Test
    void sanitizeTruncatesTo64Characters() {
        assertThat(AuditService.sanitizeAttemptedUsername("u".repeat(100))).hasSize(64);
    }

    @Test
    void sanitizeStripsControlCharactersAndWhitespace() {
        assertThat(AuditService.sanitizeAttemptedUsername("  bob\r\n\t ")).isEqualTo("bob");
        assertThat(AuditService.sanitizeAttemptedUsername("ev\u0000il")).isEqualTo("evil");
    }

    @Test
    void sanitizeReturnsNullForMissingOrBlank() {
        assertThat(AuditService.sanitizeAttemptedUsername(null)).isNull();
        assertThat(AuditService.sanitizeAttemptedUsername("   ")).isNull();
    }

    private AuditEvent captured() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }
}
