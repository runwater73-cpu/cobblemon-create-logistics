package dev.cobblemoncreate.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Small server-authored progress view; virtual coordinates never request terrain. */
public record JourneyProgress(String phase, int percent, int remainingSeconds,
                              int elapsedSeconds, int remainingBlocks, BlockPos position) {
    public static final JourneyProgress NONE = new JourneyProgress("", 0, -1, 0, 0, BlockPos.ZERO);

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(phase, 32);
        buf.writeVarInt(percent);
        buf.writeVarInt(remainingSeconds);
        buf.writeVarInt(elapsedSeconds);
        buf.writeVarInt(remainingBlocks);
        buf.writeBlockPos(position);
    }

    public static JourneyProgress read(RegistryFriendlyByteBuf buf) {
        return new JourneyProgress(buf.readUtf(32), Math.clamp(buf.readVarInt(), 0, 100),
                Math.max(-1, buf.readVarInt()), Math.max(0, buf.readVarInt()),
                Math.max(0, buf.readVarInt()), buf.readBlockPos());
    }
}
