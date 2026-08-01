package com.spirit.koil.api.model.presence;

import net.minecraft.network.PacketByteBuf;

/**
 * Versioned privacy-minimal packet payload shared by sync and broadcast
 * connectors. UUID ownership is carried by the enclosing broadcast packet.
 */
public final class ModelPresenceWireCodec {
    private ModelPresenceWireCodec() {
    }

    public static void write(
            PacketByteBuf buffer,
            ModelPresenceState.Snapshot snapshot,
            long publishedAtMillis
    ) {
        ModelPresenceState.Snapshot safe = snapshot == null
                ? ModelPresenceState.Snapshot.noneAt(publishedAtMillis)
                : snapshot;
        buffer.writeVarInt(ModelPresenceState.PROTOCOL_VERSION);
        buffer.writeString(safe.kind().name().toLowerCase(java.util.Locale.ROOT), 32);
        buffer.writeString(safe.semanticState(), 64);
        buffer.writeBoolean(safe.active());
        buffer.writeLong(publishedAtMillis <= 0L ? System.currentTimeMillis() : publishedAtMillis);
    }

    public static Decoded read(PacketByteBuf buffer) {
        int version = buffer.readVarInt();
        String kind = buffer.readString(32);
        String state = buffer.readString(64);
        boolean active = buffer.readBoolean();
        long updatedAt = buffer.readLong();
        return new Decoded(
                version,
                new ModelPresenceState.Snapshot(
                        ModelPresenceState.parseKind(kind),
                        state,
                        updatedAt,
                        active
                )
        );
    }

    public record Decoded(int version, ModelPresenceState.Snapshot snapshot) {
        public boolean supported() {
            return this.version >= 1 && this.version <= ModelPresenceState.PROTOCOL_VERSION;
        }
    }
}
