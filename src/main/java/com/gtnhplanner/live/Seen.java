package com.gtnhplanner.live;

import javax.annotation.Nullable;

/**
 * What a player last saw of a placed machine: its status, and since when it is out of their sight ({@link #IN_SIGHT}
 * while in it). Out of sight its badge is grey with the age of what they saw; after {@link #EXPIRES} that is too old
 * to show.
 */
public record Seen(@Nullable MachineStatus status, long hiddenSince) {

    /** How long a machine out of sight keeps what was last seen of it, in milliseconds. */
    public static final long EXPIRES = 60_000;

    /** {@link #hiddenSince} of a machine in sight. */
    public static final long IN_SIGHT = -1;

    public static Seen live(final MachineStatus status) {
        return new Seen(status, IN_SIGHT);
    }

    /** Out of sight from {@code now}, keeping what {@code before} saw (and since when, if it was already). */
    public static Seen hidden(@Nullable final Seen before, final long now) {
        if (before == null) return new Seen(null, now);
        return before.inSight() ? new Seen(before.status, now) : before;
    }

    public boolean inSight() {
        return hiddenSince == IN_SIGHT;
    }

    /** What is still worth showing at {@code now}: the status in sight, or out of sight not yet expired; else null. */
    @Nullable
    public MachineStatus current(final long now) {
        return inSight() || now - hiddenSince <= EXPIRES ? status : null;
    }

    /** The badge at {@code now}, for a card planned on {@code expectedMetaId} ({@link MachineStatus#health}). */
    public MachineStatus.Health health(final int expectedMetaId, final long now) {
        if (inSight()) return status == null ? new MachineStatus.Health(MachineStatus.Level.UNKNOWN, "")
            : status.health(expectedMetaId);
        if (current(now) == null) return new MachineStatus.Health(MachineStatus.Level.AWAY, "Out of sight");
        return new MachineStatus.Health(MachineStatus.Level.AWAY, "Out of sight · " + age(now - hiddenSince));
    }

    /** 12 s ago, 1 min ago. */
    static String age(final long millis) {
        final long s = Math.max(0, millis / 1000);
        return s < 60 ? s + " s ago" : s / 60 + " min ago";
    }
}
