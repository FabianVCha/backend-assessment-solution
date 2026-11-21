package com.meridian.payments.intake.publish;

import java.util.ArrayList;
import java.util.List;

/**
 * Broker stand-in that records what would have been sent.
 *
 * <p>It can fail before recording or after recording. The second case is the interesting one: the
 * broker stored the message and the acknowledgement was lost, which is exactly how an at-least-once
 * pipeline produces a duplicate. The publisher must republish, and consumers deduplicate.
 */
public final class RecordingBroker implements Broker {

    private final Object lock = new Object();
    private final List<CanonicalMessage> published = new ArrayList<>();
    private boolean failBeforeRecording;
    private boolean loseNextAcknowledgement;

    @Override
    public void publish(CanonicalMessage message) {
        synchronized (lock) {
            if (failBeforeRecording) {
                failBeforeRecording = false;
                throw new BrokerUnavailableException("the broker is unavailable");
            }
            published.add(message);
            if (loseNextAcknowledgement) {
                loseNextAcknowledgement = false;
                throw new BrokerUnavailableException("the acknowledgement was lost");
            }
        }
    }

    /** Makes the next publish fail without the broker storing anything. */
    public void failNextPublish() {
        synchronized (lock) {
            failBeforeRecording = true;
        }
    }

    /** Makes the next publish store the message and then fail, as a lost acknowledgement does. */
    public void loseNextAcknowledgement() {
        synchronized (lock) {
            loseNextAcknowledgement = true;
        }
    }

    public List<CanonicalMessage> published() {
        synchronized (lock) {
            return List.copyOf(published);
        }
    }
}
