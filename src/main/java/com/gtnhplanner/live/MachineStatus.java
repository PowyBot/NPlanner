package com.gtnhplanner.live;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What the server found at a placed machine's block, read from the world as it is ({@code server/MachineProbe}): no
 * Minecraft types, so both sides and the tests share it. {@link #health} turns it into the badge players see.
 *
 * @param found       what is there: {@link #UNLOADED} (its chunk is not loaded: unknown), {@link #NOTHING} (air),
 *                    {@link #BLOCK} (some other block), {@link #MACHINE} (a GregTech machine)
 * @param metaId      the GregTech machine's id (its item's damage), -1 for none
 * @param name        what is there, by name
 * @param multiblock  whether it is a multiblock controller
 * @param formed      whether its structure is complete (always true for a single block)
 * @param active      whether it is running a recipe
 * @param allowed     whether it is switched on (a soft mallet, a cover, a redstone signal can switch it off)
 * @param progress    ticks into the recipe it runs
 * @param maxProgress ticks the recipe takes, 0 when none
 * @param euPerTick   EU/t it draws (positive) or makes (negative) running
 * @param maintenance how many maintenance problems it has
 * @param efficiency  a multiblock's efficiency, 0 to 10000
 * @param outputs     what the recipe it runs makes, by name, joined with ", "; empty when idle
 * @param message     why it is not running, or why it was shut down, as the machine says it; empty when nothing to say
 * @param critical    whether it was shut down by something the player must fix (power lost, a structure broken)
 */
public record MachineStatus(byte found, int metaId, String name, boolean multiblock, boolean formed, boolean active,
    boolean allowed, int progress, int maxProgress, long euPerTick, int maintenance, int efficiency, String outputs,
    String message, boolean critical) {

    public static final byte UNLOADED = 0, NOTHING = 1, BLOCK = 2, MACHINE = 3;

    public static MachineStatus unloaded() {
        return new MachineStatus(UNLOADED, -1, "", false, false, false, false, 0, 0, 0, 0, 0, "", "", false);
    }

    public static MachineStatus nothing() {
        return new MachineStatus(NOTHING, -1, "", false, false, false, false, 0, 0, 0, 0, 0, "", "", false);
    }

    public static MachineStatus block(final String name) {
        return new MachineStatus(BLOCK, -1, name, false, false, false, false, 0, 0, 0, 0, 0, "", "", false);
    }

    /** The badge's colour, worst last. */
    public enum Level {
        /** Running as planned. */
        GOOD,
        /** Built but not running, or running with something to see to. */
        WARN,
        /** Not built, not formed, the wrong machine, or shut down by a fault. */
        BAD,
        /** Unknown: its chunk is not loaded, or the server has not looked yet. */
        UNKNOWN
    }

    /** A badge: its level and its few words. */
    public record Health(Level level, String text) {}

    /**
     * The badge for this status, for a card planned on the GregTech machine {@code expectedMetaId} (-1 when the card's
     * machine is not one, or not known): another machine there is as bad as none.
     */
    public Health health(final int expectedMetaId) {
        switch (found) {
            case UNLOADED:
                return new Health(Level.UNKNOWN, "Not loaded");
            case NOTHING:
                return new Health(Level.BAD, "Not built");
            case BLOCK:
                return new Health(Level.BAD, name.isEmpty() ? "Not built" : "Not a machine");
            default:
                break;
        }
        if (expectedMetaId >= 0 && metaId >= 0 && expectedMetaId != metaId)
            return new Health(Level.BAD, "Wrong machine");
        if (multiblock && !formed) return new Health(Level.BAD, "Structure incomplete");
        if (critical && !active) return new Health(Level.BAD, message.isEmpty() ? "Shut down" : message);
        if (maintenance > 0) return new Health(
            active ? Level.WARN : Level.BAD,
            maintenance == 1 ? "1 maintenance issue" : maintenance + " maintenance issues");
        if (!allowed) return new Health(Level.WARN, "Switched off");
        if (active) return new Health(Level.GOOD, maxProgress > 0 ? percent() + "%" : "Running");
        return new Health(Level.WARN, message.isEmpty() ? "Idle" : message);
    }

    /** How far into its recipe, 0 to 100. */
    public int percent() {
        return maxProgress <= 0 ? 0 : (int) Math.max(0, Math.min(100, 100L * progress / maxProgress));
    }

    public void write(final DataOutputStream out) throws IOException {
        out.writeByte(found);
        out.writeInt(metaId);
        out.writeUTF(cap(name));
        out.writeByte(
            (multiblock ? 1 : 0) | (formed ? 2 : 0) | (active ? 4 : 0) | (allowed ? 8 : 0) | (critical ? 16 : 0));
        out.writeInt(progress);
        out.writeInt(maxProgress);
        out.writeLong(euPerTick);
        out.writeInt(maintenance);
        out.writeInt(efficiency);
        out.writeUTF(cap(outputs));
        out.writeUTF(cap(message));
    }

    public static MachineStatus read(final DataInputStream in) throws IOException {
        final byte found = in.readByte();
        final int metaId = in.readInt();
        final String name = in.readUTF();
        final int flags = in.readByte();
        return new MachineStatus(
            found,
            metaId,
            name,
            (flags & 1) != 0,
            (flags & 2) != 0,
            (flags & 4) != 0,
            (flags & 8) != 0,
            in.readInt(),
            in.readInt(),
            in.readLong(),
            in.readInt(),
            in.readInt(),
            in.readUTF(),
            in.readUTF(),
            (flags & 16) != 0);
    }

    /** Short enough for a status line, and for {@link DataOutputStream#writeUTF} whatever it holds. */
    private static String cap(final String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
