package com.gtnhplanner.server;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.live.MachineStatus;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.util.shutdown.ShutDownReason;

/**
 * Reads a GregTech machine's state on the server: whether its structure is formed, whether it runs, how far, at what
 * EU/t, its maintenance, what it makes and why it stopped. Only {@link MachineProbe} reaches it, and only with
 * GregTech loaded. Every read is guarded on its own: a field or method an older GregTech lacks costs that one fact,
 * never the probe.
 */
final class GtProbe {

    private static boolean warned;

    private GtProbe() {}

    /** The machine's state, or null when the tile is not GregTech's. */
    @Nullable
    static MachineStatus probe(final TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity base)) return null;
        final IMetaTileEntity meta = base.getMetaTileEntity();
        if (meta == null) return MachineStatus.block("GregTech block");
        return new Reading(base, meta).identity()
            .multiblock()
            .basicMachine()
            .recipeCheck()
            .shutDown()
            .build();
    }

    /** One machine read fact by fact, each step guarded on its own and returning the reading. */
    private static final class Reading {

        private final IGregTechTileEntity base;
        private final IMetaTileEntity meta;
        private int metaId = -1, progress, maxProgress, maintenance, efficiency;
        private String name = "", outputs = "", message = "";
        private boolean multiblock, formed = true, active, allowed = true, critical;
        private long eu;

        Reading(final IGregTechTileEntity base, final IMetaTileEntity meta) {
            this.base = base;
            this.meta = meta;
        }

        Reading identity() {
            return guarded(() -> {
                metaId = base.getMetaTileID();
                name = meta.getLocalName();
                active = base.isActive();
                allowed = base.isAllowedToWork();
            });
        }

        Reading multiblock() {
            if (!(meta instanceof MTEMultiBlockBase m)) return this;
            return guarded(() -> {
                multiblock = true;
                formed = m.mMachine;
                progress = m.mProgresstime;
                maxProgress = m.mMaxProgresstime;
                // A multiblock keeps what it draws as a negative number, what it makes as a positive one.
                eu = -(long) m.mEUt;
                efficiency = m.mEfficiency;
                maintenance = Math.max(0, m.getIdealStatus() - m.getRepairStatus());
                outputs = names(m.mOutputItems, m.mOutputFluids);
            });
        }

        Reading basicMachine() {
            if (!(meta instanceof MTEBasicMachine b)) return this;
            return guarded(() -> {
                progress = b.mProgresstime;
                maxProgress = b.mMaxProgresstime;
                eu = b.mEUt;
                outputs = names(b.mOutputItems, b.mOutputFluid == null ? null : new FluidStack[] { b.mOutputFluid });
                if (b.mStuttering) message = "Not enough power";
            });
        }

        Reading recipeCheck() {
            if (active || !(meta instanceof MTEMultiBlockBase m)) return this;
            return guarded(() -> {
                final CheckRecipeResult check = m.getCheckRecipeResult();
                if (check == null || check.wasSuccessful()) return;
                message = check.getDisplayString();
            });
        }

        Reading shutDown() {
            if (active) return this;
            return guarded(() -> {
                if (!base.wasShutdown()) return;
                final ShutDownReason reason = base.getLastShutDownReason();
                if (reason == null) return;
                critical = reason.wasCritical();
                final String why = reason.getDisplayString();
                if (why != null && !why.isEmpty()) message = why;
            });
        }

        MachineStatus build() {
            // An idle machine's last recipe is not what it runs.
            if (!active) {
                progress = 0;
                maxProgress = 0;
                eu = 0;
                outputs = "";
            }
            return new MachineStatus(
                MachineStatus.MACHINE,
                metaId,
                name == null ? "" : name,
                multiblock,
                formed,
                active,
                allowed,
                progress,
                maxProgress,
                eu,
                maintenance,
                efficiency,
                outputs,
                clean(message),
                critical);
        }

        private Reading guarded(final Runnable read) {
            try {
                read.run();
            } catch (final Throwable t) {
                warn(t);
            }
            return this;
        }
    }

    /** What a recipe makes, by name: the first three, then how many more. */
    private static String names(@Nullable final ItemStack[] items, @Nullable final FluidStack[] fluids) {
        final List<String> out = new ArrayList<>();
        if (items != null)
            for (final ItemStack s : items) addName(out, s == null || s.getItem() == null ? null : s::getDisplayName);
        if (fluids != null) for (final FluidStack f : fluids)
            addName(out, f == null || f.getFluid() == null ? null : f::getLocalizedName);
        if (out.size() <= 3) return String.join(", ", out);
        return String.join(", ", out.subList(0, 3)) + " +" + (out.size() - 3);
    }

    private static void addName(final List<String> out, @Nullable final java.util.function.Supplier<String> name) {
        if (name == null) return;
        try {
            out.add(name.get());
        } catch (final RuntimeException ignored) {}
    }

    /** A machine's message without its colour codes and line breaks. */
    private static String clean(final String s) {
        return s == null ? ""
            : s.replaceAll("§.", "")
                .replace('\n', ' ')
                .trim();
    }

    private static void warn(final Throwable t) {
        if (t instanceof VirtualMachineError vm) throw vm;
        if (warned) return;
        warned = true;
        GtnhPlanner.LOG.warn("[live] this GregTech does not show everything about its machines", t);
    }
}
