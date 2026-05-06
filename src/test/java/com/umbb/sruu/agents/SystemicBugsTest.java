package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.Emergency;
import com.umbb.sruu.ontology.Location;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemicBugsTest {

    @Test
    void bug2_perimeterOnlyStartsAfterPrimaryAssigned_andPoliceReleaseCancelsEvenEnRoute() {
        DispatcherAgent dispatcher = new DispatcherAgent();
        Emergency fire = new Emergency();
        fire.setId("INC-14");
        fire.setType("FIRE");
        fire.setSeverity(5);
        fire.setLocation(new Location(10, 10));

        dispatcher.testSeedEmergency("INC-14", fire);
        dispatcher.testMaybeStartPerimeter("INC-14");
        assertFalse(dispatcher.testHasPoliceConversation("INC-14"),
                "perimeter must NOT start without a primary assignment");

        dispatcher.testMarkPrimaryAssigned("INC-14", "firetruck-1");
        dispatcher.testMaybeStartPerimeter("INC-14");
        assertTrue(dispatcher.testHasPoliceConversation("INC-14"),
                "perimeter should start once primary is assigned");

        PoliceAgent police = new PoliceAgent();
        police.testSetMissionForRelease("INC-14", PoliceAgent.State.EN_ROUTE);
        police.testHandleReleaseMessage("INC-14");
        assertEquals("IDLE", police.testState(), "police must release perimeter even if EN_ROUTE");
        assertNull(police.testCurrentMissionId(), "mission id must be cleared on release");
    }

    @Test
    void bug3_retryGateSuppressesBlindRetriesUntilChangeOrBackoff_andEventuallyFails() {
        DispatcherAgent dispatcher = new DispatcherAgent();
        String inc = "INC-15";

        long t0 = 1_000;
        assertTrue(dispatcher.testRetryGate(inc, t0, "NO_UNITS", Set.of()),
                "first failure should allow retry scheduling");
        assertFalse(dispatcher.testRetryGate(inc, t0 + 1000, "NO_UNITS", Set.of()),
                "identical retry too soon must be suppressed");
        assertTrue(dispatcher.testRetryGate(inc, t0 + 10_000, "NO_UNITS", Set.of()),
                "exponential backoff elapsed should allow retry even if no availability change");
        assertTrue(dispatcher.testRetryGate(inc, t0 + 20_000, "NO_UNITS", Set.of()));

        dispatcher.testMarkFailed(inc, "max retries exceeded");
        assertTrue(dispatcher.testIsFailed(inc));
    }

    @Test
    void bug4_firetruckRefillCompletionIsAtomic_stateAndResourceAreConsistent() {
        FireTruckAgent truck = new FireTruckAgent();
        truck.testSetRefillingState(40);
        truck.testCompleteRefillAtomic();

        assertEquals("IDLE", truck.testState(), "after refill completion truck must be IDLE");
        assertEquals(100, truck.testWaterLevel(), "after refill completion water must be 100");
    }
}

