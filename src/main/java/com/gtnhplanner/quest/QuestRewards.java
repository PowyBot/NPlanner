package com.gtnhplanner.quest;

import java.util.function.Predicate;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * A reward whose item isn't installed is left out (as BeCom does, its DESIGN.md 15.5): a pack without Enhanced Loot
 * Bags gets the quests without their bags. Works on a quest as BetterQuesting saves it, without BetterQuesting's
 * classes, so it is tested on its own.
 */
public final class QuestRewards {

    private QuestRewards() {}

    /**
     * Drops the items of {@code quest}'s rewards that aren't installed, from an item reward's list and from a choice's
     * options alike, and any reward left with none, numbering the rest again. Rewards without items stay as they are.
     */
    public static NBTTagCompound withInstalled(final NBTTagCompound quest, final Predicate<String> installed) {
        final NBTTagList rewards = quest.getTagList("rewards", Constants.NBT.TAG_COMPOUND);
        final NBTTagList kept = new NBTTagList();
        for (int i = 0; i < rewards.tagCount(); i++) {
            final NBTTagCompound reward = rewards.getCompoundTagAt(i);
            final String list = reward.hasKey("choices") ? "choices" : "rewards";
            final NBTTagList items = reward.getTagList(list, Constants.NBT.TAG_COMPOUND);
            final NBTTagList present = new NBTTagList();
            for (int j = 0; j < items.tagCount(); j++) {
                final NBTTagCompound item = items.getCompoundTagAt(j);
                if (installed.test(item.getString("id"))) present.appendTag(item);
            }
            if (items.tagCount() > 0 && present.tagCount() == 0) continue;
            if (items.tagCount() > 0) reward.setTag(list, present);
            reward.setInteger("index", kept.tagCount());
            kept.appendTag(reward);
        }
        quest.setTag("rewards", kept);
        return quest;
    }
}
