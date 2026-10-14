package com.gtnhplanner.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.live.MachineStatus.Level;
import com.gtnhplanner.live.Seen;
import com.gtnhplanner.net.Proto;

/** "A vista d'occhio": what a player reads live of their plan's machines, and what they see of the rest. */
class SightTest {

    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b0"),
        ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    private static final Sight SIGHT = new Sight(true, 64, NetworkReach.NONE);

    private static Sight.Viewer at(final int dim, final double x, final double y, final double z) {
        return new Sight.Viewer(ALEX, dim, x, y, z);
    }

    private static Proto.Placement machine(final int index, final int dim, final int x, final int y, final int z) {
        return new Proto.Placement(NODE, index, dim, x, y, z);
    }

    private static MachineStatus running() {
        return new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "Electric Blast Furnace",
            true,
            true,
            true,
            true,
            50,
            200,
            1920,
            0,
            10000,
            "Steel Ingot",
            "",
            false);
    }

    // region The sphere

    @Test
    void aMachineIsSeenWithinTheRadiusInASphere() {
        final Sight.Viewer alex = at(0, 0.5, 64.5, 0.5);
        assertTrue(SIGHT.sees(alex, machine(0, 0, 64, 64, 0)), "64 blocks along an axis");
        assertFalse(SIGHT.sees(alex, machine(0, 0, 65, 64, 0)), "65 blocks along an axis");
        assertTrue(SIGHT.sees(alex, machine(0, 0, 0, 0, 0)), "64 blocks straight down, through the ground");
        // 50 blocks along two axes is inside a 64-block cube, but 70.7 blocks away.
        assertFalse(SIGHT.sees(alex, machine(0, 0, 50, 64, 50)), "a sphere, not a cube");
        assertTrue(SIGHT.sees(alex, machine(0, 0, 45, 64, 45)), "63.6 blocks away");
    }

    @Test
    void anotherDimensionIsNeverInSight() {
        assertFalse(SIGHT.sees(at(-1, 0.5, 64.5, 0.5), machine(0, 0, 0, 64, 0)));
        assertTrue(SIGHT.sees(at(-1, 0.5, 64.5, 0.5), machine(0, -1, 0, 64, 0)));
    }

    @Test
    void progressionOffSeesEverythingAnywhere() {
        assertTrue(Sight.UNLIMITED.sees(at(0, 0, 0, 0), machine(0, 7, 100_000, 64, -100_000)));
    }

    @Test
    void aNetworkReachesBeyondSight() {
        final Sight networked = new Sight(true, 64, (player, dim, x, y, z) -> player.equals(ALEX) && x == 500);
        assertTrue(networked.sees(at(0, 0, 64, 0), machine(0, 0, 500, 64, 0)));
        assertFalse(networked.sees(at(0, 0, 64, 0), machine(0, 0, 600, 64, 0)));
        assertFalse(SIGHT.sees(at(0, 0, 64, 0), machine(0, 0, 500, 64, 0)), "no network: nothing beyond sight");
    }

    // endregion

    // region The scan

    /** A scan over a world of running machines, the watcher where {@code where} says. */
    private static final class World {

        final Map<String, List<Proto.Live>> heard = new HashMap<>();
        final List<String> probed = new ArrayList<>();
        final Sight.Viewer[] where = { at(0, 0.5, 64.5, 0.5) };
        final LiveScanner<String> live;

        World(final List<Proto.Placement> placed, final Sight sight) {
            live = new LiveScanner<>((dim, x, y, z) -> {
                probed.add(x + "," + z);
                return running();
            },
                (w, s) -> heard.computeIfAbsent(w, k -> new ArrayList<>())
                    .addAll(s.machines()),
                id -> placed,
                w -> where[0],
                sight);
        }

        void ticks(final int n) {
            for (int i = 0; i < n; i++) live.tick();
        }

        /** What alex heard of a machine, last: null when nothing, a Live with no status when out of sight. */
        Proto.Live last(final int index) {
            final List<Proto.Live> all = heard.getOrDefault("alex", List.of());
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i)
                .index() == index) return all.get(i);
            return null;
        }
    }

    @Test
    void onlyMachinesInSightAreReadAndSent() {
        final World w = new World(List.of(machine(0, 0, 10, 64, 0), machine(1, 0, 200, 64, 0)), SIGHT);
        w.live.watch("alex", PLAN);
        assertNull(
            w.last(1)
                .status(),
            "the far machine is out of sight from the start");
        w.ticks(2);
        assertEquals(List.of("10,0"), w.probed, "the far one is never looked at");
        assertEquals(
            running(),
            w.last(0)
                .status());
        assertEquals(Map.of(machine(0, 0, 10, 64, 0).key(), running()), w.live.seenBy("alex"));
    }

    @Test
    void walkingAwayAndBackSendsOutOfSightThenTheMachine() {
        final World w = new World(List.of(machine(0, 0, 10, 64, 0), machine(1, 0, 200, 64, 0)), SIGHT);
        w.live.watch("alex", PLAN);
        w.ticks(2);
        // Alex walks to the far machine.
        w.where[0] = at(0, 200.5, 64.5, 0.5);
        w.ticks(LiveScanner.SIGHT_PERIOD);
        assertNull(
            w.last(0)
                .status(),
            "the near one went out of sight");
        assertEquals(
            running(),
            w.last(1)
                .status(),
            "the far one is read as soon as it is in sight");
        assertEquals(1, w.live.seen("alex"));
        // Back again: the first one is no longer known (nobody saw it) and is read afresh.
        w.probed.clear();
        w.where[0] = at(0, 0.5, 64.5, 0.5);
        w.ticks(LiveScanner.SIGHT_PERIOD);
        assertTrue(w.probed.contains("10,0"));
        assertEquals(
            running(),
            w.last(0)
                .status());
    }

    @Test
    void anotherDimensionIsOutOfSight() {
        final World w = new World(List.of(machine(0, 0, 10, 64, 0)), SIGHT);
        w.where[0] = at(-1, 10.5, 64.5, 0.5);
        w.live.watch("alex", PLAN);
        w.ticks(3);
        assertTrue(w.probed.isEmpty());
        assertNull(
            w.last(0)
                .status());
    }

    @Test
    void withProgressionOffEveryMachineIsRead() {
        final World w = new World(List.of(machine(0, 0, 10, 64, 0), machine(1, 3, 90_000, 64, 0)), Sight.UNLIMITED);
        w.live.watch("alex", PLAN);
        w.ticks(2);
        assertEquals(2, w.probed.size());
        assertEquals(
            running(),
            w.last(1)
                .status());
    }

    // endregion

    // region Out of sight on the client

    @Test
    void outOfSightIsGreyWithTheAgeOfWhatWasSeen() {
        final Seen live = Seen.live(running());
        assertEquals(
            Level.GOOD,
            live.health(1000, 5_000)
                .level());
        final Seen away = Seen.hidden(live, 10_000);
        assertEquals(new MachineStatus.Health(Level.AWAY, "Out of sight · 12 s ago"), away.health(1000, 22_000));
        assertEquals(running(), away.current(22_000), "the last data is still shown, greyed");
        assertEquals(away, Seen.hidden(away, 30_000), "going out of sight again keeps when it first did");
        assertEquals(new MachineStatus.Health(Level.AWAY, "Out of sight"), away.health(1000, 71_000));
        assertNull(away.current(71_000), "after 60 s the data is too old to show");
        assertEquals(
            new MachineStatus.Health(Level.AWAY, "Out of sight"),
            Seen.hidden(null, 0)
                .health(1000, 0),
            "never seen");
    }

    // endregion
}
