package com.gtnhplanner.client.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.live.Seen;
import com.gtnhplanner.net.Proto;

/**
 * How the placed machines of the plan being watched are doing, as the server last said ({@link SharedSync}): by plan,
 * then by card and placement, each with whether it is in the player's sight ({@link Seen}). Read by the board's cards
 * and the world overlay ({@code ui/world/LiveBadge}).
 */
public final class LiveStatus {

    private static final Map<UUID, Map<String, Seen>> BY_PLAN = new HashMap<>();

    private LiveStatus() {}

    static void apply(final Proto.Status s) {
        apply(BY_PLAN, s, System.currentTimeMillis());
    }

    /**
     * Takes a status message into {@code byPlan} at {@code now}: a machine with a status is in sight, one without went
     * out of it, keeping what was seen of it. A full message replaces the plan's machines, but keeps what was seen of
     * those now out of sight.
     */
    static void apply(final Map<UUID, Map<String, Seen>> byPlan, final Proto.Status s, final long now) {
        final Map<String, Seen> before = byPlan.getOrDefault(s.planId(), Map.of());
        final Map<String, Seen> plan = s.full() ? new HashMap<>() : new HashMap<>(before);
        for (final Proto.Live l : s.machines()) {
            final String key = Proto.Placement.key(l.nodeId(), l.index());
            plan.put(key, l.status() == null ? Seen.hidden(before.get(key), now) : Seen.live(l.status()));
        }
        byPlan.put(s.planId(), plan);
    }

    static void clear() {
        BY_PLAN.clear();
    }

    /** What the player has seen of one placement, or null when the server has said nothing of it. */
    @Nullable
    public static Seen seen(final UUID planId, final UUID nodeId, final int index) {
        final Map<String, Seen> plan = BY_PLAN.get(planId);
        return plan == null ? null : plan.get(Proto.Placement.key(nodeId, index));
    }

    /** One placement's status worth showing now (in sight, or out of it and not yet expired), or null. */
    @Nullable
    public static MachineStatus of(final UUID planId, final UUID nodeId, final int index) {
        final Seen s = seen(planId, nodeId, index);
        return s == null ? null : s.current(System.currentTimeMillis());
    }

    /** Whether anything is known of this plan's machines. */
    public static boolean any(final UUID planId) {
        final Map<String, Seen> plan = BY_PLAN.get(planId);
        return plan != null && !plan.isEmpty();
    }

    /**
     * The GregTech machine id a card's machine is (its item's damage), or -1 when it is not a GregTech machine: another
     * machine built on its spot is the wrong one.
     */
    public static int expectedMetaId(@Nullable final ItemStack machine) {
        if (machine == null || machine.getItem() == null) return -1;
        try {
            return "gt.blockmachines".equals(
                machine.getItem()
                    .getUnlocalizedName()
                    .replaceFirst("^tile\\.", "")) ? machine.getItemDamage() : -1;
        } catch (final RuntimeException e) {
            return -1;
        }
    }

    /**
     * A card's badge over all its placements ({@code links}, the card's node's {@code worldLinks}): the worst of them,
     * saying how many run when there are several; null when none is known.
     */
    @Nullable
    public static MachineStatus.Health card(final UUID planId, final UUID nodeId, final List<int[]> links,
        final int expectedMetaId) {
        final long now = System.currentTimeMillis();
        MachineStatus.Health worst = null, away = null;
        int known = 0, running = 0;
        for (int i = 0; i < links.size(); i++) {
            final Seen s = seen(planId, nodeId, i);
            if (s == null) continue;
            final MachineStatus.Health h = s.health(expectedMetaId, now);
            if (h.level() == MachineStatus.Level.UNKNOWN) continue;
            if (h.level() == MachineStatus.Level.AWAY) {
                away = h;
                continue;
            }
            known++;
            if (h.level() == MachineStatus.Level.GOOD) running++;
            if (worst == null || rank(h.level()) > rank(worst.level())) worst = h;
        }
        // Out of sight shows only when nothing of the card is in sight.
        if (worst == null) return away;
        if (known > 1) return new MachineStatus.Health(
            worst.level(),
            worst.level() == MachineStatus.Level.GOOD ? running + "/" + known + " running"
                : worst.text() + " · " + running + "/" + known + " running");
        return worst;
    }

    private static int rank(final MachineStatus.Level l) {
        return switch (l) {
            case UNKNOWN, AWAY -> 0;
            case GOOD -> 1;
            case WARN -> 2;
            case BAD -> 3;
        };
    }
}
