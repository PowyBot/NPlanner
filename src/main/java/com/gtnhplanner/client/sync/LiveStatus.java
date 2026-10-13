package com.gtnhplanner.client.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.net.Proto;

/**
 * How the placed machines of the plan being watched are doing, as the server last said ({@link SharedSync}): by plan,
 * then by card and placement. Read by the board's cards and the world overlay ({@code ui/world/LiveBadge}).
 */
public final class LiveStatus {

    private static final Map<UUID, Map<String, MachineStatus>> BY_PLAN = new HashMap<>();

    private LiveStatus() {}

    static void apply(final Proto.Status s) {
        Map<String, MachineStatus> plan = BY_PLAN.get(s.planId());
        if (plan == null || s.full()) {
            plan = new HashMap<>();
            BY_PLAN.put(s.planId(), plan);
        }
        for (final Proto.Live l : s.machines()) plan.put(Proto.Placement.key(l.nodeId(), l.index()), l.status());
    }

    static void clear() {
        BY_PLAN.clear();
    }

    /** One placement's status, or null when the server has said nothing of it. */
    @Nullable
    public static MachineStatus of(final UUID planId, final UUID nodeId, final int index) {
        final Map<String, MachineStatus> plan = BY_PLAN.get(planId);
        return plan == null ? null : plan.get(Proto.Placement.key(nodeId, index));
    }

    /** Whether anything is known of this plan's machines. */
    public static boolean any(final UUID planId) {
        final Map<String, MachineStatus> plan = BY_PLAN.get(planId);
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
        MachineStatus.Health worst = null;
        int known = 0, running = 0;
        for (int i = 0; i < links.size(); i++) {
            final MachineStatus s = of(planId, nodeId, i);
            if (s == null) continue;
            final MachineStatus.Health h = s.health(expectedMetaId);
            if (h.level() == MachineStatus.Level.UNKNOWN) continue;
            known++;
            if (h.level() == MachineStatus.Level.GOOD) running++;
            if (worst == null || rank(h.level()) > rank(worst.level())) worst = h;
        }
        if (worst == null) return null;
        if (known > 1) return new MachineStatus.Health(
            worst.level(),
            worst.level() == MachineStatus.Level.GOOD ? running + "/" + known + " running"
                : worst.text() + " · " + running + "/" + known + " running");
        return worst;
    }

    private static int rank(final MachineStatus.Level l) {
        return switch (l) {
            case UNKNOWN -> 0;
            case GOOD -> 1;
            case WARN -> 2;
            case BAD -> 3;
        };
    }
}
