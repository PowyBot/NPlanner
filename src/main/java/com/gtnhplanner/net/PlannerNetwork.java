package com.gtnhplanner.net;

import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;

import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The planner's channel, {@code gtnhplanner}: {@link Proto} messages cut into {@link Frame}s. Registered on both sides
 * in pre-init; the server's side is {@code server/ServerPlanner}, the client's {@code client/sync/SharedSync}, each
 * handed whole messages through {@link #onServer} and {@link #onClient}. Messages arrive on the network's threads: the
 * receivers queue them for the game's thread. Nothing here touches client classes, so the server loads it.
 */
public final class PlannerNetwork {

    public static final String CHANNEL = GtnhPlanner.MODID;

    @Nullable
    private static SimpleNetworkWrapper channel;

    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    /** Takes each whole message from a player, on a network thread. */
    @Nullable
    private static volatile BiConsumer<EntityPlayerMP, Proto.Msg> serverReceiver;
    /** Takes each whole message from the server, on a network thread. */
    @Nullable
    private static volatile Consumer<Proto.Msg> clientReceiver;

    private static final Map<EntityPlayerMP, Chunker.Assembler> FROM_PLAYERS = new WeakHashMap<>();
    private static final Chunker.Assembler FROM_SERVER = new Chunker.Assembler();

    private PlannerNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        channel.registerMessage(ServerHandler.class, Frame.ToServer.class, 0, Side.SERVER);
        channel.registerMessage(ClientHandler.class, Frame.ToClient.class, 1, Side.CLIENT);
    }

    public static void onServer(final BiConsumer<EntityPlayerMP, Proto.Msg> receiver) {
        serverReceiver = receiver;
    }

    public static void onClient(final Consumer<Proto.Msg> receiver) {
        clientReceiver = receiver;
    }

    public static void sendToServer(final Proto.Msg msg) {
        if (channel == null) return;
        final int transfer = TRANSFERS.incrementAndGet();
        for (final Chunker.Part p : Chunker.split(transfer, Proto.encode(msg)))
            channel.sendToServer(new Frame.ToServer(p));
    }

    public static void sendTo(final EntityPlayerMP player, final Proto.Msg msg) {
        sendTo(player, Proto.encode(msg));
    }

    /** The same encoded message to a player: for one message sent to many, encoded once. */
    public static void sendTo(final EntityPlayerMP player, final byte[] encoded) {
        if (channel == null) return;
        final int transfer = TRANSFERS.incrementAndGet();
        for (final Chunker.Part p : Chunker.split(transfer, encoded)) channel.sendTo(new Frame.ToClient(p), player);
    }

    /** A player gone: the parts of anything half sent by them go too. */
    public static void forget(final EntityPlayerMP player) {
        synchronized (FROM_PLAYERS) {
            FROM_PLAYERS.remove(player);
        }
    }

    public static final class ServerHandler implements IMessageHandler<Frame.ToServer, IMessage> {

        @Override
        public IMessage onMessage(final Frame.ToServer frame, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final byte[] whole;
            synchronized (FROM_PLAYERS) {
                whole = FROM_PLAYERS.computeIfAbsent(player, p -> new Chunker.Assembler())
                    .accept(frame.part);
            }
            final BiConsumer<EntityPlayerMP, Proto.Msg> receiver = serverReceiver;
            if (whole == null || receiver == null) return null;
            try {
                receiver.accept(player, Proto.decode(whole));
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.warn("[net] a bad planner message from {}", player.getCommandSenderName(), e);
            }
            return null;
        }
    }

    public static final class ClientHandler implements IMessageHandler<Frame.ToClient, IMessage> {

        @Override
        public IMessage onMessage(final Frame.ToClient frame, final MessageContext ctx) {
            final byte[] whole;
            synchronized (FROM_SERVER) {
                whole = FROM_SERVER.accept(frame.part);
            }
            final Consumer<Proto.Msg> receiver = clientReceiver;
            if (whole == null || receiver == null) return null;
            try {
                receiver.accept(Proto.decode(whole));
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.warn("[net] a bad planner message from the server", e);
            }
            return null;
        }
    }
}
