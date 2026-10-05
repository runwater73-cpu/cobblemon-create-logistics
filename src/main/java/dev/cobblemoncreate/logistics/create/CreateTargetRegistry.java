package dev.cobblemoncreate.logistics.create;

import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.logistics.packagePort.PackagePortTargetType;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Registers only a Create target type; Create still owns package routing. */
public final class CreateTargetRegistry {
    private static final DeferredRegister<PackagePortTargetType> TARGET_TYPES =
            DeferredRegister.create(CreateRegistries.PACKAGE_PORT_TARGET_TYPE, CobblemonCreateLogistics.MOD_ID);

    public static final DeferredHolder<PackagePortTargetType, PackagePortTargetType> COBBLEMON_HUB =
            TARGET_TYPES.register("cobblemon_hub", CobblemonHubTarget.Type::new);

    private CreateTargetRegistry() {}

    public static void register(IEventBus modEventBus) {
        TARGET_TYPES.register(modEventBus);
    }
}
