package com.meridian.payments.intake.salesforce;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Salesforce stand-in that can be told what to answer, so the relay's classification is tested instead
 * of assumed.
 *
 * <p>It records every write it was asked to perform: a test that only asserts the job state cannot tell
 * "held and never sent" from "held after two sends", and the difference is a duplicated payment fact.
 */
public final class SimulatedSalesforce implements SalesforceClient {

    private final Map<String, Deque<Result>> perPayment = new ConcurrentHashMap<>();
    private final Deque<Result> nextAnswers = new ArrayDeque<>();
    private final List<PaymentWrite> writes = new CopyOnWriteArrayList<>();
    private final Result fallback;

    public SimulatedSalesforce() {
        this(Result.APPLIED);
    }

    public SimulatedSalesforce(Result fallback) {
        this.fallback = fallback;
    }

    /** The next call for this payment answers {@code result}, once. */
    public SimulatedSalesforce failOnce(String paymentKey, Result result) {
        perPayment.computeIfAbsent(paymentKey, key -> new ArrayDeque<>()).add(result);
        return this;
    }

    /** The next call, whatever the payment, answers {@code result}, once. */
    public SimulatedSalesforce failNext(Result result) {
        synchronized (nextAnswers) {
            nextAnswers.add(result);
        }
        return this;
    }

    @Override
    public Result write(PaymentWrite write) {
        writes.add(write);
        synchronized (nextAnswers) {
            if (!nextAnswers.isEmpty()) {
                return nextAnswers.poll();
            }
        }
        Deque<Result> scripted = perPayment.get(write.paymentKey());
        return scripted == null || scripted.isEmpty() ? fallback : scripted.poll();
    }

    public List<PaymentWrite> writes() {
        return List.copyOf(writes);
    }

    public int writeCount() {
        return writes.size();
    }
}
