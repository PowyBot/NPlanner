package com.gtnhplanner.quest;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.net.Proto;

/** When the planner's quest tasks are done, without BetterQuesting or a world, so it is tested on its own. */
public final class QuestChecks {

    /** "Un piano su carta": the cards a saved plan needs. */
    public static final int PLAN_CARDS = 3;
    /** "Tutto in regola": the placed cards a plan needs. */
    public static final int IN_ORDER_CARDS = 8;

    private QuestChecks() {}

    /** A plan of {@code cards} cards saved. */
    public static boolean planOnPaper(final int cards) {
        return cards >= PLAN_CARDS;
    }

    /**
     * A plan's {@code placements}, as the player sees them ({@code seen}, by placement key: only what is in their
     * sight): enough cards placed, and every placed machine seen and running. Green is the live badge's: the planner
     * has no linter yet.
     */
    public static boolean allInOrder(final List<Proto.Placement> placements, final Map<String, MachineStatus> seen) {
        final long cards = placements.stream()
            .map(Proto.Placement::nodeId)
            .distinct()
            .count();
        return cards >= IN_ORDER_CARDS && placements.stream()
            .allMatch(p -> green(seen.get(p.key())));
    }

    private static boolean green(@Nullable final MachineStatus s) {
        return s != null && s.health(-1)
            .level() == MachineStatus.Level.GOOD;
    }
}
