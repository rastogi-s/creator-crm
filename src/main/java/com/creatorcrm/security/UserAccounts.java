package com.creatorcrm.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** The admin account(s) that can sign in to the dashboard, plus brute-force protection. */
@Service
public class UserAccounts implements UserDetailsService {

    /** Lock a username for 15 minutes after 5 failed logins. */
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private record Attempts(int failures, Instant lockedUntil) {}

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AppUserRepo users;

    public UserAccounts(AppUserRepo users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser u = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
        return User.withUsername(u.username)
                .password(u.passwordHash)
                .roles("ADMIN")
                .accountLocked(isLocked(u.username))
                .build();
    }

    public boolean isLocked(String username) {
        Attempts a = attempts.get(username.toLowerCase());
        return a != null && a.lockedUntil != null && Instant.now().isBefore(a.lockedUntil);
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent e) {
        attempts.compute(String.valueOf(e.getAuthentication().getPrincipal()).toLowerCase(), (k, a) -> {
            int failures = (a == null ? 0 : a.failures) + 1;
            return failures >= MAX_FAILURES ? new Attempts(0, Instant.now().plus(LOCK)) : new Attempts(failures, null);
        });
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent e) {
        attempts.remove(e.getAuthentication().getName().toLowerCase());
    }
}
