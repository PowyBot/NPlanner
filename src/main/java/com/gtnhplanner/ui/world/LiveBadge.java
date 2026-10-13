package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.client.sync.LiveStatus;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.live.MachineStatus;
import com.gtnhplanner.live.MachineStatus.Level;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How a placed machine is doing, drawn: a pill (a dot in green, yellow or red and a few words) over the card on the
 * board and over the machine's ghost in the world, the ghost outlined in its colour, and a panel of everything the
 * server read beside the machine looked at ({@code client/sync/LiveStatus}, {@code server/GtProbe}).
 */
public final class LiveBadge {

    public static final int GOOD = 0xFF22C55E, WARN = 0xFFEAB308, BAD = 0xFFEF4444, UNKNOWN = 0xFF6B7280;

    private static final int BACK = 0xE0101114, EDGE = 0xFF2A2D33;

    private LiveBadge() {}

    public static int color(final Level level) {
        return switch (level) {
            case GOOD -> GOOD;
            case WARN -> WARN;
            case BAD -> BAD;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** A bad machine's colour breathes, so it is seen from across the base. */
    static float pulse(final Level level) {
        if (level != Level.BAD) return 1;
        return 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0));
    }

    /** The pill's width at scale 1. */
    public static float width(final String text) {
        return 3 + 5 + 3 + Hyb.width(text) + 4;
    }

    /** A pill with its top left at {@code x, y}, at {@code scale}: a dot in the level's colour and the text. */
    public static void pill(final String text, final Level level, final float x, final float y, final float scale) {
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y, 0);
        org.lwjgl.opengl.GL11.glScalef(scale, scale, 1);
        final float w = width(text), h = 11;
        Hyb.roundRect(-1, -1, w + 2, h + 2, 4, Hyb.mix(EDGE, color(level), 0.5f));
        Hyb.roundRect(0, 0, w, h, 3, BACK);
        final int dot = color(level);
        final float a = pulse(level);
        Hyb.roundRect(3, 3, 5, 5, 2, (int) (0xFF * a) << 24 | dot & 0xFFFFFF);
        Hyb.text(text, 11, 2, 0xFFE5E7EB);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    /** EU/t short: 512, 1.9k, 12M. */
    public static String eu(final long eu) {
        final long a = Math.abs(eu);
        final String n = a < 10_000 ? Long.toString(a)
            : a < 1_000_000 ? String.format("%.1fk", a / 1000.0)
                : a < 1_000_000_000 ? String.format("%.1fM", a / 1e6) : String.format("%.1fG", a / 1e9);
        return (eu < 0 ? "+" : "") + n + " EU/t";
    }

    /** The pill's words for a status: a running machine's progress and power, else what is wrong. */
    public static String words(final MachineStatus s, final MachineStatus.Health h) {
        if (h.level() == Level.GOOD && s.euPerTick() != 0) return h.text() + " · " + eu(s.euPerTick());
        return h.text();
    }

    /**
     * The board: a card's pill above its top right corner, for the plan being watched, when anything is known of its
     * placed machines.
     */
    public static void onCard(final Graph graph, final Node node, @Nullable final ItemStack machine, final float cardW,
        final float scale) {
        if (node.worldLinks.isEmpty()) return;
        final UUID plan = graph.getPlanId();
        if (!LiveStatus.any(plan)) return;
        final int expected = LiveStatus.expectedMetaId(machine);
        final MachineStatus.Health h = LiveStatus.card(plan, node.id, node.worldLinks, expected);
        if (h == null) return;
        String text = h.text();
        if (node.worldLinks.size() == 1) {
            final MachineStatus s = LiveStatus.of(plan, node.id, 0);
            if (s != null) text = words(s, h);
        }
        pill(text, h.level(), cardW - width(text) * scale, -15 * scale, scale);
    }

    /**
     * The world: everything read of the machine looked at, in a panel with its top left at {@code x, y}: its name,
     * its state, its recipe's progress, power, what it makes, efficiency, maintenance and why it stopped.
     */
    static void panel(final MachineStatus s, final MachineStatus.Health h, final float x, final float y,
        final float scale) {
        final List<String> lines = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();
        if (s.found() == MachineStatus.MACHINE) {
            if (s.maxProgress() > 0) {
                lines.add(String.format("%.1f s / %.1f s", s.progress() / 20.0, s.maxProgress() / 20.0));
                colours.add(0xFFE5E7EB);
            }
            if (s.euPerTick() != 0) {
                lines.add((s.euPerTick() > 0 ? "Using " : "Making ") + eu(Math.abs(s.euPerTick())));
                colours.add(0xFFE5E7EB);
            }
            if (!s.outputs()
                .isEmpty()) {
                lines.add("→ " + s.outputs());
                colours.add(0xFFBFDBFE);
            }
            if (s.multiblock() && s.formed()) {
                lines.add("Efficiency " + s.efficiency() / 100 + "%");
                colours.add(0xFF9CA3AF);
            }
            if (s.maintenance() > 0) {
                lines.add("Maintenance: " + s.maintenance() + (s.maintenance() == 1 ? " issue" : " issues"));
                colours.add(WARN);
            }
            if (!s.message()
                .isEmpty()
                && !s.message()
                    .equals(h.text())) {
                lines.add(s.message());
                colours.add(s.critical() ? BAD : WARN);
            }
        }
        final String title = s.name()
            .isEmpty() ? "Nothing built here" : s.name();
        float w = Math.max(Hyb.width(title), width(h.text()));
        for (final String l : lines) w = Math.max(w, Hyb.width(l));
        w += 10;
        final float bar = s.found() == MachineStatus.MACHINE && s.maxProgress() > 0 ? 6 : 0;
        final float hgt = 6 + 10 + 13 + bar + 10 * lines.size() + 3;
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y, 0);
        org.lwjgl.opengl.GL11.glScalef(scale, scale, 1);
        Hyb.roundRect(-1, -1, w + 2, hgt + 2, 4, Hyb.mix(EDGE, color(h.level()), 0.6f));
        Hyb.roundRect(0, 0, w, hgt, 3, BACK);
        Hyb.text(title, 5, 5, 0xFFFFFFFF);
        org.lwjgl.opengl.GL11.glPopMatrix();
        pill(h.text(), h.level(), x + 5 * scale, y + 16 * scale, scale);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y, 0);
        org.lwjgl.opengl.GL11.glScalef(scale, scale, 1);
        float ly = 30;
        if (bar > 0) {
            Hyb.rect(5, ly, w - 10, 3, 0xFF2A2D33);
            Hyb.rect(5, ly, (w - 10) * s.percent() / 100f, 3, color(h.level()));
            ly += bar;
        }
        for (int i = 0; i < lines.size(); i++) Hyb.text(lines.get(i), 5, ly + 10 * i, colours.get(i));
        org.lwjgl.opengl.GL11.glPopMatrix();
    }
}
