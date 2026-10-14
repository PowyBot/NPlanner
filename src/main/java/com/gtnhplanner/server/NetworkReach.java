package com.gtnhplanner.server;

import java.util.UUID;

import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.Loader;

/**
 * Whether a machine out of a player's sight is on their network, so they still read it live: the one place a network
 * mod plugs in. BeCom will answer it with its data nodes ({@code BeCom.at(pos)} and {@code BeCom.player(id).online()},
 * NPlanner-notes/BECOM_API.md); until BeCom has that API nothing is on any network, and a player reads only what is
 * within sight ({@link Sight}).
 */
@FunctionalInterface
public interface NetworkReach {

    /** No network: nothing out of sight is read. */
    NetworkReach NONE = (player, dim, x, y, z) -> false;

    /** Whether {@code player} reads the machine whose controller is at {@code dim, x, y, z} over a network. */
    boolean reaches(UUID player, int dim, int x, int y, int z);

    /** The network of the mods loaded. BeCom is looked for by name, never compiled against. */
    static NetworkReach detect() {
        if (!Loader.isModLoaded("becom")) return NONE;
        GtnhPlanner.LOG.info("[live] BeCom is loaded but has no planner API yet: machines are read within sight only");
        return NONE;
    }
}
