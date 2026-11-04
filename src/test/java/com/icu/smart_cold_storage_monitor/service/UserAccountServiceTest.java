package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final User ADMIN = new User(1L, "admin", "h", Role.ADMIN, true, NOW);
    private static final User OTHER_ADMIN = new User(2L, "admin2", "h", Role.ADMIN, true, NOW);
    private static final User OPERATOR = new User(3L, "op", "h", Role.OPERATOR, true, NOW);
    private static final User DISABLED_OPERATOR = new User(4L, "gone", "h", Role.OPERATOR, false, NOW);

    private UserRepository users;
    private AuditService audit;
    private PasswordEncoder encoder;
    private PasswordGenerator generator;
    private UserSessions sessions;
    private UserAccountService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        encoder = new BCryptPasswordEncoder(4); // low cost keeps the unit test fast
        generator = mock(PasswordGenerator.class);
        when(generator.generate()).thenReturn("generated-password-123");
        sessions = mock(UserSessions.class);
        service = new UserAccountService(users, audit, encoder, generator, sessions, Clock.fixed(NOW, ZoneOffset.UTC));
        for (User u : new User[] {ADMIN, OTHER_ADMIN, OPERATOR, DISABLED_OPERATOR}) {
            when(users.findById(u.id())).thenReturn(Optional.of(u));
        }
    }

    // ---- createUser --------------------------------------------------------------------------------------------

    @Test
    void createUserWithSuppliedPasswordStoresHashAndDoesNotEchoPassword() {
        when(users.insert(eq("alice"), anyString(), eq(Role.OPERATOR), eq(true), eq(NOW))).thenReturn(5L);
        when(users.findById(5L)).thenReturn(Optional.of(new User(5L, "alice", "hash", Role.OPERATOR, true, NOW)));

        AccountCredentials created = service.createUser("alice", "s3cret-pass", Role.OPERATOR, ADMIN.id());

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).insert(eq("alice"), hash.capture(), eq(Role.OPERATOR), eq(true), eq(NOW));
        assertThat(encoder.matches("s3cret-pass", hash.getValue())).isTrue();
        verify(audit).recordUserCreated(eq(ADMIN.id()), eq(5L), contains("role=OPERATOR"));
        assertThat(created.passwordGenerated()).isFalse();
        assertThat(created.generatedPassword()).isNull();
    }

    @Test
    void createUserWithoutPasswordGeneratesOne() {
        when(users.insert(eq("bob"), anyString(), eq(Role.ADMIN), eq(true), eq(NOW))).thenReturn(6L);
        when(users.findById(6L)).thenReturn(Optional.of(new User(6L, "bob", "hash", Role.ADMIN, true, NOW)));

        AccountCredentials created = service.createUser("bob", null, Role.ADMIN, ADMIN.id());

        assertThat(created.generatedPassword()).isEqualTo("generated-password-123");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).insert(eq("bob"), hash.capture(), eq(Role.ADMIN), eq(true), eq(NOW));
        assertThat(encoder.matches("generated-password-123", hash.getValue())).isTrue();
    }

    @Test
    void createUserRequiresActiveAdministratorActor() {
        assertThatThrownBy(() -> service.createUser("carol", "s3cret-pass", Role.OPERATOR, OPERATOR.id()))
                .isInstanceOf(OperationNotPermittedException.class);
        verify(users, never()).insert(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void createUserRequiresRole() {
        assertThatThrownBy(() -> service.createUser("carol", "s3cret-pass", null, ADMIN.id()))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void duplicateUsernameIsRejectedWithoutInsertOrAudit() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser("alice", "s3cret-pass", Role.OPERATOR, null))
                .isInstanceOf(UserAccountService.DuplicateUsernameException.class)
                .isInstanceOf(ConflictException.class);

        verify(users, never()).insert(anyString(), anyString(), any(), anyBoolean(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailurePropagatesSoTransactionRollsBack() {
        when(users.insert(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(5L);
        RuntimeException failure = new IllegalStateException("audit down");
        doThrow(failure).when(audit).recordUserCreated(isNull(), anyLong(), anyString());

        assertThatThrownBy(() -> service.createUser("alice", "s3cret-pass", Role.ADMIN, null)).isSameAs(failure);
    }

    // ---- resetPassword -----------------------------------------------------------------------------------------

    @Test
    void adminResetsOperatorPasswordAndEndsAllOperatorSessions() {
        AccountCredentials result = service.resetPassword(ADMIN.id(), OPERATOR.id(), "new-password-1", "admin-session");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).updatePasswordHash(eq(OPERATOR.id()), hash.capture());
        assertThat(encoder.matches("new-password-1", hash.getValue())).isTrue();
        verify(audit).recordPasswordReset(eq(ADMIN.id()), eq(OPERATOR.id()), contains("self=false"));
        verify(sessions).expireAll(OPERATOR.id(), null);
        assertThat(result.generatedPassword()).isNull();
    }

    @Test
    void adminResetsOwnPasswordAndKeepsCurrentSession() {
        AccountCredentials result = service.resetPassword(ADMIN.id(), ADMIN.id(), null, "admin-session");

        assertThat(result.generatedPassword()).isEqualTo("generated-password-123");
        verify(audit).recordPasswordReset(eq(ADMIN.id()), eq(ADMIN.id()), contains("self=true"));
        verify(sessions).expireAll(ADMIN.id(), "admin-session");
    }

    @Test
    void adminCannotResetAnotherAdministratorsPassword() {
        assertThatThrownBy(() -> service.resetPassword(ADMIN.id(), OTHER_ADMIN.id(), "new-password-1", "s"))
                .isInstanceOf(OperationNotPermittedException.class);
        verify(users, never()).updatePasswordHash(anyLong(), anyString());
        verifyNoInteractions(audit, sessions);
    }

    @Test
    void operatorCannotResetPasswords() {
        assertThatThrownBy(() -> service.resetPassword(OPERATOR.id(), OPERATOR.id(), "new-password-1", "s"))
                .isInstanceOf(OperationNotPermittedException.class);
    }

    @Test
    void resetOfUnknownUserIsNotFound() {
        assertThatThrownBy(() -> service.resetPassword(ADMIN.id(), 99L, "new-password-1", "s"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resetOfDeactivatedAccountIsConflict() {
        assertThatThrownBy(() -> service.resetPassword(ADMIN.id(), DISABLED_OPERATOR.id(), "new-password-1", "s"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resetRejectsTooShortPassword() {
        assertThatThrownBy(() -> service.resetPassword(ADMIN.id(), OPERATOR.id(), "short", "s"))
                .isInstanceOf(InvalidInputException.class);
        verify(users, never()).updatePasswordHash(anyLong(), anyString());
    }

    // ---- deactivateUser ----------------------------------------------------------------------------------------

    @Test
    void adminDeactivatesOperatorAndEndsSessions() {
        service.deactivateUser(ADMIN.id(), OPERATOR.id());

        verify(users).updateEnabled(OPERATOR.id(), false);
        verify(audit).recordUserDeactivated(eq(ADMIN.id()), eq(OPERATOR.id()), anyString());
        verify(sessions).expireAll(OPERATOR.id(), null);
    }

    @Test
    void deactivatingAlreadyDeactivatedOperatorIsIdempotent() {
        User result = service.deactivateUser(ADMIN.id(), DISABLED_OPERATOR.id());

        assertThat(result.enabled()).isFalse();
        verify(users, never()).updateEnabled(anyLong(), anyBoolean());
        verifyNoInteractions(audit, sessions);
    }

    @Test
    void administratorsCannotBeDeactivated() {
        assertThatThrownBy(() -> service.deactivateUser(ADMIN.id(), OTHER_ADMIN.id()))
                .isInstanceOf(OperationNotPermittedException.class);
        assertThatThrownBy(() -> service.deactivateUser(ADMIN.id(), ADMIN.id()))
                .isInstanceOf(OperationNotPermittedException.class);
        verify(users, never()).updateEnabled(anyLong(), anyBoolean());
    }

    // ---- validation --------------------------------------------------------------------------------------------

    @Test
    void usernameRules() {
        UserAccountService.validateUsername("ok_user-1.a");
        assertThatThrownBy(() -> UserAccountService.validateUsername(null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> UserAccountService.validateUsername("ab")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> UserAccountService.validateUsername("a".repeat(65)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> UserAccountService.validateUsername("has space"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void passwordRules() {
        UserAccountService.validatePassword("12345678");
        UserAccountService.validatePassword("p".repeat(72));
        assertThatThrownBy(() -> UserAccountService.validatePassword("1234567"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> UserAccountService.validatePassword("p".repeat(73)))
                .isInstanceOf(InvalidInputException.class);
        // 25 three-byte characters = 75 bytes, over BCrypt's 72-byte limit.
        assertThatThrownBy(() -> UserAccountService.validatePassword("密".repeat(25)))
                .isInstanceOf(InvalidInputException.class);
    }
}
