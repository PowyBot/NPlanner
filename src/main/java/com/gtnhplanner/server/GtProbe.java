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
        int metaId = -1;
        String name = "";
        boolean multiblock = false, formed = true, active = false, allowed = true, critical = false;
        int progress = 0, maxProgress = 0, maintenance = 0, efficiency = 0;
        long eu = 0;
        String outputs = "", message = "";
        try {
            metaId = base.getMetaTileID();
            name = meta.getLocalName();
            active = base.isActive();
            allowed = base.isAllowedToWork();
        } catch (final Throwable t) {
            warn(t);
        }
        try {
            if (meta instanceof MTEMultiBlockBase m) {
                multiblock = true;
                formed = m.mMachine;
                progress = m.mProgresstime;
                maxProgress = m.mMaxProgresstime;
                // A multiblock keeps what it draws as a negative number, what it makes as a positive one.
                eu = -(long) m.mEUt;
                efficiency = m.mEfficiency;
                maintenance = Math.max(0, m.getIdealStatus() - m.getRepairStatus());
                outputs = names(m.mOutputItems, m.mOutputFluids);
            } else if (meta instanceof MTEBasicMachine b) {
                progress = b.mProgresstime;
                maxProgress = b.mMaxProgresstime;
                eu = b.mEUt;
                outputs = names(b.mOutputItems, b.mOutputFluid == null ? null : new FluidStack[] { b.mOutputFluid });
                if (b.mStuttering) message = "Not enough power";
            }
        } catch (final Throwable t) {
            warn(t);
        }
        try {
            if (meta instanceof MTEMultiBlockBase m && !active) {
                final CheckRecipeResult check = m.getCheckRecipeResult();
                if (check != null && !check.wasSuccessful()) message = check.getDisplayString();
            }
        } catch (final Throwable t) {
            warn(t);
        }
        try {
            if (!active && base.wasShutdown()) {
                final ShutDownReason reason = base.getLastShutDownReason();
                if (reason != null) {
                    critical = reason.wasCritical();
                    final String why = reason.getDisplayString();
                    if (why != null && !why.isEmpty()) message = why;
                }
            }
        } catch (final Throwable t) {
            warn(t);
        }
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

    /** What a recipe makes, by name: the first three, then how many more. */
    private static String names(@Nullable final ItemStack[] items, @Nullable final FluidStack[] fluids) {
        final List<String> out = new ArrayList<>();
        if (items != null) for (final ItemStack s : items) if (s != null && s.getItem() != null) try {
            out.add(s.getDisplayName());
        } catch (final RuntimeException ignored) {}
        if (fluids != null) for (final FluidStack f : fluids) if (f != null && f.getFluid() != null) try {
            out.add(f.getLocalizedName());
        } catch (final RuntimeException ignored) {}
        if (out.size() <= 3) return String.join(", ", out);
        return String.join(", ", out.subList(0, 3)) + " +" + (out.size() - 3);
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
