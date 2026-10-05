package dev.cobblemoncreate.logistics.hub;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;

/** Durable address index; it stores endpoints, never packages or Pokemon. */
final class HubAddressSavedData extends SavedData {
    private static final String DATA_NAME = "cobblemon_create_logistics_hub_addresses";
    private final Map<String, HubAddressRegistry.Destination> entries = new LinkedHashMap<>();

    static SavedData.Factory<HubAddressSavedData> factory() {
        return new SavedData.Factory<>(HubAddressSavedData::new, HubAddressSavedData::load, null);
    }

    private static HubAddressSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        HubAddressSavedData data = new HubAddressSavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                ResourceKey<Level> dimension = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(entry.getString("Dimension")));
                HubAddressRegistry.Destination destination = new HubAddressRegistry.Destination(
                        dimension, net.minecraft.core.BlockPos.of(entry.getLong("Pos")),
                        entry.getString("Address"));
                if (!destination.address().isBlank()) data.entries.put(key(destination), destination);
            } catch (RuntimeException ignored) {
                // A malformed endpoint must not prevent the world from loading.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (HubAddressRegistry.Destination destination : entries.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", destination.dimension().location().toString());
            entry.putLong("Pos", destination.pos().asLong());
            entry.putString("Address", destination.address());
            list.add(entry);
        }
        tag.put("Entries", list);
        return tag;
    }

    static HubAddressSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    void replaceHub(ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos, String address) {
        entries.entrySet().removeIf(entry -> entry.getValue().dimension().equals(dimension)
                && entry.getValue().pos().equals(pos));
        if (!address.isBlank()) {
            HubAddressRegistry.Destination destination = new HubAddressRegistry.Destination(dimension, pos.immutable(), address);
            entries.put(key(destination), destination);
        }
        setDirty();
    }

    void removeHub(ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        if (entries.entrySet().removeIf(entry -> entry.getValue().dimension().equals(dimension)
                && entry.getValue().pos().equals(pos))) setDirty();
    }

    Collection<HubAddressRegistry.Destination> entries() { return entries.values(); }

    private static String key(HubAddressRegistry.Destination destination) {
        return destination.dimension().location() + "|" + destination.pos().asLong();
    }
}
