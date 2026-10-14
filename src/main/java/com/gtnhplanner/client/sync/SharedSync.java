package com.gtnhplanner.client.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.net.PlannerNetwork;
import com.gtnhplanner.net.Proto;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.world.PlanSnapshot;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * The client's side of the server's plans ({@code server/ServerPlanner}): in a world whose server has the planner,
 * the plans are the world's, the same for every player. On joining the client says hello, gets the server's list and
 * comes into step with it ({@link Reconcile}); from then on every edit goes to the server a moment after it is made
 * (once the plan has been still for {@link #SETTLE_MS}), and every edit someone else makes comes in and takes its
 * plan's slot (unless the plan has edits of its own not yet sent: those go up and win, the server keeping what they
 * overwrite as a copy). Deleting a plan deletes it for everyone. The plan last open is watched: the server sends how
 * its placed machines are doing ({@link LiveStatus}).
 *
 * <p>
 * The tour's own plans are never sent, and nothing here loads the plans: it waits until the planner has.
 */
public final class SharedSync {

    public static final SharedSync INSTANCE = new SharedSync();

    /** How long a plan must be left alone before its edits go up. */
    static final long SETTLE_MS = 1500;
    /** A push not answered in this long is sent again. */
    private static final long ANSWER_MS = 30_000;

    private enum State {
        OFF,
        HELLO,
        READY,
        REFUSED
    }

    private volatile boolean joined;
    private State state = State.OFF;
    private final ConcurrentLinkedQueue<Proto.Msg> inbox = new ConcurrentLinkedQueue<>();

    /** The server's plans as it last said, id to revision. */
    private final Map<UUID, Long> server = new HashMap<>();
    /** Plans that came in before the plans were loaded, the newest of each; applied once they are. */
    private final Map<UUID, Proto.PlanData> waiting = new LinkedHashMap<>();
    private final Set<UUID> removedWaiting = new HashSet<>();

    /** The plans this is in step with (identity): another one (a reload, the tour) means starting over. */
    @Nullable
    private Plan plans;
    private boolean reconciled;
    /** The plans the server has, as of the last tick: one gone from the client was deleted here. */
    private final Set<UUID> known = new HashSet<>();

    /** A plan sent and not answered yet: its version and name when sent, and when. */
    private record InFlight(long version, String name, long at) {}

    private final Map<UUID, InFlight> inFlight = new HashMap<>();
    /** Each plan's version and name last seen, and since when: edits go up once a plan has been still a moment. */
    private final Map<UUID, Object[]> seen = new HashMap<>();
    @Nullable
    private UUID watching;
    private boolean watchSent;
    private int ticks;

    private SharedSync() {}

    public static void register() {
        PlannerNetwork.onClient(INSTANCE.inbox::add);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    /** Whether the plans are the server's now. */
    public boolean active() {
        return state == State.READY;
    }

    @SubscribeEvent
    public void onConnect(final FMLNetworkEvent.ClientConnectedToServerEvent event) {
        joined = true;
    }

    @SubscribeEvent
    public void onDisconnect(final FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        joined = false;
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!joined || mc.theWorld == null) {
            if (state != State.OFF) reset();
            return;
        }
        if (state == State.OFF) {
            state = State.HELLO;
            PlannerNetwork.sendToServer(new Proto.Hello(Proto.PROTOCOL, Tags.VERSION));
        }
        for (Proto.Msg msg; (msg = inbox.poll()) != null;) {
            try {
                handle(msg);
            } catch (final RuntimeException e) {
                GtnhPlanner.LOG.warn(
                    "[sync] could not take {}",
                    msg.getClass()
                        .getSimpleName(),
                    e);
            }
        }
        if (state != State.READY || ++ticks % 5 != 0) return;
        try {
            step();
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("[sync] could not bring the plans into step", e);
        }
    }

    private void reset() {
        state = State.OFF;
        server.clear();
        waiting.clear();
        removedWaiting.clear();
        plans = null;
        reconciled = false;
        known.clear();
        inFlight.clear();
        seen.clear();
        watching = null;
        watchSent = false;
        inbox.clear();
        LiveStatus.clear();
    }

    // region Messages

    private void handle(final Proto.Msg msg) {
        switch (msg) {
            case Proto.Welcome w -> welcome(w);
            case Proto.PlanData d -> planData(d);
            case Proto.Pushed p -> pushed(p);
            case Proto.Removed r -> removed(r);
            case Proto.Status s -> LiveStatus.apply(s);
            default -> {}
        }
    }

    private void welcome(final Proto.Welcome w) {
        if (!w.accepted() || w.protocol() != Proto.PROTOCOL) {
            state = State.REFUSED;
            say(
                EnumChatFormatting.RED + "This server's GTNH Planner ("
                    + w.modVersion()
                    + ") cannot share plans with yours ("
                    + Tags.VERSION
                    + "): update both to the same release.");
            return;
        }
        state = State.READY;
        server.clear();
        w.plans()
            .forEach(e -> server.put(e.planId(), e.revision()));
        reconciled = false;
        watchSent = false;
        GtnhPlanner.LOG.info("[sync] the server keeps {} plans", server.size());
    }

    private void planData(final Proto.PlanData d) {
        final Proto.Entry e = d.entry();
        server.put(e.planId(), e.revision());
        removedWaiting.remove(e.planId());
        if (ready() == null) {
            waiting.put(e.planId(), d);
            return;
        }
        take(d);
    }

    private void removed(final Proto.Removed r) {
        server.remove(r.planId());
        known.remove(r.planId());
        waiting.remove(r.planId());
        if (ready() == null) {
            removedWaiting.add(r.planId());
            return;
        }
        drop(r.planId());
    }

    /** The plans, if they are loaded and the player's own (not the tour's); else null. */
    @Nullable
    private static Plan ready() {
        final Plan p = Plan.loaded();
        return p == null || Plan.sandboxed() ? null : p;
    }

    private void pushed(final Proto.Pushed p) {
        final InFlight sent = inFlight.remove(p.planId());
        server.put(p.planId(), p.revision());
        known.add(p.planId());
        final Plan all = ready();
        if (all == null) return;
        final int i = all.indexOf(p.planId());
        if (i < 0) return;
        final Graph g = all.getGraphs()
            .get(i);
        g.setRevision(p.revision());
        // In step unless it was edited again while on its way.
        if (sent != null && sent.version() == g.version()
            && sent.name()
                .equals(g.getName()))
            g.markSynced();
        if (p.conflictCopy() != null) say(
            EnumChatFormatting.GOLD + "Someone else changed \""
                + g.getName()
                + "\" at the same time: theirs is kept as \""
                + g.getName()
                + " (conflict copy)\".");
        PlanAPI.save();
    }

    /** A plan from the server takes its slot, or a new one; not over edits of this client's own not yet sent. */
    private void take(final Proto.PlanData d) {
        final Plan all = ready();
        if (all == null) return;
        final Proto.Entry e = d.entry();
        known.add(e.planId());
        final int i = all.indexOf(e.planId());
        if (i < 0) {
            settle(addNew(all, d), e);
            return;
        }
        final Graph old = all.getGraphs()
            .get(i);
        if (old.getRevision() >= e.revision() || old.unsynced()) return;
        final BoardSession board = BoardSession.current();
        if (board != null && !board.canTakeServerCopy(old)) {
            // Being edited this moment: try again in a moment.
            waiting.put(e.planId(), d);
            return;
        }
        final Graph fresh = decode(d);
        all.replaceSlot(i, fresh);
        if (board != null) board.tookServerCopy(old, fresh);
        settle(fresh, e);
    }

    /** A plan new to this client: a slot of its own, without a tab. */
    private static Graph addNew(final Plan all, final Proto.PlanData d) {
        final Graph fresh = decode(d);
        fresh.setPlanId(
            d.entry()
                .planId());
        fresh.setOpen(false);
        all.getGraphs()
            .add(fresh);
        return fresh;
    }

    /** A plan just taken from the server is in step with it, at its revision. */
    private void settle(final Graph g, final Proto.Entry e) {
        g.setRevision(e.revision());
        g.markSynced();
        rememberSeen(g);
        PlanAPI.save();
    }

    private static Graph decode(final Proto.PlanData d) {
        final String code = Base64.getEncoder()
            .encodeToString(d.blob());
        Graph g;
        try {
            g = Serializer.decode(code);
        } catch (final RuntimeException e) {
            // Recipes this client lacks (another pack version): kept as it is, and read again when opened.
            GtnhPlanner.LOG.warn(
                "[sync] the plan '{}' cannot be read here yet; it is kept as it is",
                d.entry()
                    .name());
            g = new Graph(
                d.entry()
                    .name());
            g.setUnreadable(code);
        }
        g.setName(
            d.entry()
                .name());
        return g;
    }

    /** A plan deleted on the server goes here too; the one open on the board stays, as a new plan. */
    private void drop(final UUID id) {
        final Plan all = ready();
        if (all == null) return;
        final int i = all.indexOf(id);
        if (i < 0) return;
        final Graph g = all.getGraphs()
            .get(i);
        final BoardSession board = BoardSession.current();
        if (board != null && board.graph() == g || all.getGraphs()
            .size() <= 1) {
            g.setPlanId(UUID.randomUUID());
            g.setRevision(0);
        } else all.removeSlot(i);
        seen.remove(id);
        PlanAPI.save();
    }

    // endregion

    // region Each few ticks

    private void step() {
        final Plan all = ready();
        if (all == null) return;
        if (all != plans) startOver(all);
        if (!reconciled) {
            reconcile(all);
            reconciled = true;
        }
        takeWaiting();
        pushSettled(all);
        sendDeletes(all);
        watch();
    }

    /** Other plans than those this was in step with (a reload, the tour ending): in step with these from scratch. */
    private void startOver(final Plan all) {
        plans = all;
        reconciled = false;
        known.clear();
        inFlight.clear();
        seen.clear();
    }

    /** What came in while the plans were not loaded, or while the plan was being edited. */
    private void takeWaiting() {
        final List<Proto.PlanData> later = new ArrayList<>(waiting.values());
        waiting.clear();
        later.forEach(this::take);
        final List<UUID> gone = new ArrayList<>(removedWaiting);
        removedWaiting.clear();
        gone.forEach(this::drop);
    }

    /**
     * Each plan with edits the server has not seen, once it has been still a moment and nothing of it is on its way.
     */
    private void pushSettled(final Plan all) {
        final long now = System.currentTimeMillis();
        for (final Graph g : new ArrayList<>(all.getGraphs())) {
            if (!g.unsynced()) continue;
            final InFlight f = inFlight.get(g.getPlanId());
            if (f != null && now - f.at() < ANSWER_MS) continue;
            if (now - rememberSeen(g) < SETTLE_MS) continue;
            push(g);
        }
    }

    /** A plan the server has, gone from here: deleted here, so deleted for everyone. */
    private void sendDeletes(final Plan all) {
        final Set<UUID> here = new HashSet<>();
        all.getGraphs()
            .forEach(g -> here.add(g.getPlanId()));
        final List<UUID> gone = known.stream()
            .filter(id -> !here.contains(id))
            .toList();
        for (final UUID id : gone) {
            known.remove(id);
            server.remove(id);
            seen.remove(id);
            PlannerNetwork.sendToServer(new Proto.Delete(id));
        }
    }

    private void reconcile(final Plan all) {
        final List<Reconcile.Local> local = new ArrayList<>();
        for (final Graph g : all.getGraphs())
            local.add(new Reconcile.Local(g.getPlanId(), g.getRevision(), g.unsynced()));
        final Reconcile.Plan r = Reconcile.of(local, server);
        for (final UUID id : r.drop()) drop(id);
        for (final UUID id : r.fetch()) PlannerNetwork.sendToServer(new Proto.Fetch(id));
        // What is to be sent goes on the next steps, the usual way (it is unsynced, or new).
        for (final Graph g : all.getGraphs()) if (server.containsKey(g.getPlanId())) known.add(g.getPlanId());
        GtnhPlanner.LOG.info(
            "[sync] in step with the server: {} to send, {} to fetch, {} deleted there",
            r.push()
                .size(),
            r.fetch()
                .size(),
            r.drop()
                .size());
    }

    /** When the plan last changed, as far as this has seen. */
    private long rememberSeen(final Graph g) {
        final Object[] last = seen.get(g.getPlanId());
        final long now = System.currentTimeMillis();
        if (last == null || (long) last[0] != g.version() || !last[1].equals(g.getName())) {
            seen.put(g.getPlanId(), new Object[] { g.version(), g.getName(), now });
            // A plan never seen before (just loaded) goes at once; one being edited waits until it is still.
            return last == null ? 0 : now;
        }
        return (long) last[2];
    }

    private void push(final Graph g) {
        final byte[] blob;
        try {
            blob = Base64.getDecoder()
                .decode(
                    Serializer.slotData(g)
                        .getBytes(StandardCharsets.US_ASCII));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("[sync] could not encode the plan '{}'", g.getName(), e);
            return;
        }
        if (blob.length > Proto.MAX_BLOB) return;
        inFlight.put(g.getPlanId(), new InFlight(g.version(), g.getName(), System.currentTimeMillis()));
        PlannerNetwork.sendToServer(
            new Proto.Push(
                g.getPlanId(),
                g.getRevision(),
                g.getName(),
                blob,
                g.getNodes()
                    .size(),
                placements(g)));
    }

    /** Where a plan's machines are placed, in the clear, for the server to look at. */
    static List<Proto.Placement> placements(final Graph g) {
        final List<Proto.Placement> out = new ArrayList<>();
        for (final Node n : g.getNodes()) for (int i = 0; i < n.worldLinks.size(); i++) {
            final int[] l = n.worldLinks.get(i);
            if (l.length >= 4) out.add(new Proto.Placement(n.id, i, l[0], l[1], l[2], l[3]));
        }
        return out;
    }

    /** The plan last open is watched, once the server has it. */
    private void watch() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Graph g = snap == null ? null : snap.graph();
        final UUID want = g == null || !server.containsKey(g.getPlanId()) ? null : g.getPlanId();
        if (watchSent && Objects.equals(want, watching)) return;
        watching = want;
        watchSent = true;
        PlannerNetwork.sendToServer(new Proto.Watch(want));
    }

    /** The plan whose machines' status is coming in. */
    @Nullable
    public UUID watching() {
        return watching;
    }

    // endregion

    private static void say(final String text) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[Planner] " + text));
    }
}
