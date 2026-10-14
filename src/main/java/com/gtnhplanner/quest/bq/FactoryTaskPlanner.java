package com.gtnhplanner.quest.bq;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import betterquesting.api.questing.tasks.ITask;
import betterquesting.api2.registry.IFactoryData;

/** Makes {@link TaskPlanner}s for BetterQuesting, under the task id the planner's quest files use. */
public final class FactoryTaskPlanner implements IFactoryData<ITask, NBTTagCompound> {

    public static final ResourceLocation ID = new ResourceLocation("gtnhplanner", "event");
    public static final FactoryTaskPlanner INSTANCE = new FactoryTaskPlanner();

    private FactoryTaskPlanner() {}

    @Override
    public ResourceLocation getRegistryName() {
        return ID;
    }

    @Override
    public TaskPlanner createNew() {
        return new TaskPlanner();
    }

    @Override
    public TaskPlanner loadFromData(final NBTTagCompound nbt) {
        final TaskPlanner task = new TaskPlanner();
        task.readFromNBT(nbt);
        return task;
    }
}
