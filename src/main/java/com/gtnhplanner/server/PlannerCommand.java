package com.gtnhplanner.server;

import java.util.Map;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

/**
 * {@code /plannerserver plans|live}: what the server keeps, and whose planner watches which plan's machines. For
 * operators and for checking the server side works.
 */
public final class PlannerCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "plannerserver";
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return "/plannerserver plans|live";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        final ServerPlanner planner = ServerPlanner.INSTANCE;
        final PlanStore store = planner.store();
        if (store == null) {
            say(sender, "The planner's server side is not running.");
            return;
        }
        if (args.length > 0 && args[0].equals("live")) {
            say(
                sender,
                planner.ready()
                    .size() + " planners connected");
            for (final Map.Entry<EntityPlayerMP, UUID> e : planner.watching()
                .entrySet()) {
                final PlanStore.Stored s = store.get(e.getValue());
                say(
                    sender,
                    " " + e.getKey()
                        .getCommandSenderName()
                        + " watches "
                        + (s == null ? e.getValue()
                            : s.name() + " ("
                                + s.placements()
                                    .size()
                                + " placed)"));
            }
            return;
        }
        say(
            sender,
            store.all()
                .size() + " plans");
        for (final PlanStore.Stored s : store.all()) say(
            sender,
            " " + s.name()
                + "  r"
                + s.revision()
                + "  "
                + s.placements()
                    .size()
                + " placed  "
                + s.blob().length / 1024
                + " KB  by "
                + s.updatedBy());
    }

    private static void say(final ICommandSender sender, final String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
