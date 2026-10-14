package com.gtnhplanner.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.live.Seen;
import com.gtnhplanner.net.Proto;

/** The client keeps what a player saw of a machine gone out of their sight, and since when. */
class LiveStatusTest {

    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @Test
    void outOfSightKeepsWhatWasSeenEvenThroughAFullMessage() {
        final Map<UUID, Map<String, Seen>> byPlan = new HashMap<>();
        final MachineStatus stone = MachineStatus.block("Stone");
        LiveStatus.apply(byPlan, new Proto.Status(PLAN, true, List.of(new Proto.Live(NODE, 0, stone))), 1_000);
        LiveStatus.apply(byPlan, new Proto.Status(PLAN, false, List.of(new Proto.Live(NODE, 0, null))), 2_000);
        final String key = Proto.Placement.key(NODE, 0);
        assertEquals(
            new Seen(stone, 2_000),
            byPlan.get(PLAN)
                .get(key));
        // Watching the plan again: the server says out of sight, and what was seen stays, with its age.
        LiveStatus.apply(byPlan, new Proto.Status(PLAN, true, List.of(new Proto.Live(NODE, 0, null))), 5_000);
        assertEquals(
            new Seen(stone, 2_000),
            byPlan.get(PLAN)
                .get(key));
        // Back in sight: live again.
        LiveStatus.apply(byPlan, new Proto.Status(PLAN, false, List.of(new Proto.Live(NODE, 0, stone))), 6_000);
        assertTrue(
            byPlan.get(PLAN)
                .get(key)
                .inSight());
        // A full message drops machines it does not name.
        LiveStatus.apply(byPlan, new Proto.Status(PLAN, true, List.of()), 7_000);
        assertNull(
            byPlan.get(PLAN)
                .get(key));
    }
}
