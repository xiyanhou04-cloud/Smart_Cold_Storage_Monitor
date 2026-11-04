package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user stored in the server-side session. The password hash is erased after authentication. */
public class AppUserPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final Role role;
    private final boolean enabled;
    private String passwordHash;

    public AppUserPrincipal(User user) {
        this.id = user.id();
        this.username = user.username();
        this.role = user.role();
        this.enabled = user.enabled();
        this.passwordHash = user.passwordHash();
    }

    public long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    /** Equal when they refer to the same account, so the session registry groups one user's sessions. */
    @Override
    public boolean equals(Object other) {
        return other instanceof AppUserPrincipal that && id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
