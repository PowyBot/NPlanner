package com.gtnhplanner.server;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;
import com.gtnhplanner.net.PlannerNetwork;
import com.gtnhplanner.net.Proto;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The planner on the server: keeps the world's plans ({@link PlanStore}), hands them to every player's planner when it
 * says hello, takes their edits and passes them on to everyone else, and tells each player how the machines of the
 * plan they have open are doing ({@link LiveScanner}). For now every player sees and edits every plan.
 *
 * <p>
 * Messages come in on the network's threads and are handled here at the start of each server tick.
 */
public final class ServerPlanner {

    public static final ServerPlanner INSTANCE = new ServerPlanner();

    private record Incoming(EntityPlayerMP player, Proto.Msg msg) {}

    private final ConcurrentLinkedQueue<Incoming> inbox = new ConcurrentLinkedQueue<>();
    /** The players whose planner said hello in a protocol this server speaks. */
    private final Set<EntityPlayerMP> ready = new LinkedHashSet<>();

    @Nullable
    private PlanStore store;
    @Nullable
    private LiveScanner<EntityPlayerMP> live;

    private ServerPlanner() {}

    /** Wires the channel to this. Once, in pre-init. */
    public static void init() {
        PlannerNetwork.onServer((player, msg) -> INSTANCE.inbox.add(new Incoming(player, msg)));
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    /** The world is up: its plans are read. */
    public void start(final MinecraftServer server) {
        final File root = new File(
            server.worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory(),
            GtnhPlanner.MODID);
        store = new PlanStore(root);
        final PlanStore plans = store;
        live = new LiveScanner<>(MachineProbe::probe, PlannerNetwork::sendTo, id -> {
            final PlanStore.Stored s = plans.get(id);
            return s == null ? List.of() : s.placements();
        });
        inbox.clear();
        ready.clear();
        GtnhPlanner.LOG.info(
            "[server] {} plans kept in {}",
            store.all()
                .size(),
            root);
    }

    /** The world is going: every plan is written. */
    public void stop() {
        if (store != null) store.close();
        store = null;
        live = null;
        ready.clear();
        inbox.clear();
    }

    @Nullable
    public PlanStore store() {
        return store;
    }

    @Nullable
    public LiveScanner<EntityPlayerMP> live() {
        return live;
    }

    public Set<EntityPlayerMP> ready() {
        return java.util.Collections.unmodifiableSet(ready);
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || store == null) return;
        for (Incoming in; (in = inbox.poll()) != null;) {
            try {
                handle(in.player(), in.msg());
            } catch (final RuntimeException e) {
                GtnhPlanner.LOG.warn(
                    "[server] could not handle {} from {}",
                    in.msg()
                        .getClass()
                        .getSimpleName(),
                    in.player()
                        .getCommandSenderName(),
                    e);
            }
        }
        if (live != null) live.tick();
    }

    @SubscribeEvent
    public void onLogout(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        ready.remove(player);
        if (live != null) live.forget(player);
        PlannerNetwork.forget(player);
    }

    private void handle(final EntityPlayerMP player, final Proto.Msg msg) {
        final PlanStore plans = store;
        if (plans == null) return;
        if (msg instanceof Proto.Hello hello) {
            hello(player, hello, plans);
            return;
        }
        // Nothing but hello until hello is answered.
        if (!ready.contains(player)) return;
        switch (msg) {
            case Proto.Push push -> push(player, push, plans);
            case Proto.Delete delete -> delete(player, delete, plans);
            case Proto.Fetch fetch -> fetch(player, fetch, plans);
            case Proto.Watch watch -> watch(player, watch, plans);
            default -> {}
        }
    }

    private void hello(final EntityPlayerMP player, final Proto.Hello hello, final PlanStore plans) {
        final boolean accepted = hello.protocol() == Proto.PROTOCOL;
        PlannerNetwork.sendTo(player, new Proto.Welcome(Proto.PROTOCOL, accepted, Tags.VERSION, plans.index()));
        if (accepted) ready.add(player);
        else ready.remove(player);
        GtnhPlanner.LOG.info(
            "[server] {}'s planner {} (protocol {}, {})",
            player.getCommandSenderName(),
            accepted ? "joined" : "is too old or too new",
            hello.protocol(),
            hello.modVersion());
    }

    private void push(final EntityPlayerMP player, final Proto.Push push, final PlanStore plans) {
        final PlanStore.Result r = plans.push(push, player.getCommandSenderName(), System.currentTimeMillis());
        final PlanStore.Stored plan = r.plan();
        if (plan == null) {
            GtnhPlanner.LOG
                .warn("[server] refused {}'s plan '{}': {}", player.getCommandSenderName(), push.name(), r.refused());
            return;
        }
        final PlanStore.Stored copy = r.conflictCopy();
        PlannerNetwork
            .sendTo(player, new Proto.Pushed(push.planId(), plan.revision(), copy == null ? null : copy.id()));
        broadcast(dataOf(plan), player);
        if (copy != null) broadcast(dataOf(copy), null);
        if (live != null) live.placementsChanged(push.planId());
    }

    private void delete(final EntityPlayerMP player, final Proto.Delete delete, final PlanStore plans) {
        if (!plans.delete(delete.planId())) return;
        GtnhPlanner.LOG.info("[server] {} deleted the plan {}", player.getCommandSenderName(), delete.planId());
        broadcast(new Proto.Removed(delete.planId()), player);
        if (live != null) live.planRemoved(delete.planId());
    }

    private static void fetch(final EntityPlayerMP player, final Proto.Fetch fetch, final PlanStore plans) {
        final PlanStore.Stored s = plans.get(fetch.planId());
        PlannerNetwork.sendTo(player, s == null ? new Proto.Removed(fetch.planId()) : dataOf(s));
    }

    private void watch(final EntityPlayerMP player, final Proto.Watch watch, final PlanStore plans) {
        if (live == null) return;
        final boolean known = watch.planId() != null && plans.get(watch.planId()) != null;
        live.watch(player, known ? watch.planId() : null);
    }

    private static Proto.PlanData dataOf(final PlanStore.Stored s) {
        return new Proto.PlanData(s.entry(), s.blob());
    }

    /** A message to every ready player but {@code except}, encoded once. */
    private void broadcast(final Proto.Msg msg, @Nullable final EntityPlayerMP except) {
        final List<EntityPlayerMP> to = ready.stream()
            .filter(p -> p != except)
            .toList();
        if (to.isEmpty()) return;
        final byte[] encoded = Proto.encode(msg);
        to.forEach(p -> PlannerNetwork.sendTo(p, encoded));
    }

    /** For the debug command: who watches what. */
    public Map<EntityPlayerMP, java.util.UUID> watching() {
        return live == null ? Map.of() : live.watching();
    }
}
