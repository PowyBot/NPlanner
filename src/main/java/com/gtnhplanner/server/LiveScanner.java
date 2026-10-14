package com.gtnhplanner.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.net.Proto;

/**
 * The live status of placed machines: for each plan someone has open, the server looks at the machines it places that
 * someone watching it sees ({@link Sight}), a few each tick, each again every {@link #PERIOD} ticks, and tells each
 * watcher what changed among the machines they see. A machine leaving a watcher's sight is sent to them with no status
 * (out of sight); one coming into it, with what is known of it. Only loaded chunks are looked at. Generic in who
 * watches ({@code W}: a player on the server, anything in the tests) and in how a block is looked at, so it is tested
 * without a world.
 */
public final class LiveScanner<W> {

    /** How often each machine is looked at, in ticks. */
    public static final int PERIOD = 40;
    /** The most machines looked at in one tick, across every plan. */
    public static final int BUDGET = 64;
    /** How often what each watcher sees is worked out again, in ticks: players walk. */
    public static final int SIGHT_PERIOD = 20;

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
    /** Where a watcher is now. */
    private final Function<W, Sight.Viewer> viewerOf;
    private final Sight sight;

    private final Map<W, UUID> watching = new HashMap<>();
    /** The keys of the machines each watcher sees, of the plan they watch. */
    private final Map<W, Set<String>> views = new HashMap<>();
    /** What each watcher is to be told besides what changed: machines coming into or going out of their sight. */
    private final Map<W, List<Proto.Live>> outbox = new HashMap<>();
    private final Map<UUID, Scan> scans = new LinkedHashMap<>();
    private long tick;

    /** One watched plan: its machines, those someone sees, what each was found as, and when each is looked at next. */
    private static final class Scan {

        List<Proto.Placement> placements;
        Set<String> wanted = Set.of();
        final Map<String, MachineStatus> last = new HashMap<>();
        final Map<String, Long> due = new HashMap<>();
        final List<Proto.Live> changed = new ArrayList<>();
        int cursor;

        Scan(final List<Proto.Placement> placements) {
            this.placements = placements;
        }
    }

    public LiveScanner(final Prober prober, final Sender<W> sender,
        final Function<UUID, List<Proto.Placement>> placementsOf, final Function<W, Sight.Viewer> viewerOf,
        final Sight sight) {
        this.prober = prober;
        this.sender = sender;
        this.placementsOf = placementsOf;
        this.viewerOf = viewerOf;
        this.sight = sight;
    }

    /**
     * {@code watcher} has {@code planId} open now (null: none). They are sent at once all that is known of the machines
     * they see, and the rest as out of sight.
     */
    public void watch(final W watcher, @Nullable final UUID planId) {
        final UUID was = watching.get(watcher);
        if (Objects.equals(was, planId)) return;
        views.remove(watcher);
        outbox.remove(watcher);
        if (planId == null) watching.remove(watcher);
        else watching.put(watcher, planId);
        if (was != null) rewant(was);
        if (planId == null) return;
        final Scan scan = scans.computeIfAbsent(planId, id -> new Scan(placementsOf.apply(id)));
        final Set<String> view = viewOf(watcher, scan);
        views.put(watcher, view);
        rewant(planId);
        final List<Proto.Live> all = scan.placements.stream()
            .map(p -> view.contains(p.key()) ? known(scan, p) : away(p))
            .filter(Objects::nonNull)
            .toList();
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
        final Set<String> keys = keys(scan.placements);
        scan.last.keySet()
            .retainAll(keys);
        scan.due.keySet()
            .retainAll(keys);
        scan.cursor = 0;
        watchersOf(planId).forEach(w -> look(w, scan));
        rewant(planId);
    }

    /** A plan deleted. */
    public void planRemoved(final UUID planId) {
        scans.remove(planId);
        watchersOf(planId).forEach(w -> {
            watching.remove(w);
            views.remove(w);
            outbox.remove(w);
        });
    }

    /** Whom are watching what: for the debug command. */
    public Map<W, UUID> watching() {
        return Collections.unmodifiableMap(watching);
    }

    /** What is known of the machines a watcher sees, by placement key. */
    public Map<String, MachineStatus> seenBy(final W watcher) {
        final Scan scan = scans.get(watching.get(watcher));
        if (scan == null) return Map.of();
        return views.getOrDefault(watcher, Set.of())
            .stream()
            .filter(scan.last::containsKey)
            .collect(Collectors.toMap(k -> k, scan.last::get));
    }

    /** How many machines of their plan a watcher sees: for the debug command. */
    public int seen(final W watcher) {
        return views.getOrDefault(watcher, Set.of())
            .size();
    }

    /**
     * One server tick: now and then works out again what everyone sees, looks at up to {@link #BUDGET} machines that
     * are due, then sends what changed.
     */
    public void tick() {
        tick++;
        if (tick % SIGHT_PERIOD == 0) lookAround();
        probeDue();
        scans.forEach(this::sendChanged);
    }

    /** What each watcher sees, again: what came into or went out of their sight goes in their outbox. */
    private void lookAround() {
        watching.forEach((w, planId) -> look(w, scans.get(planId)));
        List.copyOf(scans.keySet())
            .forEach(this::rewant);
    }

    /** One watcher's sight of one plan, again. */
    private void look(final W watcher, @Nullable final Scan scan) {
        if (scan == null) return;
        final Set<String> was = views.getOrDefault(watcher, Set.of());
        final Set<String> now = viewOf(watcher, scan);
        views.put(watcher, now);
        if (now.equals(was)) return;
        final List<Proto.Live> news = outbox.computeIfAbsent(watcher, k -> new ArrayList<>());
        scan.placements.stream()
            .filter(p -> was.contains(p.key()) != now.contains(p.key()))
            .map(p -> now.contains(p.key()) ? known(scan, p) : away(p))
            .filter(Objects::nonNull)
            .forEach(news::add);
    }

    /** The keys of a plan's machines a watcher sees from where they are now. */
    private Set<String> viewOf(final W watcher, final Scan scan) {
        final Sight.Viewer viewer = viewerOf.apply(watcher);
        return scan.placements.stream()
            .filter(p -> sight.sees(viewer, p))
            .map(Proto.Placement::key)
            .collect(Collectors.toSet());
    }

    /**
     * The machines of a plan anyone watching it sees. A machine no one sees any more is forgotten, so it is looked at
     * afresh when someone does again; a plan no one watches is dropped.
     */
    private void rewant(final UUID planId) {
        final Scan scan = scans.get(planId);
        if (scan == null) return;
        final List<W> watchers = watchersOf(planId);
        if (watchers.isEmpty()) {
            scans.remove(planId);
            return;
        }
        final Set<String> wanted = new HashSet<>();
        watchers.forEach(w -> wanted.addAll(views.getOrDefault(w, Set.of())));
        scan.wanted = wanted;
        scan.last.keySet()
            .retainAll(wanted);
        scan.due.keySet()
            .retainAll(wanted);
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

    /** What changed in one plan since the last tick, and what came into or went out of sight, to each watcher. */
    private void sendChanged(final UUID planId, final Scan scan) {
        final List<Proto.Live> changed = List.copyOf(scan.changed);
        scan.changed.clear();
        watchersOf(planId).forEach(w -> {
            final Set<String> view = views.getOrDefault(w, Set.of());
            final List<Proto.Live> news = Objects.requireNonNullElseGet(outbox.remove(w), ArrayList::new);
            changed.stream()
                .filter(l -> view.contains(Proto.Placement.key(l.nodeId(), l.index())))
                .forEach(news::add);
            if (!news.isEmpty()) sender.send(w, new Proto.Status(planId, false, news));
        });
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

    /** Looks at one machine someone sees if its period is up, noting it when it changed; whether it was looked at. */
    private boolean probeIfDue(final Scan scan, final Proto.Placement p) {
        final String key = p.key();
        if (!scan.wanted.contains(key)) return false;
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

    private List<W> watchersOf(final UUID planId) {
        return watching.entrySet()
            .stream()
            .filter(
                w -> w.getValue()
                    .equals(planId))
            .map(Map.Entry::getKey)
            .toList();
    }

    /** What is known of a machine, or null when nothing is yet. */
    @Nullable
    private static Proto.Live known(final Scan scan, final Proto.Placement p) {
        final MachineStatus s = scan.last.get(p.key());
        return s == null ? null : new Proto.Live(p.nodeId(), p.index(), s);
    }

    /** A machine out of sight: no status. */
    private static Proto.Live away(final Proto.Placement p) {
        return new Proto.Live(p.nodeId(), p.index(), null);
    }

    private static Set<String> keys(final List<Proto.Placement> placements) {
        return placements.stream()
            .map(Proto.Placement::key)
            .collect(Collectors.toSet());
    }
}
