package dev.cobblemoncreate.logistics.client;

import com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt;
import com.cobblemon.mod.common.client.gui.ProfileTransformType;
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.util.math.QuaternionUtilsKt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reuses the same Cobblemon profile renderer as PC storage slots. */
final class HubPokemonPortraits {
    private final Map<UUID, FloatingState> states = new HashMap<>();

    void retain(Set<UUID> visibleIds) {
        states.keySet().retainAll(visibleIds);
    }

    void render(GuiGraphics graphics, UUID pokemonId, ResourceLocation species, Set<String> aspects,
                int x, int y, int size, float partialTick, double uiScale) {
        FloatingState state = states.computeIfAbsent(pokemonId, ignored -> new FloatingState());
        state.setCurrentAspects(aspects);
        Quaternionf rotation = QuaternionUtilsKt.fromEulerXYZDegrees(
                new Quaternionf(), new Vector3f(13.0F, 35.0F, 0.0F));
        graphics.enableScissor((int) Math.floor((x - 2) * uiScale),
                (int) Math.floor((y - 2) * uiScale),
                (int) Math.ceil((x + size + 2) * uiScale),
                (int) Math.ceil((y + size + 2) * uiScale));
        graphics.pose().pushPose();
        graphics.pose().translate(x + size / 2.0D, y + 1.0D, 0.0D);
        graphics.pose().scale(size / 10.0F, size / 10.0F, 1.0F);
        try {
            PokemonGuiUtilsKt.drawProfilePokemon(species, graphics.pose(), rotation,
                    PoseType.PROFILE, state, partialTick, 4.5F, ProfileTransformType.PROFILE, false, false,
                    1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 13);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
}
