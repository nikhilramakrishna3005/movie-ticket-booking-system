package com.moviebooking.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Security principal for one request. Theater assignments are loaded with the user so
 * authorization checks need no further queries; the password hash is erased after authentication.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    private final long id;
    private final String username;
    private final String displayName;
    private final Role role;
    private final Set<Long> theaterIds;
    private String passwordHash;

    public AuthenticatedUser(long id, String username, String passwordHash, String displayName, Role role,
            Set<Long> theaterIds) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.theaterIds = Set.copyOf(theaterIds);
    }

    public long id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    /** Theaters this user administers; always empty for customers. */
    public Set<Long> theaterIds() {
        return theaterIds;
    }

    public boolean isTheaterAdmin() {
        return role == Role.THEATER_ADMIN;
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
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
