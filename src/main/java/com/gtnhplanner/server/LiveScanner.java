package com.gtnhplanner.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.net.Proto;

/**
 * The live status of placed machines: for each plan someone has open, the server looks at every machine it places, a
 * few each tick, each again every {@link #PERIOD} ticks, and tells the players who have it open what changed. Only
 * loaded chunks are looked at. Generic in who watches ({@code W}: a player on the server, anything in the tests) and
 * in how a block is looked at, so it is tested without a world.
 */
public final class LiveScanner<W> {

    /** How often each machine is looked at, in ticks. */
    public static final int PERIOD = 40;
    /** The most machines looked at in one tick, across every plan. */
    public static final int BUDGET = 64;

    /** Looks at a block. */
    public interface Prober {

        MachineStatus probe(int dim, int x, int y, int z);
    }

    /** Sends a status message to a watcher. */
    public interface Sender<W> {

        void send(W watcher, Proto.Status status);
    }

    private final Prober prober;
    private final Sender<W> sender;
    /** A plan's placements, by plan id (the store's). */
    private final Function<UUID, List<Proto.Placement>> placementsOf;

    private final Map<W, UUID> watching = new HashMap<>();
    private final Map<UUID, Scan> scans = new LinkedHashMap<>();
    private long tick;

    /** One watched plan: its machines, what each was found as, and when each is looked at next. */
    private static final class Scan {

        List<Proto.Placement> placements;
        final Map<String, MachineStatus> last = new HashMap<>();
        final Map<String, Long> due = new HashMap<>();
        final List<Proto.Live> changed = new ArrayList<>();
        int cursor;

        Scan(final List<Proto.Placement> placements) {
            this.placements = placements;
        }
    }

    public LiveScanner(final Prober prober, final Sender<W> sender,
        final Function<UUID, List<Proto.Placement>> placementsOf) {
        this.prober = prober;
        this.sender = sender;
        this.placementsOf = placementsOf;
    }

    /** {@code watcher} has {@code planId} open now (null: none). They are sent all that is known of it at once. */
    public void watch(final W watcher, @Nullable final UUID planId) {
        final UUID was = watching.get(watcher);
        if (Objects.equals(was, planId)) return;
        if (planId == null) watching.remove(watcher);
        else watching.put(watcher, planId);
        if (was != null && !watching.containsValue(was)) scans.remove(was);
        if (planId == null) return;
        Scan scan = scans.get(planId);
        if (scan == null) {
            scan = new Scan(placementsOf.apply(planId));
            scans.put(planId, scan);
        }
        final List<Proto.Live> all = new ArrayList<>();
        for (final Proto.Placement p : scan.placements) {
            final MachineStatus s = scan.last.get(p.key());
            if (s != null) all.add(new Proto.Live(p.nodeId(), p.index(), s));
        }
        sender.send(watcher, new Proto.Status(planId, true, all));
    }

    /** A watcher gone. */
    public void forget(final W watcher) {
        watch(watcher, null);
    }

    /** A plan's placements changed (a push): machines no longer placed are dropped, new ones looked at soon. */
    public void placementsChanged(final UUID planId) {
        final Scan scan = scans.get(planId);
        if (scan == null) return;
        scan.placements = placementsOf.apply(planId);
        final Set<String> keys = new java.util.HashSet<>();
        for (final Proto.Placement p : scan.placements) keys.add(p.key());
        scan.last.keySet()
            .retainAll(keys);
        scan.due.keySet()
            .retainAll(keys);
        scan.cursor = 0;
    }

    /** A plan deleted. */
    public void planRemoved(final UUID planId) {
        scans.remove(planId);
        watching.values()
            .removeIf(planId::equals);
    }

    /** Whom are watching what: for the debug command. */
    public Map<W, UUID> watching() {
        return java.util.Collections.unmodifiableMap(watching);
    }

    /** One server tick: looks at up to {@link #BUDGET} machines that are due, then sends what changed. */
    public void tick() {
        tick++;
        probeDue();
        scans.forEach(this::sendChanged);
    }

    /** Rounds the plans, a share of the budget each, so one huge plan does not starve the rest. */
    private void probeDue() {
        final List<Scan> list = new ArrayList<>(scans.values());
        if (list.isEmpty()) return;
        int budget = BUDGET;
        for (int round = 0; round < 4 && budget > 0; round++) budget = probeRound(list, budget);
    }

    /** One round over the plans; the budget left. */
    private int probeRound(final List<Scan> list, int budget) {
        for (final Scan scan : list) {
            if (budget <= 0) return 0;
            budget -= step(scan, Math.max(1, budget / list.size()));
        }
        return budget;
    }

    /** What changed in one plan since the last tick, to everyone watching it. */
    private void sendChanged(final UUID planId, final Scan scan) {
        if (scan.changed.isEmpty()) return;
        final Proto.Status status = new Proto.Status(planId, false, List.copyOf(scan.changed));
        scan.changed.clear();
        watching.entrySet()
            .stream()
            .filter(
                w -> w.getValue()
                    .equals(planId))
            .forEach(w -> sender.send(w.getKey(), status));
    }

    /** Looks at up to {@code max} due machines of one plan; how many it looked at. */
    private int step(final Scan scan, final int max) {
        final int n = scan.placements.size();
        int looked = 0;
        for (int seen = 0; seen < n && looked < max; seen++) {
            final Proto.Placement p = scan.placements.get(scan.cursor);
            scan.cursor = (scan.cursor + 1) % n;
            if (probeIfDue(scan, p)) looked++;
        }
        return looked;
    }

    /** Looks at one machine if its period is up, noting it when it changed; whether it was looked at. */
    private boolean probeIfDue(final Scan scan, final Proto.Placement p) {
        final String key = p.key();
        final Long due = scan.due.get(key);
        if (due != null && due > tick) return false;
        scan.due.put(key, tick + PERIOD);
        final MachineStatus status = probe(p);
        if (status.equals(scan.last.put(key, status))) return true;
        scan.changed.add(new Proto.Live(p.nodeId(), p.index(), status));
        return true;
    }

    private MachineStatus probe(final Proto.Placement p) {
        try {
            return prober.probe(p.dim(), p.x(), p.y(), p.z());
        } catch (final RuntimeException e) {
            return MachineStatus.unloaded();
        }
    }
}
