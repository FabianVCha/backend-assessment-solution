package com.meridian.payments.intake.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** NFR6: authentication lines never carry the secret, the signature, the body or payer data. */
class SensitiveDataNeverLoggedTest {

    @Test
    void authenticationOutcomesLogOnlyProviderAndReason() {
        Logger logger = Logger.getLogger("com.meridian.payments.intake.auth");
        List<String> lines = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        logger.addHandler(capture);
        try {
            QuetzalSignatureVerifier quetzal = new QuetzalSignatureVerifier(Fixtures.SECRETS,
                    Clock.fixed(Instant.ofEpochSecond(1795888803L), ZoneOffset.UTC));
            AndinaSignatureVerifier andina = new AndinaSignatureVerifier(Fixtures.SECRETS,
                    Clock.fixed(OffsetDateTime.parse("2026-11-28T14:02:59-05:00").toInstant(),
                            ZoneOffset.UTC));

            byte[] quetzalBody = Fixtures.sample(Fixtures.QZ_SUCCEEDED);
            quetzal.verify(Fixtures.quetzal(Fixtures.quetzalHeader("1795888803",
                    Fixtures.QUETZAL_VECTORS.get(0).signature()), quetzalBody));
            quetzal.verify(Fixtures.quetzal("t=1795888803,v1=" + "0".repeat(64), quetzalBody));

            byte[] andinaBody = Fixtures.sample(Fixtures.AN_PENDIENTE);
            andina.verify(Fixtures.andina("2026-11-28T14:02:59-05:00",
                    Fixtures.ANDINA_VECTORS.get(0).signature(), andinaBody));
            andina.verify(Fixtures.andina("2026-11-28T14:02:59-05:00", "not-base64", andinaBody));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }

        assertEquals(4, lines.size(), "one audit line per outcome: " + lines);
        List<String> forbidden = List.of(
                Fixtures.QUETZAL_SECRET,
                Fixtures.ANDINA_SECRET,
                Fixtures.QUETZAL_VECTORS.get(0).signature(),
                Fixtures.ANDINA_VECTORS.get(0).signature(),
                "maria.lopez@example.com",
                "jorge.ramirez@example.com",
                "Laura Gómez",
                "CC 1020304050",
                "laura.gomez@example.com",
                "POL-MX-000412785",
                "POL-CO-000187342",
                "evt_01JDQ3K8M2X7",
                "TRX-CO-5521907",
                "tok_qz_9sD2fK");
        for (String line : lines) {
            for (String value : forbidden) {
                assertFalse(line.contains(value),
                        () -> "log line leaked [" + value + "]: " + line);
            }
        }
    }
}
