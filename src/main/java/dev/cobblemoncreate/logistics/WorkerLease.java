package dev.cobblemoncreate.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * A lease over an existing Cobblemon Pokemon. This is deliberately small and
 * does not duplicate the Pokemon's species, moves, inventory, or NBT state.
 */
public record WorkerLease(
        UUID ownerUuid,
        UUID pokemonUuid,
        ResourceLocation species,
        ResourceKey<Level> dimension,
        BlockPos anchor,
        UUID tetheringId,
        UUID storageUuid,
        boolean homeReported
) {
    public WorkerLease {
        anchor = anchor.immutable();
    }

    /** Keeps existing fixtures source-compatible; a newly bound worker starts home. */
    public WorkerLease(UUID ownerUuid, UUID pokemonUuid, ResourceLocation species,
                       ResourceKey<Level> dimension, BlockPos anchor,
                       UUID tetheringId, UUID storageUuid) {
        this(ownerUuid, pokemonUuid, species, dimension, anchor, tetheringId, storageUuid, true);
    }

    public WorkerLease withHomeReported(boolean reported) {
        return new WorkerLease(ownerUuid, pokemonUuid, species, dimension, anchor,
                tetheringId, storageUuid, reported);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Owner", ownerUuid);
        tag.putUUID("Pokemon", pokemonUuid);
        tag.putString("Species", species.toString());
        tag.putString("Dimension", dimension.location().toString());
        tag.put("Anchor", NbtUtils.writeBlockPos(anchor));
        tag.putUUID("Tethering", tetheringId);
        tag.putUUID("Storage", storageUuid);
        tag.putBoolean("HomeReported", homeReported);
        return tag;
    }

    public static WorkerLease load(CompoundTag tag) {
        UUID owner = tag.getUUID("Owner");
        ResourceKey<Level> dimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("Dimension")));
        BlockPos anchor = NbtUtils.readBlockPos(tag, "Anchor").orElse(BlockPos.ZERO);
        return new WorkerLease(
                owner,
                tag.getUUID("Pokemon"),
                ResourceLocation.parse(tag.getString("Species")),
                dimension,
                anchor,
                tag.hasUUID("Tethering") ? tag.getUUID("Tethering") : UUID.randomUUID(),
                tag.hasUUID("Storage") ? tag.getUUID("Storage") : owner,
                !tag.contains("HomeReported") || tag.getBoolean("HomeReported"));
    }
}
