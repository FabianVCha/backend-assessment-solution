package com.meridian.payments.intake.ops;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Local HS256 profile only. No production token issuer or JWKS support. */
public final class LocalOpsIdentity implements OpsIdentity {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ISSUER = "INTAKE_OPS_ISSUER";
    private static final String AUDIENCE = "INTAKE_OPS_AUDIENCE";
    private static final String KEY = "INTAKE_OPS_HS256_KEY_BASE64";

    private final String issuer;
    private final String audience;
    private final SecretKeySpec key;
    private final Clock clock;

    public LocalOpsIdentity(String issuer, String audience, byte[] key, Clock clock) {
        if (issuer.isBlank() || audience.isBlank() || key.length < 32) {
            throw new IllegalArgumentException("Local ops needs issuer, audience and an HS256 key of at least 32 bytes");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.key = new SecretKeySpec(key, "HmacSHA256");
        this.clock = clock;
    }

    public static Optional<OpsIdentity> fromEnvironment(Map<String, String> env, Clock clock) {
        if (!env.containsKey(ISSUER) && !env.containsKey(AUDIENCE) && !env.containsKey(KEY)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LocalOpsIdentity(env.getOrDefault(ISSUER, ""), env.getOrDefault(AUDIENCE, ""),
                    Base64.getDecoder().decode(env.getOrDefault(KEY, "")), clock));
        } catch (IllegalArgumentException e) {
            // Base64 decoder details are not part of the configuration error contract.
            throw new IllegalStateException("Configure INTAKE_OPS_ISSUER, INTAKE_OPS_AUDIENCE and "
                    + "INTAKE_OPS_HS256_KEY_BASE64 together with a base64-encoded key of at least 32 bytes");
        }
    }

    @Override
    public Optional<Caller> resolve(String authorization) {
        if (authorization == null || authorization.length() > 8192
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        try {
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 3 || !authorization.substring(7).matches("[A-Za-z0-9_.-]+")) {
                return Optional.empty();
            }
            var decoder = Base64.getUrlDecoder();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, decoder.decode(parts[2]))) {
                return Optional.empty();
            }
            var header = JSON.readTree(decoder.decode(parts[0]));
            if (header == null || !header.isObject() || !"HS256".equals(text(header, "alg"))
                    || header.has("crit") || header.has("b64")) {
                return Optional.empty();
            }
            var claims = JSON.readTree(decoder.decode(parts[1]));
            if (claims == null || !claims.isObject() || !issuer.equals(text(claims, "iss"))
                    || !matchesAudience(claims.path("aud"))) {
                return Optional.empty();
            }
            long now = clock.instant().getEpochSecond();
            var expiry = claims.path("exp");
            var notBefore = claims.path("nbf");
            if (!isSeconds(expiry) || expiry.longValue() <= now
                    || (!notBefore.isMissingNode() && (!isSeconds(notBefore) || notBefore.longValue() > now))) {
                return Optional.empty();
            }
            var roles = new HashSet<Role>();
            for (String role : strings(claims.path("roles"))) {
                roles.add(Role.valueOf(role));
            }
            return Optional.of(new Caller(text(claims, "sub"), roles, strings(claims.path("countries"))));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private boolean matchesAudience(JsonNode claim) {
        return claim.isTextual() ? audience.equals(claim.textValue()) : strings(claim).contains(audience);
    }

    private static boolean isSeconds(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong();
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected a claim array");
        }
        var result = new HashSet<String>();
        for (var entry : node) {
            if (!entry.isTextual() || !result.add(entry.textValue())) {
                throw new IllegalArgumentException("Expected distinct string claims");
            }
        }
        return result;
    }

    private static String text(JsonNode object, String name) {
        return object.path(name).textValue();
    }
}
