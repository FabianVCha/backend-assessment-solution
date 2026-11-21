package com.meridian.payments.intake;

import com.meridian.payments.intake.auth.AndinaSignatureVerifier;
import com.meridian.payments.intake.auth.InMemorySecretProvider;
import com.meridian.payments.intake.auth.Provider;
import com.meridian.payments.intake.auth.QuetzalSignatureVerifier;
import com.meridian.payments.intake.auth.SecretProvider;
import com.meridian.payments.intake.canonical.AndinaCanonicalEventMapper;
import com.meridian.payments.intake.canonical.CanonicalEventMapper;
import com.meridian.payments.intake.canonical.QuetzalCanonicalEventMapper;
import com.meridian.payments.intake.http.IntakeServer;
import com.meridian.payments.intake.http.WebhookEndpoint;
import com.meridian.payments.intake.publish.Broker;
import com.meridian.payments.intake.publish.CanonicalEventPublisher;
import com.meridian.payments.intake.publish.PublisherLoop;
import com.meridian.payments.intake.store.InMemoryIntakeStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Runs the intake as a process: the endpoint on a port and the publisher on its poll loop.
 *
 * <p>This binary is a demonstration of the wiring, not a production deployment. Three adapters are
 * in-memory stubs whose interfaces the design fixes: the store, the broker and the secret source.
 * Everything between them — signature verification, mapping, durability semantics and publication —
 * is the real implementation that the test suite exercises.
 *
 * <p>The providers' webhook secrets are read from the environment here. In Azure the same
 * {@link SecretProvider} boundary is backed by Key Vault with the workload identity, which is
 * provisioned and permissioned in {@code infra/} but whose adapter is still pending.
 */
public final class IntakeApplication {

    private static final Logger LOG = Logger.getLogger("com.meridian.payments.intake");

    private IntakeApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(environment("PORT", "8080"));
        SecretProvider secrets = new InMemorySecretProvider(Map.of(
                Provider.QUETZAL, required("QUETZAL_WEBHOOK_SECRET").getBytes(StandardCharsets.UTF_8),
                Provider.ANDINA, required("ANDINA_WEBHOOK_SECRET").getBytes(StandardCharsets.UTF_8)));

        Clock clock = Clock.systemUTC();
        InMemoryIntakeStore store = new InMemoryIntakeStore(clock);
        Broker broker = message -> LOG.info("published message " + message.messageId());
        List<CanonicalEventMapper> mappers = List.of(
                new QuetzalCanonicalEventMapper(), new AndinaCanonicalEventMapper());

        NotificationIntake intake = new NotificationIntake(
                Map.of(
                        Provider.QUETZAL, new QuetzalSignatureVerifier(secrets, clock),
                        Provider.ANDINA, new AndinaSignatureVerifier(secrets, clock)),
                mappers,
                store,
                clock);

        CanonicalEventPublisher publisher = new CanonicalEventPublisher(store, broker, 50);
        try (PublisherLoop loop = new PublisherLoop(publisher, Duration.ofSeconds(10));
             IntakeServer server = IntakeServer.start(new WebhookEndpoint(intake), port)) {
            loop.start();
            LOG.info("intake listening on port " + server.port() + " with in-memory store, "
                    + "recording broker and environment-provided secrets");
            Thread.currentThread().join();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
