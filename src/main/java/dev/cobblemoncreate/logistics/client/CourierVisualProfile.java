package dev.cobblemoncreate.logistics.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only package projection tuning. It changes presentation only; the
 * package remains the Create ItemStack owned by DeliveryTask.
 */
public record CourierVisualProfile(
        float offsetX,
        float offsetY,
        float offsetZ,
        float scale,
        float bobAmplitude,
        float bobSpeed,
        float rotationSpeed
) {
    private static final Map<ResourceLocation, CourierVisualProfile> PROFILES = new ConcurrentHashMap<>();
    private static final CourierVisualProfile GENERIC =
            new CourierVisualProfile(0.0F, 0.0F, 0.0F, 0.65F, 0.04F, 0.25F, 1.5F);

    static {
        register(ResourceLocation.fromNamespaceAndPath("cobblemon", "eevee"),
                new CourierVisualProfile(0.0F, 0.0F, 0.0F, 0.58F, 0.035F, 0.28F, 1.5F));
        register(ResourceLocation.fromNamespaceAndPath("cobblemon", "pidgey"),
                new CourierVisualProfile(0.0F, -0.08F, 0.0F, 0.48F, 0.015F, 0.18F, 0.0F));
        register(ResourceLocation.fromNamespaceAndPath("cobblemon", "gastly"),
                new CourierVisualProfile(0.0F, 0.06F, -0.12F, 0.54F, 0.06F, 0.2F, 7.0F));
        register(ResourceLocation.fromNamespaceAndPath("cobblemon", "abra"),
                new CourierVisualProfile(0.0F, 0.04F, -0.16F, 0.5F, 0.07F, 0.15F, 10.0F));
    }

    public static void register(ResourceLocation species, CourierVisualProfile profile) {
        PROFILES.put(species, profile);
    }

    public static CourierVisualProfile resolve(ResourceLocation species) {
        return PROFILES.getOrDefault(species, GENERIC);
    }
}
