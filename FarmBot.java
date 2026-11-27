package pl.autotrzcina;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.GenericContainerScreenHandler;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FarmBot {
    private enum Phase {
        GO_BUTTON, AIM, PRESS, GO_COLLECT, STAND,
        SELL_OPEN, SELL_WAIT_GUI, SELL_ACTIONS, SELL_CLOSE
    }

    private static boolean running = false;
    private static Phase phase = Phase.STAND;
    public static volatile String status = "Wylaczony";
    public static int cycles = 0;

    private static long phaseStart, nextPressAt, nextSellAt, nextActionAt;
    private static BlockPos navTarget;
    private static long navStart, lastMove;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static int retries, failCount;

    private static final Deque<Integer> openSlots = new ArrayDeque<>();
    private static boolean waitingWindow = false;
    private static int syncAtClick = -1;
    private static long waitStart;
    private static int shiftTries;

    // statystyki / raport
    private static int lastCane = 0, caneAtOpen = 0, collectedWhileReport = 0;
    private static long moneyWindowUntil = 0, reportAt = 0, sessionStart = 0;
    private static boolean reportPending = false, moneyFound = false;
    private static double pendingMoney = 0, sessionMoney = 0;

    // auto-skok
    private static long nextJumpAt = 0, jumpReleaseAt = 0;

    private FarmBot() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static long secondsToNext() {
        return Math.max(0, (nextPressAt - System.currentTimeMillis()) / 1000);
    }

    public static double perHour() {
        long ms = System.currentTimeMillis() - sessionStart;
        if (!running || ms < 60_000) return 0;
        return sessionMoney * 3_600_000.0 / ms;
    }

    public static void resetStats() {
        AutoConfig c = AutoConfig.get();
        c.totalCollected = 0;
        c.totalSold = 0;
        c.totalMoney = 0;
        sessionMoney = 0;
        sessionStart = System.currentTimeMillis();
        cycles = 0;
        AutoConfig.save();
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
        long now = System.currentTimeMillis();
        running = true;
        phase = Phase.GO_BUTTON;
        nextPressAt = 0;
        nextSellAt = 0;
        nextJumpAt = now + c.jumpMinutes * 60_000L;
        sessionStart = now;
        sessionMoney = 0;
        lastCane = countCane(mc.player);
        reportPending = false;
        navTarget = null;
        failCount = 0;
        waitingWindow = false;
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
            if (jumpReleaseAt != 0) {
                mc.options.jumpKey.setPressed(false);
                jumpReleaseAt = 0;
            }
            AutoConfig.save();
        }
        running = false;
        status = reason;
    }

    public static void onDisconnect() {
        running = false;
        status = "Rozlaczono";
        AutoConfig.save();
    }

    /** Wiadomosci z serwera - szukamy kwoty za sprzedaz (tylko w oknie sprzedazy). */
    public static void onMessage(Text msg) {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now > moneyWindowUntil) return;
        AutoConfig c = AutoConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        String s = msg.getString();
        if (c.debugShop && mc.player != null) {
            mc.player.sendMessage(Text.literal("[Debug] " + s), false);
        }
        if (c.moneyRegex == null || c.moneyRegex.isBlank()) return;
        try {
            Matcher m = Pattern.compile(c.moneyRegex).matcher(s);
            if (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String grp = m.group(g);
                    if (grp != null) {
                        double v = parseAmount(grp);
                        if (v > 0) {
                            pendingMoney += v;
                            moneyFound = true;
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
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

        // puszczenie klawisza skoku
        if (jumpReleaseAt != 0 && now >= jumpReleaseAt) {
            mc.options.jumpKey.setPressed(false);
            jumpReleaseAt = 0;
        }

        // statystyki: ile trzciny przybylo w ekwipunku
        int caneNow = countCane(p);
        if (caneNow > lastCane && mc.currentScreen == null) {
            int diff = caneNow - lastCane;
            c.totalCollected += diff;
            if (reportPending) collectedWhileReport += diff;
        }
        lastCane = caneNow;

        if (reportPending && now >= reportAt) sendReport(mc, c);

        switch (phase) {
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
                status = "Ide na farme";
                if (navigateTo(mc, c.collect.toBlockPos(), now)) go(Phase.STAND, now);
            }
            case STAND -> {
                long untilPress = nextPressAt - now;
                if (untilPress <= 0) {
                    go(Phase.GO_BUTTON, now);
                    return;
                }
                BlockPos col = c.collect.toBlockPos();
                if (p.squaredDistanceTo(col.getX() + 0.5, col.getY(), col.getZ() + 0.5) > 9.0) {
                    go(Phase.GO_COLLECT, now);
                    return;
                }

                // auto-skok (anty-AFK)
                if (c.autoJump) {
                    long cap = now + c.jumpMinutes * 60_000L;
                    if (nextJumpAt > cap) nextJumpAt = cap;
                    if (now >= nextJumpAt && p.isOnGround() && mc.currentScreen == null) {
                        mc.options.jumpKey.setPressed(true);
                        jumpReleaseAt = now + 150;
                        nextJumpAt = now + c.jumpMinutes * 60_000L;
                    }
                }

                // sprzedaz za kazdym razem, gdy w ekwipunku jest trzcina
                if (lastCane > 0 && now >= nextSellAt && mc.currentScreen == null) {
                    go(Phase.SELL_OPEN, now);
                    return;
                }
                status = "Na farmie | trzcina: " + lastCane + " | przycisk za " + fmt(untilPress / 1000);
            }
            case SELL_OPEN -> {
                if (lastCane == 0) {
                    go(Phase.STAND, now);
                    return;
                }
                String cmd = c.sellCommand == null ? "" : c.sellCommand.trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isEmpty()) {
                    fail(mc, "Brak komendy sklepu w menu", now);
                    return;
                }
                caneAtOpen = lastCane;
                pendingMoney = 0;
                moneyFound = false;
                reportPending = false;
                moneyWindowUntil = now + 90_000;
                p.networkHandler.sendChatCommand(cmd);
                status = "Otwieram sklep";
                go(Phase.SELL_WAIT_GUI, now);
            }
            case SELL_WAIT_GUI -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    openSlots.clear();
                    for (String s : c.sellSlots.split("[,;\\s]+")) {
                        if (s.isBlank()) continue;
                        try {
                            openSlots.add(Integer.parseInt(s.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    shiftTries = 0;
                    waitingWindow = false;
                    nextActionAt = now + 600;
                    go(Phase.SELL_ACTIONS, now);
                } else if (now - phaseStart > 6000) {
                    fail(mc, "Sklep sie nie otworzyl (sprawdz komende)", now);
                }
            }
            case SELL_ACTIONS -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) {
                    if (waitingWindow && now - waitStart < 4000) return;
                    finishSell(mc, now);
                    return;
                }
                GenericContainerScreenHandler h = gcs.getScreenHandler();

                if (waitingWindow) {
                    if (h.syncId != syncAtClick) {
                        waitingWindow = false;
                        nextActionAt = now + 700;
                    } else if (now - waitStart > 4000) {
                        waitingWindow = false;
                    } else {
                        return;
                    }
                }
                if (now < nextActionAt) return;

                // 1) kolejne sloty z listy (kompas, czerwona welna...)
                Integer slot = openSlots.pollFirst();
                if (slot != null) {
                    if (slot >= 0 && slot < h.slots.size()) {
                        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, p);
                        syncAtClick = h.syncId;
                        waitingWindow = true;
                        waitStart = now;
                    }
                    nextActionAt = now + c.clickDelayMs;
                    status = "Klikam w sklepie";
                    return;
                }

                // 2) lewy shift + PRAWY przycisk myszy na ikonie trzciny
                if (shiftTries < 6 && countCane(p) > 0) {
                    int caneSlot = findCaneSlot(h);
                    if (caneSlot < 0) {
                        fail(mc, "Nie widze ikony trzciny w oknie sklepu", now);
                        return;
                    }
                    mc.interactionManager.clickSlot(h.syncId, caneSlot, 1, SlotActionType.QUICK_MOVE, p);
                    shiftTries++;
                    nextActionAt = now + Math.max(c.clickDelayMs, 800);
                    status = "Sprzedaje trzcine";
                    return;
                }

                go(Phase.SELL_CLOSE, now);
            }
            case SELL_CLOSE -> {
                if (now - phaseStart > 800) finishSell(mc, now);
            }
        }
    }

    // ---------- raport ----------

    private static void sendReport(MinecraftClient mc, AutoConfig c) {
        reportPending = false;
        int sold = Math.max(0, caneAtOpen - lastCane + collectedWhileReport);
        collectedWhileReport = 0;
        if (sold <= 0 && !moneyFound) return;

        c.totalSold += sold;
        c.totalMoney += pendingMoney;
        sessionMoney += pendingMoney;
        AutoConfig.save();

        if (!c.chatReport || mc.player == null) return;
        String msg = "[AutoTrzcina] Sprzedano " + money(sold) + " trzciny";
        if (moneyFound) {
            msg += ", zarobiłeś " + money(pendingMoney);
        } else {
            msg += " (nie wykryłem kwoty - włącz Debug w menu, zakładka Sklep)";
        }
        msg += ". Razem: " + money(c.totalSold) + " trzciny, " + money(c.totalMoney) + " zarobione.";
        mc.player.sendMessage(Text.literal(msg), false);
    }

    public static String money(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) {
            return String.format(Locale.ROOT, "%,d", (long) Math.rint(v)).replace(',', ' ');
        }
        return String.format(Locale.ROOT, "%,.2f", v).replace(',', ' ');
    }

    private static double parseAmount(String raw) {
        String s = raw.replace('\u00A0', ' ').replaceAll("\\s+", "").replaceAll("[^0-9.,]", "");
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(","))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return 0;
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        try {
            if (lastDot >= 0 && lastComma >= 0) {
                char dec = lastDot > lastComma ? '.' : ',';
                char th = dec == '.' ? ',' : '.';
                s = s.replace(String.valueOf(th), "").replace(dec, '.');
            } else if (lastDot >= 0 || lastComma >= 0) {
                char sep = lastDot >= 0 ? '.' : ',';
                int count = 0;
                for (int i = 0; i < s.length(); i++) {
                    if (s.charAt(i) == sep) count++;
                }
                int after = s.length() - s.lastIndexOf(sep) - 1;
                if (count > 1 || after == 3) {
                    s = s.replace(String.valueOf(sep), "");
                } else {
                    s = s.replace(sep, '.');
                }
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- pomocnicze ----------

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
        waitingWindow = false;
        collectedWhileReport = 0;
        reportPending = true;
        reportAt = now + 2500;
        moneyWindowUntil = now + 2500;
        nextSellAt = now + AutoConfig.get().collectSec * 1000L;
        status = "Sprzedano (" + cycles + ")";
        go(Phase.STAND, now);
    }

    private static void fail(MinecraftClient mc, String msg, long now) {
        failCount++;
        waitingWindow = false;
        reportPending = false;
        moneyWindowUntil = 0;
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
        nextSellAt = now + 30_000;
        nextPressAt = Math.max(nextPressAt, now + 30_000);
        go(Phase.STAND, now);
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

    private static int findCaneSlot(GenericContainerScreenHandler h) {
        int container = Math.min(h.getRows() * 9, h.slots.size());
        for (int i = 0; i < container; i++) {
            if (h.slots.get(i).getStack().isOf(Items.SUGAR_CANE)) return i;
        }
        return -1;
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
