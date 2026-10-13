package com.gtnhplanner.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Cuts a message's bytes into parts a packet takes (a custom payload in 1.7.10 holds under 32 KB) and puts them back
 * together on the other side. Pure: {@code net/Frame} carries the parts.
 */
public final class Chunker {

    /** The most bytes of a message in one part. */
    public static final int PART = 30_000;

    /** The most bytes of messages being put together at once, per sender. */
    public static final int MAX_PENDING = Proto.MAX_BLOB + 1024 * 1024;

    /** One part of a message: which message (per sender), which part of how many, and its bytes. */
    public record Part(int transfer, int index, int total, byte[] bytes) {}

    private Chunker() {}

    public static List<Part> split(final int transfer, final byte[] message) {
        final int total = Math.max(1, (message.length + PART - 1) / PART);
        final List<Part> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final int from = i * PART, to = Math.min(message.length, from + PART);
            final byte[] bytes = new byte[to - from];
            System.arraycopy(message, from, bytes, 0, bytes.length);
            parts.add(new Part(transfer, i, total, bytes));
        }
        return parts;
    }

    /** Puts one sender's parts back together; parts of a message arrive in order (one connection). */
    public static final class Assembler {

        private final Map<Integer, List<byte[]>> pending = new HashMap<>();
        private int pendingBytes;

        /** The whole message once its last part is in, else null. Malformed or too big: dropped, null. */
        @Nullable
        public byte[] accept(final Part part) {
            if (part.total() <= 0 || part.index() < 0 || part.index() >= part.total()) return null;
            if (part.total() == 1) return part.bytes();
            List<byte[]> got = pending.get(part.transfer());
            if (part.index() == 0) {
                if (got != null) drop(part.transfer());
                got = new ArrayList<>(part.total());
                pending.put(part.transfer(), got);
            } else if (got == null || got.size() != part.index()) {
                // A part out of order: the message is lost.
                if (got != null) drop(part.transfer());
                return null;
            }
            got.add(part.bytes());
            pendingBytes += part.bytes().length;
            if (pendingBytes > MAX_PENDING) {
                pending.clear();
                pendingBytes = 0;
                return null;
            }
            if (got.size() < part.total()) return null;
            pending.remove(part.transfer());
            int size = 0;
            for (final byte[] b : got) size += b.length;
            pendingBytes -= size;
            final byte[] whole = new byte[size];
            int at = 0;
            for (final byte[] b : got) {
                System.arraycopy(b, 0, whole, at, b.length);
                at += b.length;
            }
            return whole;
        }

        private void drop(final int transfer) {
            final List<byte[]> got = pending.remove(transfer);
            if (got != null) for (final byte[] b : got) pendingBytes -= b.length;
        }
    }
}
