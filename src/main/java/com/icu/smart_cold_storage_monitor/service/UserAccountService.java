package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business rules for user accounts.
 *
 * <ul>
 *   <li>Administrators create OPERATOR or ADMIN accounts; a missing password is generated.</li>
 *   <li>Administrators reset their own password and operators' passwords, never another administrator's.</li>
 *   <li>Administrators deactivate operator accounts; administrator accounts cannot be deactivated.</li>
 * </ul>
 *
 * Every change and its audit event are written in one transaction, so an audit failure rejects the change.
 * Accounts are deactivated rather than deleted so that audit records keep their actor references.
 */
@Service
public class UserAccountService {

    static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes of input; longer passwords are rejected instead of silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final PasswordGenerator passwordGenerator;
    private final UserSessions sessions;
    private final Clock clock;

    public UserAccountService(UserRepository users, AuditService audit, PasswordEncoder passwordEncoder,
                              PasswordGenerator passwordGenerator, UserSessions sessions, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.passwordGenerator = passwordGenerator;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** All accounts ordered by id. */
    public List<User> listUsers() {
        return users.findAll();
    }

    /**
     * Creates an enabled account with a BCrypt-hashed password.
     *
     * @param rawPassword the password, or {@code null}/empty to generate one
     * @param actorId     the administrator creating the account, or {@code null} for first-run setup
     * @return the account and, if it was generated, the plain password (shown to the administrator once)
     * @throws InvalidInputException          if the username or supplied password breaks the account rules
     * @throws DuplicateUsernameException     if the username is taken
     * @throws OperationNotPermittedException if the actor is not an active administrator
     */
    @Transactional
    public AccountCredentials createUser(String username, String rawPassword, Role role, Long actorId) {
        if (role == null) {
            throw new InvalidInputException("role", "Role must be OPERATOR or ADMIN.");
        }
        if (actorId != null) {
            requireActiveAdmin(actorId);
        }
        validateUsername(username);
        boolean generate = isEmpty(rawPassword);
        String password = generate ? passwordGenerator.generate() : rawPassword;
        validatePassword(password);
        if (users.existsByUsername(username)) {
            throw new DuplicateUsernameException(username);
        }
        Instant now = Instant.now(clock);
        long id;
        try {
            id = users.insert(username, passwordEncoder.encode(password), role, true, now);
        } catch (DuplicateKeyException e) {
            // Another request created the same username between the check and the insert.
            throw new DuplicateUsernameException(username);
        }
        audit.recordUserCreated(actorId, id, "username=" + username + ", role=" + role.name()
                + ", passwordGenerated=" + generate);
        return new AccountCredentials(users.findById(id).orElseThrow(), generate ? password : null);
    }

    /**
     * Replaces a user's password and ends that user's other sessions after commit.
     *
     * @param newPassword    the new password, or {@code null}/empty to generate one
     * @param actorSessionId the administrator's own session, kept active when they reset their own password
     * @throws OperationNotPermittedException if the target is a different administrator
     * @throws NotFoundException              if the target does not exist
     * @throws ConflictException              if the target account is deactivated
     */
    @Transactional
    public AccountCredentials resetPassword(long actorId, long targetId, String newPassword, String actorSessionId) {
        User actor = requireActiveAdmin(actorId);
        User target = findUser(targetId);
        boolean self = target.id() == actor.id();
        if (target.role() == Role.ADMIN && !self) {
            throw new OperationNotPermittedException(
                    "Administrators cannot reset another administrator's password.");
        }
        if (!target.enabled()) {
            throw new ConflictException("ACCOUNT_DEACTIVATED", "This account is deactivated.");
        }
        boolean generate = isEmpty(newPassword);
        String password = generate ? passwordGenerator.generate() : newPassword;
        validatePassword(password);

        users.updatePasswordHash(target.id(), passwordEncoder.encode(password));
        audit.recordPasswordReset(actorId, target.id(), "self=" + self + ", passwordGenerated=" + generate);
        afterCommit(() -> sessions.expireAll(target.id(), self ? actorSessionId : null));
        return new AccountCredentials(users.findById(target.id()).orElseThrow(), generate ? password : null);
    }

    /**
     * Deactivates an operator account: it can no longer sign in and its sessions end after commit. Repeating the
     * call on a deactivated account returns it unchanged and writes no further audit event.
     *
     * @throws OperationNotPermittedException if the target is an administrator
     * @throws NotFoundException              if the target does not exist
     */
    @Transactional
    public User deactivateUser(long actorId, long targetId) {
        requireActiveAdmin(actorId);
        User target = findUser(targetId);
        if (target.role() == Role.ADMIN) {
            throw new OperationNotPermittedException("Administrator accounts cannot be deactivated.");
        }
        if (!target.enabled()) {
            return target;
        }
        users.updateEnabled(target.id(), false);
        audit.recordUserDeactivated(actorId, target.id(), "username=" + target.username());
        afterCommit(() -> sessions.expireAll(target.id(), null));
        return users.findById(target.id()).orElseThrow();
    }

    private User requireActiveAdmin(long actorId) {
        User actor = users.findById(actorId).orElse(null);
        if (actor == null || actor.role() != Role.ADMIN || !actor.enabled()) {
            throw new OperationNotPermittedException("Only an active administrator can manage accounts.");
        }
        return actor;
    }

    private User findUser(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " does not exist."));
    }

    /** Runs the action once the current transaction commits, or immediately when there is no transaction. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new InvalidInputException("username",
                    "Username must be 3-64 characters using letters, digits, '.', '_' or '-'.");
        }
    }

    static void validatePassword(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidInputException("password",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidInputException("password", "Password must be at most " + MAX_PASSWORD_BYTES + " bytes.");
        }
    }

    /**
     * @param user              the account after the change
     * @param generatedPassword the plain password if it was generated, otherwise {@code null}
     *                          (a password chosen by the administrator is never echoed back)
     */
    public record AccountCredentials(User user, String generatedPassword) {

        public boolean passwordGenerated() {
            return generatedPassword != null;
        }
    }

    /** Thrown when creating a user whose username already exists. */
    public static class DuplicateUsernameException extends ConflictException {
        public DuplicateUsernameException(String username) {
            super("USERNAME_TAKEN", "Username already exists: " + username);
        }
    }
}
