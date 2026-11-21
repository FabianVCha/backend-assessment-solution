package com.meridian.payments.intake.ops;

import java.util.Optional;
import java.util.Set;

/** Authenticates an Authorization header. In production an OIDC provider adapter implements this port. */
@FunctionalInterface
public interface OpsIdentity {
    Set<String> COUNTRIES = Set.of("CO", "MX", "CL");

    enum Role { read, replay }

    record Caller(String subject, Set<Role> roles, Set<String> countries) {
        public Caller {
            if (subject == null || subject.isBlank() || subject.length() > 200
                    || subject.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid operator subject");
            }
            roles = Set.copyOf(roles);
            countries = Set.copyOf(countries);
            if (countries.isEmpty() || !COUNTRIES.containsAll(countries)) {
                throw new IllegalArgumentException("Invalid operator countries");
            }
        }
    }

    Optional<Caller> resolve(String authorization);
}
