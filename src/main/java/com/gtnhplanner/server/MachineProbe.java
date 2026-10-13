package com.gtnhplanner.server;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.live.MachineStatus;

import cpw.mods.fml.common.Loader;

/**
 * Looks at the block a card is placed on, on the server: nothing loaded there (unknown), air, some block, or a
 * GregTech machine with its state ({@link GtProbe}). Never loads a chunk.
 */
final class MachineProbe {

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");
    private static boolean gtBroken;

    private MachineProbe() {}

    static MachineStatus probe(final int dim, final int x, final int y, final int z) {
        final World world = DimensionManager.getWorld(dim);
        if (world == null || y < 0 || y >= 256 || !world.blockExists(x, y, z)) return MachineStatus.unloaded();
        final Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return MachineStatus.nothing();
        if (GREGTECH && !gtBroken) {
            final TileEntity tile = world.getTileEntity(x, y, z);
            if (tile != null) try {
                final MachineStatus gt = GtProbe.probe(tile);
                if (gt != null) return gt;
            } catch (final LinkageError e) {
                gtBroken = true;
                GtnhPlanner.LOG.warn("[live] cannot read GregTech machines in this pack", e);
            }
        }
        String name;
        try {
            name = block.getLocalizedName();
        } catch (final RuntimeException e) {
            name = block.getUnlocalizedName();
        }
        return MachineStatus.block(name == null ? "" : name);
    }
}
