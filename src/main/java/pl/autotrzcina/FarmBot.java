package pl.autotrzcina;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

public final class FarmBot {
    private enum Phase {
        WAIT_NEXT, GO_BUTTON, AIM, PRESS, GO_COLLECT, COLLECT_WAIT,
        GO_SELL, SELL_OPEN, SELL_WAIT_GUI, SELL_ACTIONS, SELL_CLOSE
    }

    private static boolean running = false;
    private static Phase phase = Phase.WAIT_NEXT;
    public static volatile String status = "Wylaczony";
    public static int cycles = 0;

    private static long phaseStart, nextPressAt, nextActionAt;
    private static BlockPos navTarget;
    private static long navStart, lastMove;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static int retries, failCount;
    private static final Deque<int[]> actions = new ArrayDeque<>();

    private FarmBot() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static long secondsToNext() {
        return Math.max(0, (nextPressAt - System.currentTimeMillis()) / 1000);
    }

    public static boolean baritoneLoaded() {
        FabricLoader l = FabricLoader.getInstance();
        return AutoConfig.get().skipBaritoneCheck
                || l.isModLoaded("baritone")
                || l.isModLoaded("baritone-meteor")
                || l.isModLoaded("baritone-api-fabric")
                || l.isModLoaded("baritone-standalone-fabric");
    }

    public static void start(MinecraftClient mc) {
        AutoConfig c = AutoConfig.get();
        if (mc.player == null) return;
        if (!baritoneLoaded()) {
            status = "Brak moda Baritone";
            say(mc, "Zainstaluj mod Baritone (chodzenie).");
            return;
        }
        if (c.buttonStand == null || c.button == null || c.collect == null) {
            status = "Ustaw pozycje w menu (O)";
            say(mc, "Najpierw ustaw pozycje w menu (O).");
            return;
        }
        running = true;
        phase = Phase.WAIT_NEXT;
        nextPressAt = 0;
        navTarget = null;
        failCount = 0;
        status = "Start";
        chat(mc, "#set allowBreak false");
        chat(mc, "#set allowPlace false");
    }

    public static void stop(MinecraftClient mc, String reason) {
        if (running) {
            chat(mc, "#cancel");
            chat(mc, "#set allowBreak true");
            chat(mc, "#set allowPlace true");
            if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
                mc.player.closeHandledScreen();
            }
        }
        running = false;
        status = reason;
    }

    public static void onDisconnect() {
        running = false;
        status = "Rozlaczono";
    }

    public static void tick(MinecraftClient mc) {
        if (!running) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            running = false;
            status = "Rozlaczono";
            return;
        }
        if (p.isDead() || p.getHealth() <= 0) {
            stop(mc, "Zginales - zatrzymano");
            return;
        }
        AutoConfig c = AutoConfig.get();
        long now = System.currentTimeMillis();

        switch (phase) {
            case WAIT_NEXT -> {
                long left = nextPressAt - now;
                if (left <= 0) {
                    go(Phase.GO_BUTTON, now);
                } else {
                    status = "Czekam na kolejny cykl: " + fmt(left / 1000);
                }
            }
            case GO_BUTTON -> {
                status = "Ide do przycisku";
                if (navigateTo(mc, c.buttonStand.toBlockPos(), now)) go(Phase.AIM, now);
            }
            case AIM -> {
                BlockPos b = c.button.toBlockPos();
                if (!mc.world.getBlockState(b).isIn(BlockTags.BUTTONS)) {
                    fail(mc, "W ustawionym miejscu nie ma przycisku", now);
                    return;
                }
                aimAt(p, Vec3d.ofCenter(b));
                status = "Celuje w przycisk";
                if (now - phaseStart > 300) go(Phase.PRESS, now);
            }
            case PRESS -> {
                BlockPos b = c.button.toBlockPos();
                Vec3d center = Vec3d.ofCenter(b);
                aimAt(p, center);
                Vec3d eye = p.getEyePos();
                if (eye.distanceTo(center) > 4.6) {
                    fail(mc, "Za daleko od przycisku", now);
                    return;
                }
                Direction face = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
                BlockHitResult hit = new BlockHitResult(center, face, b, false);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                p.swingHand(Hand.MAIN_HAND);
                nextPressAt = now + c.intervalSec * 1000L;
                failCount = 0;
                status = "Kliknieto przycisk";
                go(Phase.GO_COLLECT, now);
            }
            case GO_COLLECT -> {
                status = "Ide zbierac trzcine";
                if (navigateTo(mc, c.collect.toBlockPos(), now)) go(Phase.COLLECT_WAIT, now);
            }
            case COLLECT_WAIT -> {
                long left = phaseStart + c.collectSec * 1000L - now;
                if (left <= 0) {
                    boolean sameSpot = c.sell == null
                            || (c.sell.x == c.collect.x && c.sell.y == c.collect.y && c.sell.z == c.collect.z);
                    go(sameSpot ? Phase.SELL_OPEN : Phase.GO_SELL, now);
                } else {
                    status = "Zbieram trzcine: " + fmt(left / 1000);
                }
            }
            case GO_SELL -> {
                status = "Ide sprzedawac";
                if (navigateTo(mc, c.sell.toBlockPos(), now)) go(Phase.SELL_OPEN, now);
            }
            case SELL_OPEN -> {
                if (countCane(p) == 0) {
                    status = "Brak trzciny do sprzedania";
                    go(Phase.WAIT_NEXT, now);
                    return;
                }
                String cmd = c.sellCommand == null ? "" : c.sellCommand.trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isEmpty()) {
                    fail(mc, "Brak komendy sklepu w menu", now);
                    return;
                }
                p.networkHandler.sendChatCommand(cmd);
                status = "Otwieram sklep";
                go(Phase.SELL_WAIT_GUI, now);
            }
            case SELL_WAIT_GUI -> {
                if (mc.currentScreen instanceof GenericContainerScreen gcs) {
                    buildActions(gcs, c);
                    nextActionAt = now + 500;
                    go(Phase.SELL_ACTIONS, now);
                } else if (now - phaseStart > 6000) {
                    fail(mc, "Sklep sie nie otworzyl (sprawdz komende)", now);
                }
            }
            case SELL_ACTIONS -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen)) {
                    finishSell(mc, now);
                    return;
                }
                if (now < nextActionAt) return;
                int[] a = actions.pollFirst();
                if (a == null) {
                    go(Phase.SELL_CLOSE, now);
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                if (a[0] >= 0 && a[0] < h.slots.size()) {
                    mc.interactionManager.clickSlot(h.syncId, a[0], 0,
                            a[1] == 1 ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP, p);
                }
                nextActionAt = now + c.clickDelayMs;
                status = "Sprzedaje...";
            }
            case SELL_CLOSE -> {
                if (now - phaseStart > 800) finishSell(mc, now);
            }
        }
    }

    private static void go(Phase next, long now) {
        phase = next;
        phaseStart = now;
        navTarget = null;
    }

    private static void finishSell(MinecraftClient mc, long now) {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        cycles++;
        failCount = 0;
        status = "Sprzedano (cykl " + cycles + ")";
        go(Phase.WAIT_NEXT, now);
    }

    private static void fail(MinecraftClient mc, String msg, long now) {
        failCount++;
        chat(mc, "#cancel");
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) {
            mc.player.closeHandledScreen();
        }
        say(mc, msg);
        if (failCount >= 3) {
            stop(mc, "Zatrzymano: " + msg);
            return;
        }
        status = msg;
        nextPressAt = Math.max(nextPressAt, now + 30_000);
        go(Phase.WAIT_NEXT, now);
    }

    private static boolean navigateTo(MinecraftClient mc, BlockPos target, long now) {
        ClientPlayerEntity p = mc.player;
        if (!target.equals(navTarget)) {
            navTarget = target;
            retries = 0;
            navStart = now;
            issueGoto(mc, target, now);
            return false;
        }
        if (p.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) < 2.25) {
            chat(mc, "#cancel");
            return true;
        }
        Vec3d pos = p.getPos();
        if (pos.squaredDistanceTo(lastPos) > 0.04) {
            lastPos = pos;
            lastMove = now;
        }
        if (now - lastMove > 8000) {
            retries++;
            if (retries > 3) {
                fail(mc, "Nie moge dojsc do celu", now);
                return false;
            }
            issueGoto(mc, target, now);
        }
        if (now - navStart > 240_000) {
            fail(mc, "Za dlugo ide do celu", now);
        }
        return false;
    }

    private static void issueGoto(MinecraftClient mc, BlockPos t, long now) {
        chat(mc, "#goto " + t.getX() + " " + t.getY() + " " + t.getZ());
        lastMove = now;
        if (mc.player != null) lastPos = mc.player.getPos();
    }

    private static void aimAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, flat) * 180.0 / Math.PI);
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    private static int countCane(ClientPlayerEntity p) {
        int n = 0;
        for (var s : p.getInventory().main) {
            if (s.isOf(Items.SUGAR_CANE)) n += s.getCount();
        }
        return n;
    }

    private static void buildActions(GenericContainerScreen gcs, AutoConfig c) {
        actions.clear();
        GenericContainerScreenHandler h = gcs.getScreenHandler();
        int container = h.getRows() * 9;

        if (c.depositMode) {
            for (int i = container; i < h.slots.size(); i++) {
                if (h.slots.get(i).getStack().isOf(Items.SUGAR_CANE)) actions.add(new int[]{i, 1});
            }
        }

        boolean any = false;
        for (String s : c.sellSlots.split("[,;\\s]+")) {
            if (s.isBlank()) continue;
            try {
                actions.add(new int[]{Integer.parseInt(s.trim()), 0});
                any = true;
            } catch (NumberFormatException ignored) {
            }
        }

        if (!any && !c.depositMode) {
            for (int i = 0; i < container && i < h.slots.size(); i++) {
                if (h.slots.get(i).getStack().isOf(Items.SUGAR_CANE)) {
                    actions.add(new int[]{i, 0});
                    break;
                }
            }
        }
    }

    private static void chat(MinecraftClient mc, String msg) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(msg);
        }
    }

    private static void say(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoTrzcina] " + text), true);
    }

    public static String fmt(long sec) {
        if (sec < 60) return sec + " s";
        return (sec / 60) + " min " + (sec % 60) + " s";
    }
}
