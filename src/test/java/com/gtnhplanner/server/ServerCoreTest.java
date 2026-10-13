package com.gtnhplanner.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.live.MachineStatus.Level;
import com.gtnhplanner.net.Chunker;
import com.gtnhplanner.net.Proto;

/** The server side without a server: the messages, the parts they travel in, the store, the live scan, the badges. */
class ServerCoreTest {

    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private static MachineStatus running(final int progress) {
        return new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "Electric Blast Furnace",
            true,
            true,
            true,
            true,
            progress,
            200,
            1920,
            0,
            10000,
            "Steel Ingot",
            "",
            false);
    }

    // region Messages

    @Test
    void everyMessageComesBackAsItWent() throws IOException {
        final byte[] blob = new byte[70_000];
        new Random(1).nextBytes(blob);
        final List<Proto.Placement> placements = List
            .of(new Proto.Placement(NODE, 0, 0, 10, 64, -20), new Proto.Placement(NODE, 1, -1, 1, 2, 3));
        final Proto.Entry entry = new Proto.Entry(PLAN, 4, "Steel line", 123L, "Alex");
        final List<Proto.Msg> all = List.of(
            new Proto.Hello(Proto.PROTOCOL, "0.2.0-alpha.9"),
            new Proto.Push(PLAN, 3, "Steel line", blob, placements),
            new Proto.Delete(PLAN),
            new Proto.Fetch(PLAN),
            new Proto.Watch(PLAN),
            new Proto.Watch(null),
            new Proto.Welcome(Proto.PROTOCOL, true, "v", List.of(entry)),
            new Proto.PlanData(entry, blob),
            new Proto.Pushed(PLAN, 5, null),
            new Proto.Pushed(PLAN, 5, NODE),
            new Proto.Removed(PLAN),
            new Proto.Status(
                PLAN,
                true,
                List.of(new Proto.Live(NODE, 0, running(50)), new Proto.Live(NODE, 1, MachineStatus.unloaded()))));
        for (final Proto.Msg msg : all) {
            final Proto.Msg back = Proto.decode(Proto.encode(msg));
            assertEquals(msg.getClass(), back.getClass());
            // Records with arrays compare them by identity: compare their bytes instead.
            assertArrayEquals(
                Proto.encode(msg),
                Proto.encode(back),
                msg.getClass()
                    .getSimpleName());
        }
        assertEquals(placements, ((Proto.Push) Proto.decode(Proto.encode(all.get(1)))).placements());
    }

    @Test
    void malformedBytesAreRefused() {
        assertThrows(IOException.class, () -> Proto.decode(new byte[] { 99 }));
        final byte[] push = Proto.encode(new Proto.Push(PLAN, 0, "x", new byte[10], List.of()));
        assertThrows(IOException.class, () -> Proto.decode(java.util.Arrays.copyOf(push, push.length - 3)));
    }

    @Test
    void bigMessagesTravelInPartsAndComeBackWhole() {
        final byte[] message = new byte[Chunker.PART * 3 + 17];
        new Random(2).nextBytes(message);
        final List<Chunker.Part> parts = Chunker.split(7, message);
        assertEquals(4, parts.size());
        final Chunker.Assembler a = new Chunker.Assembler();
        for (int i = 0; i < 3; i++) assertNull(a.accept(parts.get(i)));
        assertArrayEquals(message, a.accept(parts.get(3)));
        // A small one is one part, whole at once; an empty one too.
        assertArrayEquals(
            new byte[] { 1, 2 },
            a.accept(
                Chunker.split(8, new byte[] { 1, 2 })
                    .get(0)));
        assertArrayEquals(
            new byte[0],
            a.accept(
                Chunker.split(9, new byte[0])
                    .get(0)));
        // A part out of order loses its message, not the next one.
        final List<Chunker.Part> again = Chunker.split(10, message);
        assertNull(a.accept(again.get(0)));
        assertNull(a.accept(again.get(2)));
        for (final Chunker.Part p : Chunker.split(11, message)) {
            final byte[] whole = a.accept(p);
            if (p.index() == p.total() - 1) assertArrayEquals(message, whole);
        }
    }

    // endregion

    // region The store

    @Test
    void theStoreKeepsPlansThroughARestart(@TempDir final File dir) {
        final PlanStore store = new PlanStore(dir);
        final PlanStore.Result r = store.push(
            new Proto.Push(
                PLAN,
                0,
                "Steel line",
                new byte[] { 1, 2, 3 },
                List.of(new Proto.Placement(NODE, 0, 0, 1, 2, 3))),
            "Alex",
            1000);
        assertNull(r.conflictCopy());
        assertEquals(
            1,
            r.plan()
                .revision());
        store.close();
        final PlanStore again = new PlanStore(dir);
        final PlanStore.Stored s = again.get(PLAN);
        assertNotNull(s);
        assertEquals("Steel line", s.name());
        assertEquals(1, s.revision());
        assertEquals("Alex", s.updatedBy());
        assertArrayEquals(new byte[] { 1, 2, 3 }, s.blob());
        assertEquals(List.of(new Proto.Placement(NODE, 0, 0, 1, 2, 3)), s.placements());
        assertEquals(List.of(s.entry()), again.index());
        again.close();
    }

    @Test
    void anEditOnTheLatestRevisionMovesItOn(@TempDir final File dir) {
        final PlanStore store = new PlanStore(dir);
        store.push(new Proto.Push(PLAN, 0, "a", new byte[] { 1 }, List.of()), "Alex", 1);
        final PlanStore.Result r = store.push(new Proto.Push(PLAN, 1, "a", new byte[] { 2 }, List.of()), "Sam", 2);
        assertNull(r.conflictCopy());
        assertEquals(
            2,
            r.plan()
                .revision());
        assertEquals(
            1,
            store.all()
                .size());
        // The same plan again changes nothing.
        final PlanStore.Result same = store.push(new Proto.Push(PLAN, 1, "a", new byte[] { 2 }, List.of()), "Sam", 3);
        assertEquals(
            2,
            same.plan()
                .revision());
        assertNull(same.conflictCopy());
        store.close();
    }

    @Test
    void aStaleEditStillWinsButTheOverwrittenPlanIsKeptAsACopy(@TempDir final File dir) {
        final PlanStore store = new PlanStore(dir);
        store.push(new Proto.Push(PLAN, 0, "Line", new byte[] { 1 }, List.of()), "Alex", 1);
        store.push(new Proto.Push(PLAN, 1, "Line", new byte[] { 2 }, List.of()), "Alex", 2);
        // Sam edited revision 1 meanwhile.
        final PlanStore.Result r = store.push(new Proto.Push(PLAN, 1, "Line", new byte[] { 3 }, List.of()), "Sam", 3);
        assertNotNull(r.conflictCopy());
        assertEquals(
            3,
            r.plan()
                .revision());
        assertArrayEquals(
            new byte[] { 3 },
            store.get(PLAN)
                .blob());
        final PlanStore.Stored copy = store.get(
            r.conflictCopy()
                .id());
        assertArrayEquals(new byte[] { 2 }, copy.blob(), "Alex's revision 2 is not lost");
        assertEquals("Line (conflict copy)", copy.name());
        assertEquals("Alex", copy.updatedBy());
        assertEquals(
            2,
            store.all()
                .size());
        store.close();
    }

    @Test
    void aDeletedPlanGoesToTheTrash(@TempDir final File dir) {
        final PlanStore store = new PlanStore(dir);
        store.push(new Proto.Push(PLAN, 0, "a", new byte[] { 1 }, List.of()), "Alex", 1);
        assertTrue(store.delete(PLAN));
        assertFalse(store.delete(PLAN));
        store.close();
        assertNull(new PlanStore(dir).get(PLAN));
        final String[] trash = new File(dir, "trash").list();
        assertNotNull(trash);
        assertEquals(1, trash.length);
    }

    @Test
    void tooBigAPlanIsRefusedAndABrokenFileSkipped(@TempDir final File dir) throws IOException {
        final PlanStore store = new PlanStore(dir);
        assertNotNull(
            store.push(new Proto.Push(PLAN, 0, "a", new byte[Proto.MAX_BLOB + 1], List.of()), "Alex", 1)
                .refused());
        store.close();
        new File(dir, "plans").mkdirs();
        Files.write(new File(dir, "plans/broken.plan").toPath(), new byte[] { 1, 2, 3 });
        assertTrue(
            new PlanStore(dir).all()
                .isEmpty());
    }

    // endregion

    // region The live scan

    @Test
    void watchersHearWhatChangedAndOnlyThat() {
        final Map<String, MachineStatus> world = new HashMap<>();
        final List<Proto.Placement> placed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            placed.add(new Proto.Placement(NODE, i, 0, i, 64, 0));
            world.put(i + ",64,0", running(10));
        }
        final Map<String, List<Proto.Status>> heard = new HashMap<>();
        final int[] probes = { 0 };
        final LiveScanner<String> live = new LiveScanner<>((dim, x, y, z) -> {
            probes[0]++;
            return world.getOrDefault(x + "," + y + "," + z, MachineStatus.unloaded());
        },
            (w, s) -> heard.computeIfAbsent(w, k -> new ArrayList<>())
                .add(s),
            id -> id.equals(PLAN) ? placed : List.of());
        live.watch("alex", PLAN);
        assertTrue(
            heard.get("alex")
                .get(0)
                .full(),
            "a new watcher is told all that is known at once");
        live.tick();
        assertEquals(LiveScanner.BUDGET, probes[0], "no more than the budget a tick");
        live.tick();
        // Every machine has been looked at once: everyone is new, so everyone was sent.
        int sent = 0;
        for (final Proto.Status s : heard.get("alex")) sent += s.machines()
            .size();
        assertEquals(100, sent);
        // Nothing is looked at again before its period is up.
        final int before = probes[0];
        for (int t = 0; t < LiveScanner.PERIOD - 3; t++) live.tick();
        assertEquals(before, probes[0]);
        // One machine stops: after a period, that one alone is sent.
        world.put("5,64,0", MachineStatus.nothing());
        heard.get("alex")
            .clear();
        for (int t = 0; t < LiveScanner.PERIOD + 2; t++) live.tick();
        final List<Proto.Live> changed = new ArrayList<>();
        for (final Proto.Status s : heard.get("alex")) changed.addAll(s.machines());
        assertEquals(1, changed.size());
        assertEquals(
            5,
            changed.get(0)
                .index());
        // A second watcher gets the whole picture; and when both leave, the plan is no longer looked at.
        live.watch("sam", PLAN);
        assertEquals(
            100,
            heard.get("sam")
                .get(0)
                .machines()
                .size());
        live.forget("alex");
        live.forget("sam");
        final int after = probes[0];
        for (int t = 0; t < 3 * LiveScanner.PERIOD; t++) live.tick();
        assertEquals(after, probes[0]);
    }

    @Test
    void aMachineNoLongerPlacedIsDropped() {
        final List<Proto.Placement> placed = new ArrayList<>(
            List.of(new Proto.Placement(NODE, 0, 0, 0, 64, 0), new Proto.Placement(NODE, 1, 0, 1, 64, 0)));
        final List<Proto.Status> heard = new ArrayList<>();
        final LiveScanner<String> live = new LiveScanner<>(
            (dim, x, y, z) -> running(1),
            (w, s) -> heard.add(s),
            id -> List.copyOf(placed));
        live.watch("alex", PLAN);
        live.tick();
        placed.remove(1);
        live.placementsChanged(PLAN);
        live.watch("sam", PLAN);
        assertEquals(
            1,
            heard.get(heard.size() - 1)
                .machines()
                .size());
    }

    // endregion

    // region Badges

    @Test
    void badgesReadAsAPlayerWouldExpect() {
        assertEquals(
            Level.UNKNOWN,
            MachineStatus.unloaded()
                .health(-1)
                .level());
        assertEquals(
            Level.BAD,
            MachineStatus.nothing()
                .health(-1)
                .level());
        assertEquals(
            "Not a machine",
            MachineStatus.block("Stone")
                .health(-1)
                .text());
        assertEquals(
            Level.GOOD,
            running(50).health(1000)
                .level());
        assertEquals(
            "25%",
            running(50).health(1000)
                .text());
        assertEquals(
            "Wrong machine",
            running(50).health(1001)
                .text());
        final MachineStatus open = new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "EBF",
            true,
            false,
            false,
            true,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            false);
        assertEquals(
            "Structure incomplete",
            open.health(1000)
                .text());
        final MachineStatus idle = new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "EBF",
            true,
            true,
            false,
            true,
            0,
            0,
            0,
            0,
            0,
            "",
            "No valid recipe",
            false);
        assertEquals(new MachineStatus.Health(Level.WARN, "No valid recipe"), idle.health(1000));
        final MachineStatus off = new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "EBF",
            true,
            true,
            false,
            false,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            false);
        assertEquals(
            "Switched off",
            off.health(1000)
                .text());
        final MachineStatus fault = new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "EBF",
            true,
            true,
            false,
            true,
            0,
            0,
            0,
            0,
            0,
            "",
            "Power lost",
            true);
        assertEquals(new MachineStatus.Health(Level.BAD, "Power lost"), fault.health(1000));
        final MachineStatus worn = new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "EBF",
            true,
            true,
            true,
            true,
            10,
            20,
            30,
            2,
            9000,
            "x",
            "",
            false);
        assertEquals(new MachineStatus.Health(Level.WARN, "2 maintenance issues"), worn.health(1000));
    }

    // endregion
}
