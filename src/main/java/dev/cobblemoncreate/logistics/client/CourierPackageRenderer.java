package dev.cobblemoncreate.logistics.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.math.Axis;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.CarrierProfile;
import dev.cobblemoncreate.logistics.CarrierProfiles;
import dev.cobblemoncreate.logistics.TransportCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/** Visual projection of the task's package; it never enters an entity inventory. */
public final class CourierPackageRenderer {
    private CourierPackageRenderer() {}

    @SubscribeEvent
    public static void render(RenderLivingEvent.Post<?, ?> event) {
        if (!(event.getEntity() instanceof PokemonEntity pokemon) || pokemon.isInvisible()) return;
        ItemStack shown = pokemon.getShownItem();
        if (!PackageItem.isPackage(shown)) return;

        var pose = event.getPoseStack();
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - Mth.rotLerp(event.getPartialTick(),
                pokemon.yBodyRotO, pokemon.yBodyRot)));
        CourierVisualProfile profile = CourierVisualProfile.resolve(
                pokemon.getPokemon().getSpecies().getResourceIdentifier());
        CarrierProfile carrier = CarrierProfiles.resolve(pokemon.getPokemon());
        TransportCapability capability = carrier == null ? TransportCapability.GROUND : carrier.capability();
        float time = pokemon.tickCount + event.getPartialTick();
        float bob = (float) Math.sin(time * profile.bobSpeed()) * profile.bobAmplitude();
        float height = pokemon.getBbHeight();
        float front = -pokemon.getBbWidth() * 0.55F - 0.18F;
        switch (capability) {
            case FLYING -> {
                pose.translate(profile.offsetX(), height * 0.3F + profile.offsetY() + bob,
                        front * 0.5F + profile.offsetZ());
                pose.mulPose(Axis.XP.rotationDegrees(8.0F));
            }
            case SWIMMING -> {
                pose.translate(profile.offsetX(), height * 0.55F + profile.offsetY() + bob,
                        front + profile.offsetZ());
                pose.mulPose(Axis.XP.rotationDegrees((float) Math.sin(time * 0.16F) * 5.0F));
            }
            case GROUND -> {
                pose.translate(profile.offsetX(), height * 0.62F + profile.offsetY() + bob,
                        front + profile.offsetZ());
            }
        }
        float scale = Math.min(profile.scale(), Math.max(0.32F, pokemon.getBbWidth() * 0.55F));
        pose.scale(scale, scale, scale);
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        renderer.renderStatic(shown, ItemDisplayContext.FIXED, event.getPackedLight(),
                OverlayTexture.NO_OVERLAY, pose, event.getMultiBufferSource(), pokemon.level(), pokemon.getId());
        pose.popPose();
    }
}
