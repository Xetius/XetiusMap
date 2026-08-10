package dev.xetius.xetiusmap.common.net;

/** Anything that can be put on the wire. The encoded form is {@code id | body}. */
public interface Packet {

    int id();

    void write(ByteWriter w);

    default byte[] encode() {
        ByteWriter w = new ByteWriter(128);
        w.writeByte(id());
        write(w);
        return w.toByteArray();
    }
}
