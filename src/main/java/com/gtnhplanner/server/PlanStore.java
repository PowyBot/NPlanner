package com.gtnhplanner.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.net.Proto;

/**
 * The plans a world keeps on its server, one file each in {@code <world>/gtnhplanner/plans/}: opaque blobs (the
 * server never reads a plan; only a client with its recipes can) with a revision, a name, who changed it last, and
 * where its machines are placed. Pure Java, so tested without Minecraft.
 *
 * <p>
 * Edits are last-writer-wins by revision: a push based on the revision the server has moves it on by one; a push based
 * on an older one still wins, but the plan it overwrites is kept first as a copy of its own (a conflict copy), so no
 * one's work is lost. A deleted plan's file goes to {@code trash/}. Files are written off the calling thread, each
 * whole
 * ({@code .new} then moved over).
 */
public final class PlanStore {

    private static final int MAGIC = 0x4E504C4E, FORMAT = 1;

    /** A kept plan. */
    public record Stored(UUID id, long revision, String name, long updatedAt, String updatedBy, byte[] blob,
        List<Proto.Placement> placements) {

        public Proto.Entry entry() {
            return new Proto.Entry(id, revision, name, updatedAt, updatedBy);
        }
    }

    /** What a push did: the plan as it is now, and the copy the plan it overwrote went to, if any. */
    public record Result(@Nullable Stored plan, @Nullable Stored conflictCopy, @Nullable String refused) {}

    private final File plansDir, trashDir;
    private final Map<UUID, Stored> plans = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner server save");
        t.setDaemon(true);
        return t;
    });

    /** The store in {@code root} (a world's {@code gtnhplanner} folder), with every plan already there read. */
    public PlanStore(final File root) {
        plansDir = new File(root, "plans");
        trashDir = new File(root, "trash");
        final File[] files = plansDir.listFiles((d, n) -> n.endsWith(".plan"));
        if (files != null) for (final File f : files) {
            try {
                final Stored s = read(Files.readAllBytes(f.toPath()));
                plans.put(s.id(), s);
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.error("[server] could not read the plan {}; it is left where it is", f, e);
            }
        }
    }

    public synchronized Collection<Stored> all() {
        final List<Stored> out = new ArrayList<>(plans.values());
        out.sort(
            Comparator.comparing(Stored::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Stored::id));
        return out;
    }

    public synchronized List<Proto.Entry> index() {
        final List<Proto.Entry> out = new ArrayList<>();
        for (final Stored s : all()) out.add(s.entry());
        return out;
    }

    @Nullable
    public synchronized Stored get(final UUID id) {
        return plans.get(id);
    }

    /** Takes a plan from {@code by} at {@code now} (ms); see the class's note on conflicts. */
    public synchronized Result push(final Proto.Push push, final String by, final long now) {
        if (push.blob().length > Proto.MAX_BLOB) return new Result(null, null, "too big");
        final Stored was = plans.get(push.planId());
        if (was != null && Arrays.equals(was.blob(), push.blob())
            && was.name()
                .equals(push.name())
            && was.placements()
                .equals(push.placements()))
            return new Result(was, null, null);
        Stored copy = null;
        if (was != null && push.baseRevision() != was.revision()) {
            copy = new Stored(
                UUID.randomUUID(),
                1,
                was.name() + " (conflict copy)",
                now,
                was.updatedBy(),
                was.blob(),
                was.placements());
            put(copy);
        }
        final Stored next = new Stored(
            push.planId(),
            was == null ? 1 : was.revision() + 1,
            push.name(),
            now,
            by,
            push.blob(),
            List.copyOf(push.placements()));
        put(next);
        return new Result(next, copy, null);
    }

    /** Deletes a plan; its file goes to the trash. Whether there was one. */
    public synchronized boolean delete(final UUID id) {
        final Stored was = plans.remove(id);
        if (was == null) return false;
        writer.execute(() -> {
            try {
                final File f = file(id);
                if (!f.isFile()) return;
                trashDir.mkdirs();
                Files.move(
                    f.toPath(),
                    new File(trashDir, id + "-" + System.currentTimeMillis() + ".plan").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                GtnhPlanner.LOG.warn("[server] could not move the deleted plan {} to the trash", id, e);
            }
        });
        return true;
    }

    /** Waits for every write so far. */
    public void flush() {
        try {
            writer.submit(() -> {})
                .get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            GtnhPlanner.LOG.warn("[server] the plans may not all be written", e);
        }
    }

    /** Writes what is left and stops the writer. */
    public void close() {
        flush();
        writer.shutdown();
    }

    private void put(final Stored s) {
        plans.put(s.id(), s);
        final byte[] bytes = write(s);
        writer.execute(() -> {
            try {
                plansDir.mkdirs();
                final File f = file(s.id()), next = new File(plansDir, f.getName() + ".new");
                Files.write(next.toPath(), bytes);
                Files.move(
                    next.toPath(),
                    f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                GtnhPlanner.LOG.warn("[server] could not save the plan {}", s.id(), e);
            }
        });
    }

    private File file(final UUID id) {
        return new File(plansDir, id + ".plan");
    }

    static byte[] write(final Stored s) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(
                s.id()
                    .getMostSignificantBits());
            out.writeLong(
                s.id()
                    .getLeastSignificantBits());
            out.writeLong(s.revision());
            out.writeUTF(s.name());
            out.writeLong(s.updatedAt());
            out.writeUTF(s.updatedBy());
            out.writeInt(
                s.placements()
                    .size());
            for (final Proto.Placement p : s.placements()) {
                out.writeLong(
                    p.nodeId()
                        .getMostSignificantBits());
                out.writeLong(
                    p.nodeId()
                        .getLeastSignificantBits());
                out.writeInt(p.index());
                out.writeInt(p.dim());
                out.writeInt(p.x());
                out.writeInt(p.y());
                out.writeInt(p.z());
            }
            out.writeInt(s.blob().length);
            out.write(s.blob());
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Stored read(final byte[] data) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != MAGIC) throw new IOException("Not a plan");
        final int format = in.readInt();
        if (format != FORMAT) throw new IOException("A plan of a newer format: " + format);
        final UUID id = new UUID(in.readLong(), in.readLong());
        final long revision = in.readLong();
        final String name = in.readUTF();
        final long at = in.readLong();
        final String by = in.readUTF();
        final int n = in.readInt();
        if (n < 0 || n > 1_000_000) throw new IOException("Bad placements " + n);
        final List<Proto.Placement> placements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) placements.add(
            new Proto.Placement(
                new UUID(in.readLong(), in.readLong()),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readInt()));
        final int size = in.readInt();
        if (size < 0 || size > Proto.MAX_BLOB) throw new IOException("Bad blob " + size);
        final byte[] blob = new byte[size];
        in.readFully(blob);
        return new Stored(id, revision, name, at, by, blob, List.copyOf(placements));
    }
}
