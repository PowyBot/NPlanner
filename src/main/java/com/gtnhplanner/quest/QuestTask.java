package com.gtnhplanner.quest;

/**
 * What the planner's own BetterQuesting task type waits for (NPlanner-notes/PROGRESSION.md §5). Saved by name in quest
 * files, so constants can be added but never renamed. Quests whose task nothing reports yet are declared in
 * {@code tools/quests/make_quests.py} but left out of the book until something does.
 */
public enum QuestTask {
    /** A plan of {@link QuestChecks#PLAN_CARDS} cards or more saved: whoever saved it. */
    PLAN_ON_PAPER,
    /** A job created, assigned and closed on a plan's board: nothing reports it until the board exists. */
    TEAMWORK,
    /**
     * A plan with {@link QuestChecks#IN_ORDER_CARDS} cards or more placed, every machine seen and green: who sees it.
     */
    ALL_IN_ORDER,
    /** A machine read from beyond sight through a BeCom data node: nothing reports it until BeCom has its API. */
    EXTRA_EYE,
    /** A machine read from another dimension over BeCom: nothing reports it until BeCom has its API. */
    HOME_FROM_THE_MOON
}
