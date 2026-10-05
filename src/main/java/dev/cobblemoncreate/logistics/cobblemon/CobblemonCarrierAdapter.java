package dev.cobblemoncreate.logistics.cobblemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.cobblemoncreate.logistics.CarrierProfile;
import dev.cobblemoncreate.logistics.WorkerLease;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** Reads the live Cobblemon PC/entity without copying Pokemon state. */
public final class CobblemonCarrierAdapter {
    private CobblemonCarrierAdapter() {}

    public static Optional<ActiveCarrier> findActive(ServerLevel level, WorkerLease lease,
                                                      CarrierProfile profile) {
        try {
            var pc = Cobblemon.INSTANCE.getStorage()
                    .getPC(lease.storageUuid(), level.registryAccess());
            Pokemon pokemon = pc.get(lease.pokemonUuid());
            boolean generic = ResourceLocation.parse("cobblemon:generic").equals(profile.species());
            if (pokemon == null || pokemon.getSpecies() == null
                    || !lease.tetheringId().equals(pokemon.getTetheringId())
                    || (!generic && !profile.species().equals(pokemon.getSpecies().getResourceIdentifier()))) {
                return Optional.empty();
            }
            PokemonEntity entity = pokemon.getEntity();
            if (entity == null || entity.level() != level || !entity.isAlive()
                    || level.getEntity(entity.getId()) != entity || entity.isBusy()) {
                return Optional.empty();
            }
            return Optional.of(new ActiveCarrier(entity));
        } catch (RuntimeException ignored) {
            // A PC may be unavailable during login/logout or a storage reload.
            // The task remains durable and can be retried without force-loading.
            return Optional.empty();
        }
    }


    public record ActiveCarrier(PokemonEntity entity) {}
}
