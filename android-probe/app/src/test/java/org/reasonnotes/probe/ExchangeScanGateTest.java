package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ExchangeScanGateTest {
    @Test
    public void overlappingCallersNeverExecuteScanBodiesConcurrently() throws Exception {
        ExchangeScanGate gate = new ExchangeScanGate();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ArrayList<Future<Integer>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            futures.add(pool.submit(() -> {
                start.await();
                return gate.run(() -> {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    finally { active.decrementAndGet(); }
                    return now;
                });
            }));
        }
        start.countDown();
        for (Future<Integer> future : futures) future.get();
        pool.shutdown();
        assertEquals(1, maximum.get());
    }
}
