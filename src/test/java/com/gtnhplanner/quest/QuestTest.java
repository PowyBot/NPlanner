package com.gtnhplanner.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.net.Proto;

/** When the planner's quest tasks are done, and the quest files the book gets. */
class QuestTest {

    private static MachineStatus running(final boolean active) {
        return new MachineStatus(
            MachineStatus.MACHINE,
            1000,
            "Electric Blast Furnace",
            true,
            true,
            active,
            true,
            50,
            200,
            1920,
            0,
            10000,
            "",
            "",
            false);
    }

    /** {@code cards} cards placed once each, the first twice. */
    private static List<Proto.Placement> placed(final int cards) {
        final List<Proto.Placement> out = new ArrayList<>();
        for (int i = 0; i < cards; i++) out.add(new Proto.Placement(new UUID(0, i), 0, 0, i, 64, 0));
        out.add(new Proto.Placement(new UUID(0, 0), 1, 0, 0, 64, 5));
        return out;
    }

    private static Map<String, MachineStatus> allRunning(final List<Proto.Placement> placed) {
        final Map<String, MachineStatus> seen = new HashMap<>();
        placed.forEach(p -> seen.put(p.key(), running(true)));
        return seen;
    }

    @Test
    void aPlanOnPaperHasThreeCards() {
        assertFalse(QuestChecks.planOnPaper(2));
        assertTrue(QuestChecks.planOnPaper(3));
    }

    @Test
    void allInOrderIsEightCardsEveryMachineSeenAndRunning() {
        final List<Proto.Placement> eight = placed(8);
        assertTrue(QuestChecks.allInOrder(eight, allRunning(eight)));
        // Seven cards placed nine times are still seven cards.
        final List<Proto.Placement> seven = placed(7);
        assertFalse(QuestChecks.allInOrder(seven, allRunning(seven)));
        // One idle.
        final Map<String, MachineStatus> idle = allRunning(eight);
        idle.put(
            eight.get(3)
                .key(),
            running(false));
        assertFalse(QuestChecks.allInOrder(eight, idle));
        // One out of sight (nothing known of it).
        final Map<String, MachineStatus> away = allRunning(eight);
        away.remove(
            eight.get(8)
                .key());
        assertFalse(QuestChecks.allInOrder(eight, away));
    }

    @Test
    void aRewardWhoseItemIsNotInstalledIsLeftOut() {
        final NBTTagCompound quest = new NBTTagCompound();
        final NBTTagList rewards = new NBTTagList();
        rewards.appendTag(reward("enhancedlootbags:lootbag"));
        rewards.appendTag(reward("minecraft:paper"));
        quest.setTag("rewards", rewards);
        final NBTTagList kept = QuestRewards.withInstalled(quest, id -> id.startsWith("minecraft:"))
            .getTagList("rewards", Constants.NBT.TAG_COMPOUND);
        assertEquals(1, kept.tagCount());
        assertEquals(
            0,
            kept.getCompoundTagAt(0)
                .getInteger("index"));
        assertEquals(
            "minecraft:paper",
            kept.getCompoundTagAt(0)
                .getTagList("rewards", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0)
                .getString("id"));
    }

    private static NBTTagCompound reward(final String item) {
        final NBTTagCompound stack = new NBTTagCompound();
        stack.setString("id", item);
        final NBTTagList items = new NBTTagList();
        items.appendTag(stack);
        final NBTTagCompound reward = new NBTTagCompound();
        reward.setString("rewardID", "bq_standard:item");
        reward.setTag("rewards", items);
        return reward;
    }

    /** The book gets the quests whose task is reported, each naming a task this planner knows. */
    @Test
    void theQuestFilesNameKnownTasksAndLeaveOutTheWaitingOnes() throws Exception {
        final List<String> index = lines("/assets/gtnhplanner/questline/index.txt");
        final List<String> quests = index.stream()
            .filter(p -> p.startsWith("DefaultQuests/Quests/"))
            .toList();
        assertEquals(2, quests.size(), "A Plan on Paper and All in Order; the other three wait");
        final List<String> tasks = new ArrayList<>();
        for (final String q : quests) {
            final String json = String.join("\n", lines("/assets/gtnhplanner/questline/" + q));
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"plannerTask:8\": \"(\\w+)\"")
                .matcher(json);
            assertTrue(m.find(), q);
            tasks.add(
                QuestTask.valueOf(m.group(1))
                    .name());
            assertTrue(json.contains("\"taskID:8\": \"gtnhplanner:event\""), q);
        }
        assertEquals(List.of("PLAN_ON_PAPER", "ALL_IN_ORDER"), tasks);
    }

    private static List<String> lines(final String resource) throws Exception {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(QuestTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8))) {
            return r.lines()
                .filter(l -> !l.isBlank())
                .toList();
        }
    }
}
