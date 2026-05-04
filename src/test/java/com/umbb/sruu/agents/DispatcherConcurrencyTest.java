package com.umbb.sruu.agents;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherConcurrencyTest {

    @Test
    void onlyOneConcurrentStatusTransitionWins() throws Exception {
        DispatcherAgent dispatcher = new DispatcherAgent();
        assertTrue(dispatcher.testTransitionStatus("INC-1", null, "PENDING"));

        int workers = 10;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return dispatcher.testTransitionStatus("INC-1", "PENDING", "ASSIGNED");
            }));
        }
        ready.await();
        start.countDown();

        int success = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                success++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, success, "CAS transition must be atomic under contention");
        assertEquals("ASSIGNED", dispatcher.testCurrentStatus("INC-1"));
    }

    @Test
    void concurrentEventAppendsAreNotLost() throws Exception {
        DispatcherAgent dispatcher = new DispatcherAgent();
        int workers = 10;
        int perWorker = 20;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                for (int j = 0; j < perWorker; j++) {
                    dispatcher.testAppendDetected("INC-2", "FIRE");
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        assertEquals(workers * perWorker, dispatcher.testEventCount("INC-2"));
    }

    @Test
    void duplicateStatusMessageIsIgnored() throws Exception {
        DispatcherAgent dispatcher = new DispatcherAgent();

        Method m = DispatcherAgent.class.getDeclaredMethod("isDuplicateStatusMessage", jade.lang.acl.ACLMessage.class);
        m.setAccessible(true);

        jade.lang.acl.ACLMessage msg = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
        msg.setSender(new jade.core.AID("unit-1", jade.core.AID.ISLOCALNAME));
        msg.setContent("UNIT_IDLE:unit-1:INC-10:7");

        boolean first = (boolean) m.invoke(dispatcher, msg);
        boolean second = (boolean) m.invoke(dispatcher, msg);

        assertTrue(!first);
        assertTrue(second);
    }
}

