package com.gtnhplanner.quest;

import java.util.Collection;
import java.util.UUID;

import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.Loader;

/**
 * Where the planner reports what its quest tasks wait for, as BeCom does (its {@code quest/BeComQuests}).
 * BetterQuesting is optional: without it every report is dropped. Its classes are touched only from {@code quest/bq/},
 * loaded by name when BQ is installed. Server thread only.
 */
public final class PlannerQuests {

    public static final String BQ_MODID = "betterquesting";

    private static QuestBackend backend = QuestBackend.NONE;

    private PlannerQuests() {}

    /** Picks the BetterQuesting backend when BQ is installed; once, at init. */
    public static void init() {
        if (!Loader.isModLoaded(BQ_MODID)) {
            GtnhPlanner.LOG.info("[quests] BetterQuesting isn't installed: the planner's quests are off");
            return;
        }
        try {
            backend = (QuestBackend) Class.forName("com.gtnhplanner.quest.bq.BqBackend")
                .getConstructor()
                .newInstance();
            backend.init();
        } catch (final Throwable t) {
            GtnhPlanner.LOG.error("[quests] BetterQuesting integration failed to start; carrying on without it", t);
            backend = QuestBackend.NONE;
        }
    }

    public static QuestBackend backend() {
        return backend;
    }

    /** Whether anything listens: checks that cost something are skipped otherwise. */
    public static boolean on() {
        return backend != QuestBackend.NONE;
    }

    /** {@code task} happened for {@code players}. */
    public static void complete(final QuestTask task, final Collection<UUID> players) {
        try {
            backend.complete(task, players);
        } catch (final Throwable t) {
            GtnhPlanner.LOG.error("[quests] couldn't complete the task {}", task, t);
        }
    }

    /** The parts that need BetterQuesting, behind an interface so nothing else loads BQ's classes. */
    public interface QuestBackend {

        QuestBackend NONE = new QuestBackend() {};

        default void init() {}

        default void serverStarted() {}

        default void tick() {}

        default void serverStopped() {}

        default void complete(final QuestTask task, final Collection<UUID> players) {}
    }
}
