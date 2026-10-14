package com.gtnhplanner.server;

import java.util.UUID;

import com.gtnhplanner.net.Proto;

/**
 * Which placed machines a player reads live ("a vista d'occhio", NPlanner-notes/PROGRESSION.md §2): those whose
 * controller is within {@code radius} blocks of them, a sphere in the same dimension, through walls; or out of sight
 * but on their network ({@link NetworkReach}). With progression off ({@code limited} false) they read every machine.
 */
public record Sight(boolean limited, int radius, NetworkReach reach) {

    /** Every machine, wherever it is: progression off. */
    public static final Sight UNLIMITED = new Sight(false, 0, NetworkReach.NONE);

    /** Who looks, from where. */
    public record Viewer(UUID id, int dim, double x, double y, double z) {}

    public boolean sees(final Viewer v, final Proto.Placement p) {
        return !limited || near(v, p) || reach.reaches(v.id(), p.dim(), p.x(), p.y(), p.z());
    }

    /** Within the radius of the block's middle. */
    private boolean near(final Viewer v, final Proto.Placement p) {
        if (v.dim() != p.dim()) return false;
        final double dx = p.x() + 0.5 - v.x(), dy = p.y() + 0.5 - v.y(), dz = p.z() + 0.5 - v.z();
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
