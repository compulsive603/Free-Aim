package com.example.freeaim;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: where the hand is aiming and whether the player is guarding / mid-swing. */
public record HandStatePacket(boolean active, float yaw, float pitch, boolean guard, boolean swinging) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeBoolean(guard);
        buf.writeBoolean(swinging);
    }

    public static HandStatePacket decode(FriendlyByteBuf buf) {
        return new HandStatePacket(buf.readBoolean(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(HandStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null && Float.isFinite(msg.yaw) && Float.isFinite(msg.pitch)) {
                ServerParry.update(sender, msg);
            }
        });
        c.setPacketHandled(true);
    }
}
