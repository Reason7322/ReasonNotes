package org.reasonnotes.probe;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** One process-wide gate shared by every Activity's incoming scanner. */
final class ExchangeScanGate {
    private final ReentrantLock lock = new ReentrantLock(true);

    <T> T run(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
