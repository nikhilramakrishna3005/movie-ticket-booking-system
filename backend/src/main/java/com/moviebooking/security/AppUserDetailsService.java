package com.moviebooking.security;

import java.util.HashSet;
import java.util.Set;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUser loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        Set<Long> theaterIds = user.getRole() == Role.THEATER_ADMIN
                ? new HashSet<>(users.findAssignedTheaterIds(user.getId()))
                : Set.of();
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), user.getRole(), theaterIds);
    }
}
