package pl.autotrzcina;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import pl.autotrzcina.gui.AutoScreen;

public class AutoTrzcinaMod implements ClientModInitializer {
    private static KeyBinding menuKey;
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        AutoConfig.load();

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autotrzcina.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.autotrzcina"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autotrzcina.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.autotrzcina"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                mc.setScreen(new AutoScreen(mc.currentScreen));
            }
            while (toggleKey.wasPressed()) {
                if (FarmBot.isRunning()) FarmBot.stop(mc, "Zatrzymano");
                else FarmBot.start(mc);
            }
            FarmBot.tick(mc);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> FarmBot.onDisconnect());

        // maly pasek statusu w rogu ekranu
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            AutoConfig c = AutoConfig.get();
            if (!c.hud || !FarmBot.isRunning() || mc.options.hudHidden) return;
            int acc = 0xFF000000 | c.accent;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal("AutoTrzcina: " + FarmBot.status), 6, 6, acc);
        });

        // numery slotow w oknie sklepu (pomaga ustawic sloty do klikania)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GenericContainerScreen gcs) {
                ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                    if (!AutoConfig.get().showSlotNumbers) return;
                    GenericContainerScreenHandler handler = gcs.getScreenHandler();
                    int rows = handler.getRows();
                    int left = (s.width - 176) / 2;
                    int top = (s.height - (114 + rows * 18)) / 2;
                    for (int i = 0; i < rows * 9 && i < handler.slots.size(); i++) {
                        Slot slot = handler.slots.get(i);
                        ctx.drawText(client.textRenderer, String.valueOf(i),
                                left + slot.x + 1, top + slot.y + 1, 0xFFFFFF00, true);
                    }
                });
            }
        });
    }
}
