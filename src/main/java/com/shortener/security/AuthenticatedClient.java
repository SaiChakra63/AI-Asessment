package com.shortener.security;

import java.util.Set;

public record AuthenticatedClient(String clientId, Set<String> authorities) {

    public AuthenticatedClient {
        authorities = Set.copyOf(authorities);
    }

    public boolean isAdmin() {
        return authorities.contains("ROLE_ADMIN");
    }
}
