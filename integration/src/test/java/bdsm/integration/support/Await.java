package bdsm.integration.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Await {

    private static final long POLL_MS = 200;

    private Await() {
    }

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Not true after " + timeout.toSeconds() + " s: " + description);
            }
            pause(POLL_MS);
        }
    }

    public static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }
}
