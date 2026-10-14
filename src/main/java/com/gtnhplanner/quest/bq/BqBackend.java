package com.gtnhplanner.quest.bq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.google.common.collect.Maps;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.Config;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.quest.PlannerQuests;
import com.gtnhplanner.quest.QuestRewards;
import com.gtnhplanner.quest.QuestTask;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api.storage.BQ_Settings;
import betterquesting.api.utils.NBTConverter;
import betterquesting.api2.cache.QuestCache;
import betterquesting.api2.storage.DBEntry;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.network.handlers.NetChapterSync;
import betterquesting.network.handlers.NetQuestSync;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestInstance;
import betterquesting.questing.QuestLine;
import betterquesting.questing.QuestLineDatabase;
import betterquesting.questing.QuestLineEntry;

/**
 * The planner and BetterQuesting, the way BeCom does it (its {@code quest/bq/BqBackend}, DESIGN.md 15.5): registers
 * the planner's task type, puts the "NPlanner" questline into the book once the server has started, puts it back
 * whenever something removed it, and completes the planner's tasks.
 * <p>
 * Only ever touches the planner's own questline and quests, found by their fixed UUIDs (the files in
 * {@code assets/gtnhplanner/questline/}, written by {@code tools/quests/make_quests.py}). BQ keeps progress inside each
 * quest, so whoever removes them (a DefaultQuests reload) takes the progress too; a copy of it is kept and given back
 * when they are re-added, and BQ's own progress files are read for anything not copied yet.
 */
public final class BqBackend implements PlannerQuests.QuestBackend {

    private static final String ROOT = "/assets/gtnhplanner/questline/";
    private static final String INDEX = ROOT + "index.txt";
    private static final int TICKS_PER_SECOND = 20;

    private Questline questline;
    private final Map<UUID, NBTTagCompound> progressCopy = new HashMap<>();
    private long ticks;

    /** The questline as its files describe it. */
    private record Questline(UUID lineId, NBTTagCompound line, Map<UUID, NBTTagCompound> entries,
        Map<UUID, NBTTagCompound> quests) {}

    @Override
    public void init() {
        QuestingAPI.getAPI(ApiReference.TASK_REG)
            .register(FactoryTaskPlanner.INSTANCE);
        GtnhPlanner.LOG.info("[quests] registered the BetterQuesting task type {}", FactoryTaskPlanner.ID);
    }

    @Override
    public void serverStarted() {
        ticks = 0;
        questline = null;
        progressCopy.clear();
        if (!Config.questlineEnabled) {
            GtnhPlanner.LOG.info("[quests] the questline is switched off in the config: the quest book is left alone");
            return;
        }
        try {
            questline = load();
            ensure(true);
        } catch (final Throwable t) {
            GtnhPlanner.LOG.error("[quests] couldn't add the questline; carrying on without it", t);
            questline = null;
        }
    }

    @Override
    public void tick() {
        if (questline == null) return;
        ticks++;
        // A reload takes the quests with their progress, and the copy is all that brings it back: keep it fresh, so at
        // most a second of progress is lost.
        if (ticks % TICKS_PER_SECOND == 0) copyProgressSafely();
        if (ticks % (Config.questlineRecheckSeconds * (long) TICKS_PER_SECOND) != 0) return;
        try {
            ensure(false);
        } catch (final Throwable t) {
            GtnhPlanner.LOG.error("[quests] couldn't check the questline", t);
        }
    }

    @Override
    public void serverStopped() {
        questline = null;
        progressCopy.clear();
    }

    // region Adding the questline

    /** Adds whatever of the questline is missing, and copies the progress of what is there. */
    private void ensure(final boolean startup) {
        final Questline q = questline;
        final List<UUID> added = new ArrayList<>();
        q.quests()
            .entrySet()
            .stream()
            .filter(e -> !QuestDatabase.INSTANCE.containsKey(e.getKey()))
            .forEach(e -> {
                final IQuest quest = new QuestInstance();
                quest.readFromNBT(
                    withInstalledRewards(
                        (NBTTagCompound) e.getValue()
                            .copy()));
                QuestDatabase.INSTANCE.put(e.getKey(), quest);
                added.add(e.getKey());
            });
        IQuestLine line = QuestLineDatabase.INSTANCE.get(q.lineId());
        boolean lineAdded = false;
        if (line == null) {
            line = new QuestLine();
            line.readFromNBT(q.line());
            final List<Map.Entry<UUID, IQuestLine>> ordered = new ArrayList<>(
                QuestLineDatabase.INSTANCE.getOrderedEntries());
            ordered.add(Maps.immutableEntry(q.lineId(), line));
            QuestLineDatabase.INSTANCE.setOrderedEntries(ordered);
            lineAdded = true;
        }
        for (final Map.Entry<UUID, NBTTagCompound> e : q.entries()
            .entrySet()) {
            if (line.containsKey(e.getKey())) continue;
            line.put(e.getKey(), new QuestLineEntry(e.getValue()));
            lineAdded = true;
        }
        if (!added.isEmpty()) restoreProgress(added);
        copyProgress();
        if (added.isEmpty() && !lineAdded) return;
        GtnhPlanner.LOG.info(
            startup ? "[quests] added the questline to the quest book ({} quests new)"
                : "[quests] the questline was missing from the quest book; added it back ({} quests)",
            added.size());
        NetQuestSync.quickSync(null, true, true);
        NetChapterSync.sendSync(null, null);
        SaveLoadHandler.INSTANCE.markDirty();
    }

    /** A reward whose item isn't installed is left out. */
    private static NBTTagCompound withInstalledRewards(final NBTTagCompound quest) {
        return QuestRewards.withInstalled(quest, id -> Item.itemRegistry.getObject(id) != null);
    }

    // endregion

    // region Progress

    private void copyProgressSafely() {
        try {
            copyProgress();
        } catch (final Throwable t) {
            GtnhPlanner.LOG.error("[quests] couldn't copy the progress of the planner's quests", t);
        }
    }

    private void copyProgress() {
        for (final UUID id : questline.quests()
            .keySet()) {
            final IQuest quest = QuestDatabase.INSTANCE.get(id);
            if (quest != null) progressCopy.put(id, quest.writeProgressToNBT(new NBTTagCompound(), null));
        }
    }

    /** Gives re-added quests their progress back: from the copy, or from BQ's progress files on disk. */
    private void restoreProgress(final List<UUID> quests) {
        final List<UUID> fromDisk = new ArrayList<>();
        for (final UUID id : quests) {
            final NBTTagCompound copy = progressCopy.get(id);
            final IQuest quest = QuestDatabase.INSTANCE.get(id);
            if (copy != null && quest != null) quest.readProgressFromNBT(copy, false);
            else fromDisk.add(id);
        }
        if (!fromDisk.isEmpty()) restoreFromDisk(fromDisk);
    }

    private static void restoreFromDisk(final Collection<UUID> quests) {
        final File dir = BQ_Settings.curWorldDir == null ? null : new File(BQ_Settings.curWorldDir, "QuestProgress");
        final File[] files = dir == null ? null : dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (final File file : files) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                final JsonObject json = new JsonParser().parse(reader)
                    .getAsJsonObject();
                final NBTTagCompound nbt = NBTConverter.JSONtoNBT_Object(json, new NBTTagCompound(), true);
                final NBTTagList all = nbt.getTagList("questProgress", Constants.NBT.TAG_COMPOUND);
                final NBTTagList ours = new NBTTagList();
                for (int i = 0; i < all.tagCount(); i++) {
                    final NBTTagCompound entry = all.getCompoundTagAt(i);
                    NBTConverter.UuidValueType.QUEST.tryReadId(entry)
                        .filter(quests::contains)
                        .ifPresent(id -> ours.appendTag(entry));
                }
                if (ours.tagCount() > 0) QuestDatabase.INSTANCE.readProgressFromNBT(ours, true);
            } catch (final Exception e) {
                GtnhPlanner.LOG.warn("[quests] couldn't read quest progress from {}", file, e);
            }
        }
    }

    // endregion

    // region Completing tasks

    @Override
    public void complete(final QuestTask kind, final Collection<UUID> players) {
        if (players.isEmpty()) return;
        // Every quest with a planner task, the planner's own or a pack's that reused the task type.
        for (final Map.Entry<UUID, IQuest> entry : QuestDatabase.INSTANCE.entrySet()) {
            final IQuest quest = entry.getValue();
            for (final DBEntry<ITask> task : quest.getTasks()
                .getEntries()) {
                if (!(task.getValue() instanceof TaskPlanner planner) || planner.kind() != kind) continue;
                players.stream()
                    .filter(player -> !planner.isComplete(player) && quest.isUnlocked(player))
                    .forEach(player -> {
                        planner.setComplete(player);
                        markDirty(player, entry.getKey());
                    });
            }
        }
    }

    /** The player's quest book hears of it, when they are online. */
    private static void markDirty(final UUID player, final UUID questId) {
        final EntityPlayerMP online = QuestingAPI.getPlayer(player);
        if (online == null) return;
        final QuestCache cache = (QuestCache) online.getExtendedProperties(QuestCache.LOC_QUEST_CACHE.toString());
        if (cache != null) cache.markQuestDirty(questId);
    }

    // endregion

    // region Loading the files

    private static Questline load() throws Exception {
        final List<String> paths = new ArrayList<>();
        try (BufferedReader index = open(INDEX)) {
            index.lines()
                .filter(l -> !l.isBlank())
                .map(String::trim)
                .forEach(paths::add);
        }
        UUID lineId = null;
        NBTTagCompound lineTag = null;
        final Map<UUID, NBTTagCompound> entries = new LinkedHashMap<>();
        final Map<UUID, NBTTagCompound> quests = new LinkedHashMap<>();
        for (final String path : paths) {
            final NBTTagCompound tag;
            try (BufferedReader reader = open(ROOT + path)) {
                final JsonObject json = new JsonParser().parse(reader)
                    .getAsJsonObject();
                tag = NBTConverter.JSONtoNBT_Object(json, new NBTTagCompound(), true);
            }
            if (path.startsWith("DefaultQuests/QuestLines/") && path.endsWith("/QuestLine.json")) {
                lineId = NBTConverter.UuidValueType.QUEST_LINE.readId(tag);
                lineTag = tag;
            } else if (path.startsWith("DefaultQuests/QuestLines/")) {
                entries.put(NBTConverter.UuidValueType.QUEST.readId(tag), tag);
            } else if (path.startsWith("DefaultQuests/Quests/")) {
                quests.put(NBTConverter.UuidValueType.QUEST.readId(tag), tag);
            }
        }
        if (lineId == null) throw new IllegalStateException("the planner's questline files have no QuestLine.json");
        return new Questline(lineId, lineTag, entries, quests);
    }

    private static BufferedReader open(final String resource) {
        final InputStream in = BqBackend.class.getResourceAsStream(resource);
        if (in == null) throw new IllegalStateException("missing quest resource " + resource);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    // endregion
}
