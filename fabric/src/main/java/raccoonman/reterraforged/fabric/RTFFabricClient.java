package raccoonman.reterraforged.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import raccoonman.reterraforged.client.debug.FlowFieldDebugRenderer;
import raccoonman.reterraforged.client.gui.screen.worldselection.AutoPresetConfig;
import raccoonman.reterraforged.fabric.network.RTFFabricClientNetworking;

public class RTFFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AutoPresetConfig.ensureCreated();
        RTFFabricClientNetworking.init();

        Minecraft mc = Minecraft.getInstance();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            FlowFieldDebugRenderer.render(
                    context.matrixStack(),
                    context.camera(),
                    mc.renderBuffers().bufferSource()
            );
        });
    }
}
