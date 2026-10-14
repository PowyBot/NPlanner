package com.gtnhplanner.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.live.MachineStatus;

/**
 * The planner's messages between client and server, as plain records turned to bytes and back with no Minecraft in
 * sight ({@link #encode}, {@link #decode}), so they are tested headless; {@link Chunker} carries the bytes, cut to the
 * size a packet takes. Plans travel as opaque blobs (their plan code, gzip JSON): the server keeps them without
 * reading them, since only a client with its recipes loaded can.
 *
 * <p>
 * Every message starts with its type byte. {@link #PROTOCOL} goes up whenever any message changes shape; the client
 * says its own in {@link Hello}, and the server answers with its own in {@link Welcome}.
 */
public final class Proto {

    /** The protocol both sides speak. Raise it whenever a message changes. */
    public static final int PROTOCOL = 2;

    /** The largest plan the server takes (its compressed blob). */
    public static final int MAX_BLOB = 4 * 1024 * 1024;

    private Proto() {}

    /** A message either way. */
    public sealed interface Msg permits Hello,Push,Delete,Fetch,Watch,Welcome,PlanData,Pushed,Removed,Status {
    }

    // region Client to server

    /** The first thing a client says: its protocol and its mod's version. */
    public record Hello(int protocol, String modVersion) implements Msg {}

    /**
     * A plan, new or edited: what it is based on ({@code baseRevision}, 0 for new), its name, its blob, and where its
     * machines are placed in the world, in the clear, so the server can look at them ({@link Placement}).
     */
    public record Push(UUID planId, long baseRevision, String name, byte[] blob, List<Placement> placements)
        implements Msg {}

    /** The client deleted a plan. */
    public record Delete(UUID planId) implements Msg {}

    /** The client wants a plan's latest blob. */
    public record Fetch(UUID planId) implements Msg {}

    /** The plan the client has open (its machines' live status is wanted), or none. */
    public record Watch(@Nullable UUID planId) implements Msg {}

    // endregion

    // region Server to client

    /**
     * The server's answer to {@link Hello}: its protocol, whether it takes the client's, its version, and every plan it
     * keeps.
     */
    public record Welcome(int protocol, boolean accepted, String modVersion, List<Entry> plans) implements Msg {}

    /** A plan's blob: in answer to {@link Fetch}, or because someone changed it. */
    public record PlanData(Entry entry, byte[] blob) implements Msg {}

    /** A push was taken: the plan is at this revision now. {@code copyOf}: the plan it overwrote went to this one. */
    public record Pushed(UUID planId, long revision, @Nullable UUID conflictCopy) implements Msg {}

    /** A plan was deleted. */
    public record Removed(UUID planId) implements Msg {}

    /**
     * The live status of placed machines of a watched plan the player sees: only those that changed, came into or went
     * out of their sight, unless {@code full}.
     */
    public record Status(UUID planId, boolean full, List<Live> machines) implements Msg {}

    // endregion

    /** A plan in the server's index. */
    public record Entry(UUID planId, long revision, String name, long updatedAt, String updatedBy) {}

    /** A placed machine of a plan: its card (node), which of the card's placements, and its block. */
    public record Placement(UUID nodeId, int index, int dim, int x, int y, int z) {

        /** The key its status goes by. */
        public String key() {
            return key(nodeId, index);
        }

        public static String key(final UUID nodeId, final int index) {
            return nodeId + "#" + index;
        }
    }

    /** One placed machine's status; null when it is out of the player's sight ({@code server/Sight}). */
    public record Live(UUID nodeId, int index, @Nullable MachineStatus status) {}

    // region Bytes

    private static final byte HELLO = 1, PUSH = 2, DELETE = 3, FETCH = 4, WATCH = 5, WELCOME = 64, PLAN = 65,
        PUSHED = 66, REMOVED = 67, STATUS = 68;

    public static byte[] encode(final Msg msg) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            switch (msg) {
                case Hello m -> {
                    out.writeByte(HELLO);
                    out.writeInt(m.protocol());
                    out.writeUTF(cap(m.modVersion()));
                }
                case Push m -> {
                    out.writeByte(PUSH);
                    uuid(out, m.planId());
                    out.writeLong(m.baseRevision());
                    out.writeUTF(cap(m.name()));
                    blob(out, m.blob());
                    out.writeInt(
                        m.placements()
                            .size());
                    for (final Placement p : m.placements()) {
                        uuid(out, p.nodeId());
                        out.writeInt(p.index());
                        out.writeInt(p.dim());
                        out.writeInt(p.x());
                        out.writeInt(p.y());
                        out.writeInt(p.z());
                    }
                }
                case Delete m -> {
                    out.writeByte(DELETE);
                    uuid(out, m.planId());
                }
                case Fetch m -> {
                    out.writeByte(FETCH);
                    uuid(out, m.planId());
                }
                case Watch m -> {
                    out.writeByte(WATCH);
                    out.writeBoolean(m.planId() != null);
                    if (m.planId() != null) uuid(out, m.planId());
                }
                case Welcome m -> {
                    out.writeByte(WELCOME);
                    out.writeInt(m.protocol());
                    out.writeBoolean(m.accepted());
                    out.writeUTF(cap(m.modVersion()));
                    out.writeInt(
                        m.plans()
                            .size());
                    for (final Entry e : m.plans()) entry(out, e);
                }
                case PlanData m -> {
                    out.writeByte(PLAN);
                    entry(out, m.entry());
                    blob(out, m.blob());
                }
                case Pushed m -> {
                    out.writeByte(PUSHED);
                    uuid(out, m.planId());
                    out.writeLong(m.revision());
                    out.writeBoolean(m.conflictCopy() != null);
                    if (m.conflictCopy() != null) uuid(out, m.conflictCopy());
                }
                case Removed m -> {
                    out.writeByte(REMOVED);
                    uuid(out, m.planId());
                }
                case Status m -> {
                    out.writeByte(STATUS);
                    uuid(out, m.planId());
                    out.writeBoolean(m.full());
                    out.writeInt(
                        m.machines()
                            .size());
                    for (final Live l : m.machines()) {
                        uuid(out, l.nodeId());
                        out.writeInt(l.index());
                        out.writeBoolean(l.status() != null);
                        if (l.status() != null) l.status()
                            .write(out);
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The message in these bytes; throws {@link IOException} on anything malformed. */
    public static Msg decode(final byte[] data) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        final byte type = in.readByte();
        return switch (type) {
            case HELLO -> new Hello(in.readInt(), in.readUTF());
            case PUSH -> {
                final UUID id = uuid(in);
                final long base = in.readLong();
                final String name = in.readUTF();
                final byte[] blob = blob(in);
                final int n = count(in, 1_000_000);
                final List<Placement> placements = new ArrayList<>(n);
                for (int i = 0; i < n; i++) placements
                    .add(new Placement(uuid(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                yield new Push(id, base, name, blob, placements);
            }
            case DELETE -> new Delete(uuid(in));
            case FETCH -> new Fetch(uuid(in));
            case WATCH -> new Watch(in.readBoolean() ? uuid(in) : null);
            case WELCOME -> {
                final int protocol = in.readInt();
                final boolean accepted = in.readBoolean();
                final String version = in.readUTF();
                final int n = count(in, 1_000_000);
                final List<Entry> plans = new ArrayList<>(n);
                for (int i = 0; i < n; i++) plans.add(entry(in));
                yield new Welcome(protocol, accepted, version, plans);
            }
            case PLAN -> new PlanData(entry(in), blob(in));
            case PUSHED -> new Pushed(uuid(in), in.readLong(), in.readBoolean() ? uuid(in) : null);
            case REMOVED -> new Removed(uuid(in));
            case STATUS -> {
                final UUID id = uuid(in);
                final boolean full = in.readBoolean();
                final int n = count(in, 1_000_000);
                final List<Live> machines = new ArrayList<>(n);
                for (int i = 0; i < n; i++)
                    machines.add(new Live(uuid(in), in.readInt(), in.readBoolean() ? MachineStatus.read(in) : null));
                yield new Status(id, full, machines);
            }
            default -> throw new IOException("Unknown planner message " + type);
        };
    }

    /** A name short enough for any message (and for {@link DataOutputStream#writeUTF}). */
    private static String cap(final String s) {
        return s == null ? "" : s.length() > 256 ? s.substring(0, 256) : s;
    }

    private static void uuid(final DataOutputStream out, final UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(final DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void blob(final DataOutputStream out, final byte[] blob) throws IOException {
        out.writeInt(blob.length);
        out.write(blob);
    }

    private static byte[] blob(final DataInputStream in) throws IOException {
        final byte[] blob = new byte[count(in, MAX_BLOB)];
        in.readFully(blob);
        return blob;
    }

    private static int count(final DataInputStream in, final int max) throws IOException {
        final int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("Bad count " + n);
        return n;
    }

    private static void entry(final DataOutputStream out, final Entry e) throws IOException {
        uuid(out, e.planId());
        out.writeLong(e.revision());
        out.writeUTF(cap(e.name()));
        out.writeLong(e.updatedAt());
        out.writeUTF(cap(e.updatedBy()));
    }

    private static Entry entry(final DataInputStream in) throws IOException {
        return new Entry(uuid(in), in.readLong(), in.readUTF(), in.readLong(), in.readUTF());
    }

    // endregion
}
