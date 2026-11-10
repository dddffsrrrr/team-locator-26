package dev.example.teamlocator.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.example.teamlocator.TeamLocatorClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Reads the field of view Minecraft really uses to draw the world this frame.
 * Includes zoom mods, OptiFine zoom, sprint/speed FOV effects and spyglass.
 * Only reads the value, never changes it. Applied late (priority 2000) so other mods'
 * changes are already included. If this ever fails to apply, the mod falls back to the
 * FOV from the options instead of crashing (require = 0).
 */
@Mixin(value = GameRenderer.class, priority = 2000)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), require = 0)
    private void teamLocator$captureFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Float> cir) {
        if (useFovSetting) { // world projection (false = first-person hand)
            TeamLocatorClient.renderFov = cir.getReturnValueF();
        }
    }
}
