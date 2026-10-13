package com.gtnhplanner.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * One packet of the planner's channel: a {@link Chunker.Part} of a {@link Proto} message. One class each way, since
 * a channel tells its messages apart by class.
 */
public abstract class Frame implements IMessage {

    Chunker.Part part;

    protected Frame() {}

    protected Frame(final Chunker.Part part) {
        this.part = part;
    }

    @Override
    public void fromBytes(final ByteBuf buf) {
        final int transfer = buf.readInt(), index = buf.readInt(), total = buf.readInt(), length = buf.readInt();
        if (length < 0 || length > Chunker.PART) throw new IllegalArgumentException("Bad planner frame: " + length);
        final byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        part = new Chunker.Part(transfer, index, total, bytes);
    }

    @Override
    public void toBytes(final ByteBuf buf) {
        buf.writeInt(part.transfer());
        buf.writeInt(part.index());
        buf.writeInt(part.total());
        buf.writeInt(part.bytes().length);
        buf.writeBytes(part.bytes());
    }

    /** Client to server. */
    public static final class ToServer extends Frame {

        public ToServer() {}

        ToServer(final Chunker.Part part) {
            super(part);
        }
    }

    /** Server to client. */
    public static final class ToClient extends Frame {

        public ToClient() {}

        ToClient(final Chunker.Part part) {
            super(part);
        }
    }
}
