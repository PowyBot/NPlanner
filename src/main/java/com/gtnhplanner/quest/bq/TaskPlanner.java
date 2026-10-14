package com.gtnhplanner.quest.bq;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import com.gtnhplanner.quest.QuestTask;

import betterquesting.api.questing.IQuest;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.client.gui.panels.content.PanelTextBox;
import betterquesting.api2.utils.ParticipantInfo;
import bq_standard.tasks.base.TaskBase;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The planner's own BetterQuesting task: done when the planner says the thing happened ({@code quest/PlannerQuests}),
 * never by polling. The quest file names what it waits for; a name this planner doesn't know (from a newer one) is
 * kept and never completes.
 */
public class TaskPlanner extends TaskBase {

    static final String KEY = "plannerTask";

    private String kindName = QuestTask.PLAN_ON_PAPER.name();

    @Nullable
    public QuestTask kind() {
        try {
            return QuestTask.valueOf(kindName);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(final NBTTagCompound nbt) {
        nbt.setString(KEY, kindName);
        return nbt;
    }

    @Override
    public void readFromNBT(final NBTTagCompound nbt) {
        if (nbt.hasKey(KEY)) kindName = nbt.getString(KEY);
    }

    @Override
    public ResourceLocation getFactoryID() {
        return FactoryTaskPlanner.ID;
    }

    @Override
    public String getUnlocalisedName() {
        return "gtnhplanner.task.name";
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IGuiPanel getTaskGui(final IGuiRect rect, final Map.Entry<UUID, IQuest> quest) {
        return new PanelTextBox(
            rect,
            StatCollector.translateToLocal("gtnhplanner.task." + kindName.toLowerCase(Locale.ROOT)));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public GuiScreen getTaskEditor(final GuiScreen parent, final Map.Entry<UUID, IQuest> quest) {
        return null;
    }

    @Override
    public void detect(final ParticipantInfo participant, final Map.Entry<UUID, IQuest> quest) {
        // Completed from the planner's events, never by polling.
    }
}
