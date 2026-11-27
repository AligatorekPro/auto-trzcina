package pl.autotrzcina.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import pl.autotrzcina.AutoConfig;
import pl.autotrzcina.AutoConfig.Pos;
import pl.autotrzcina.FarmBot;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class AutoScreen extends Screen {
    private static final String[] TABS = {"Start", "Pozycje", "Czasy", "Sklep", "Home", "Bot", "Wygląd"};
    private static final int PW = 380, PH = 236;
    private static int tab = 0;

    private final Screen parent;
    private final AutoConfig c = AutoConfig.get();
    private int px, py;
    private String info = "";
    private long infoUntil = 0;
    private boolean resetArmed = false;

    public AutoScreen(Screen parent) {
        super(Text.literal("AutoTrzcina"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        px = (width - PW) / 2;
        py = (height - PH) / 2;
        resetArmed = false;

        int tw = (PW - 20) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(TABS[i]), b -> {
                tab = idx;
                clearAndInit();
            }).dimensions(px + 10 + i * tw, py + 26, tw - 2, 18).build());
        }

        switch (tab) {
            case 0 -> tabStart();
            case 1 -> tabPositions();
            case 2 -> tabTimes();
            case 3 -> tabShop();
            case 4 -> tabHome();
            case 5 -> tabBot();
            default -> tabLook();
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Gotowe"), b -> close())
                .dimensions(px + PW / 2 - 50, py + PH - 26, 100, 20).build());
    }

    private int colW() {
        return (PW - 24) / 2;
    }

    private int colX(int col) {
        return px + 10 + col * (colW() + 4);
    }

    private int rowY(int row) {
        return py + 52 + row * 24;
    }

    // ---------- zakladki ----------

    private void tabStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean running = FarmBot.isRunning();
        int col = running ? 0xFF5555 : c.accent;
        addDrawableChild(ButtonWidget.builder(
                Text.literal(running ? "STOP" : "START").styled(s -> s.withColor(col)), b -> {
                    if (FarmBot.isRunning()) FarmBot.stop(mc, "Zatrzymano");
                    else FarmBot.start(mc);
                    clearAndInit();
                }).dimensions(px + 10, rowY(0), PW - 20, 22).build());

        toggle(colX(0), rowY(1) + 2, colW(), "Pasek statusu", () -> c.hud, v -> c.hud = v);
        toggle(colX(1), rowY(1) + 2, colW(), "Raport na czacie", () -> c.chatReport, v -> c.chatReport = v);

        addDrawableChild(ButtonWidget.builder(Text.literal("Wyzeruj statystyki"), b -> {
            if (!resetArmed) {
                resetArmed = true;
                b.setMessage(Text.literal("Na pewno? Kliknij jeszcze raz").styled(s -> s.withColor(0xFF5555)));
            } else {
                resetArmed = false;
                FarmBot.resetStats();
                b.setMessage(Text.literal("Wyzerowano!"));
                showInfo("Statystyki wyzerowane");
            }
        }).dimensions(px + 10, rowY(2) + 4, PW - 20, 20).build());
    }

    private void tabPositions() {
        posRow(0, "Stoję przy przycisku", () -> c.buttonStand, v -> c.buttonStand = v, false);
        posRow(1, "Przycisk (patrz na niego)", () -> c.button, v -> c.button = v, true);
        posRow(2, "Miejsce zbierania", () -> c.collect, v -> c.collect = v, false);
        posRow(3, "Miejsce sprzedaży (nieużywane)", () -> c.sell, v -> c.sell = v, false);
    }

    private void tabTimes() {
        addDrawableChild(new IntSlider(px + 10, rowY(0), PW - 20, "Klikanie przycisku co", 30, 3600, 30,
                c.intervalSec, AutoScreen::secs, v -> c.intervalSec = v));
        addDrawableChild(new IntSlider(px + 10, rowY(1), PW - 20, "Min. przerwa między sprzedażami", 5, 300, 5,
                c.collectSec, AutoScreen::secs, v -> c.collectSec = v));
        addDrawableChild(new IntSlider(px + 10, rowY(2), PW - 20, "Opóźnienie kliknięć w sklepie", 200, 2000, 100,
                c.clickDelayMs, v -> v + " ms", v -> c.clickDelayMs = v));
        addDrawableChild(new IntSlider(px + 10, rowY(3), PW - 20, "Sprzedawaj od", 0, 1000, 10,
                c.minSell, v -> v == 0 ? "1 szt." : v + " szt.", v -> c.minSell = v));
        addDrawableChild(new IntSlider(px + 10, rowY(4), PW - 20, "Ile godzin AFK (do szacunku)", 1, 24, 1,
                c.afkHours, v -> v + " h", v -> c.afkHours = v));
    }

    private void tabShop() {
        TextFieldWidget cmd = new TextFieldWidget(textRenderer, px + 130, rowY(0), PW - 140, 20, Text.literal("Komenda"));
        cmd.setMaxLength(60);
        cmd.setText(c.sellCommand);
        cmd.setChangedListener(s -> c.sellCommand = s);
        addDrawableChild(cmd);

        TextFieldWidget slots = new TextFieldWidget(textRenderer, px + 130, rowY(1), PW - 140, 20, Text.literal("Sloty"));
        slots.setMaxLength(60);
        slots.setPlaceholder(Text.literal("np. 13, 20 (kompas, wełna)"));
        slots.setText(c.sellSlots);
        slots.setChangedListener(s -> c.sellSlots = s);
        addDrawableChild(slots);

        toggle(px + 10, rowY(2), PW - 20, "Debug: wiadomości sklepu na czacie", () -> c.debugShop, v -> c.debugShop = v);
        toggle(px + 10, rowY(3), PW - 20, "Pokaż numery slotów w oknach", () -> c.showSlotNumbers, v -> c.showSlotNumbers = v);

        TextFieldWidget rx = new TextFieldWidget(textRenderer, px + 130, rowY(4), PW - 140, 20, Text.literal("Regex"));
        rx.setMaxLength(300);
        rx.setText(c.moneyRegex);
        rx.setChangedListener(s -> c.moneyRegex = s);
        addDrawableChild(rx);
    }

    private void tabHome() {
        toggle(px + 10, rowY(0), PW - 20, "Po śmierci: /home i wznowienie farmy", () -> c.autoHome, v -> c.autoHome = v);
        addDrawableChild(new IntSlider(px + 10, rowY(1), PW - 20, "Czekaj po śmierci", 5, 120, 5,
                c.homeDelaySec, AutoScreen::secs, v -> c.homeDelaySec = v));

        TextFieldWidget cmd = new TextFieldWidget(textRenderer, px + 130, rowY(2), PW - 140, 20, Text.literal("Home"));
        cmd.setMaxLength(60);
        cmd.setText(c.homeCommand);
        cmd.setChangedListener(s -> c.homeCommand = s);
        addDrawableChild(cmd);

        TextFieldWidget slots = new TextFieldWidget(textRenderer, px + 130, rowY(3), PW - 140, 20, Text.literal("Sloty"));
        slots.setMaxLength(60);
        slots.setPlaceholder(Text.literal("np. 11 (slot domu)"));
        slots.setText(c.homeSlots);
        slots.setChangedListener(s -> c.homeSlots = s);
        addDrawableChild(slots);
    }

    private void tabBot() {
        toggle(colX(0), rowY(0), colW(), "Auto-jedzenie", () -> c.autoEat, v -> c.autoEat = v);
        addDrawableChild(new IntSlider(colX(1), rowY(0), colW(), "Jedz poniżej", 1, 19, 1,
                c.eatBelow, v -> v + "/20", v -> c.eatBelow = v));

        toggle(colX(0), rowY(1), colW(), "Auto-skok", () -> c.autoJump, v -> c.autoJump = v);
        addDrawableChild(new IntSlider(colX(1), rowY(1), colW(), "Skok co", 1, 30, 1,
                c.jumpMinutes, v -> v + " min", v -> c.jumpMinutes = v));

        toggle(colX(0), rowY(2), colW(), "Losowe obroty głowy", () -> c.headTurn, v -> c.headTurn = v);
    }

    private void tabLook() {
        addDrawableChild(new IntSlider(px + 10, rowY(0), PW - 20, "Czerwony", 0, 255, 1,
                (c.accent >> 16) & 255, String::valueOf, v -> c.accent = (c.accent & 0x00FFFF) | (v << 16)));
        addDrawableChild(new IntSlider(px + 10, rowY(1), PW - 20, "Zielony", 0, 255, 1,
                (c.accent >> 8) & 255, String::valueOf, v -> c.accent = (c.accent & 0xFF00FF) | (v << 8)));
        addDrawableChild(new IntSlider(px + 10, rowY(2), PW - 20, "Niebieski", 0, 255, 1,
                c.accent & 255, String::valueOf, v -> c.accent = (c.accent & 0xFFFF00) | v));

        int[] presets = {0x3DDC84, 0xFF5FA2, 0x00E5FF, 0xFF9F1C, 0xA06CFF};
        int bw = (PW - 20 - 4 * 4) / 5;
        for (int i = 0; i < presets.length; i++) {
            final int col = presets[i];
            addDrawableChild(ButtonWidget.builder(Text.literal("●").styled(s -> s.withColor(col)), b -> {
                c.accent = col;
                clearAndInit();
            }).dimensions(px + 10 + i * (bw + 4), rowY(3), bw, 20).build());
        }
    }

    // ---------- pozycje ----------

    private void posRow(int row, String label, Supplier<Pos> get, Consumer<Pos> set, boolean isButton) {
        Pos cur = get.get();
        String txt = label + ": " + (cur == null ? "nie ustawiono" : cur.str());
        addDrawableChild(ButtonWidget.builder(Text.literal(txt), b -> {
            Pos np = isButton ? lookedAtButton() : playerPos();
            if (np == null) return;
            set.accept(np);
            showInfo("Zapisano: " + np.str());
            clearAndInit();
        }).dimensions(px + 10, rowY(row), PW - 20 - 24, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("X").styled(s -> s.withColor(0xFF5555)), b -> {
            set.accept(null);
            clearAndInit();
        }).dimensions(px + PW - 10 - 20, rowY(row), 20, 20).build());
    }

    private Pos playerPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            showInfo("Wejdź na serwer, żeby zapisać pozycję");
            return null;
        }
        return new Pos(mc.player.getBlockPos());
    }

    private Pos lookedAtButton() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            showInfo("Wejdź na serwer, żeby zapisać przycisk");
            return null;
        }
        HitResult hit = mc.player.raycast(6.0, 1.0F, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            if (mc.world.getBlockState(bp).isIn(BlockTags.BUTTONS)) return new Pos(bp);
        }
        showInfo("Nie patrzysz na przycisk (max 6 bloków)");
        return null;
    }

    private void showInfo(String s) {
        info = s;
        infoUntil = System.currentTimeMillis() + 3500;
    }

    // ---------- helpery ----------

    private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        ButtonWidget b = ButtonWidget.builder(toggleText(label, get.getAsBoolean()), btn -> {
            boolean v = !get.getAsBoolean();
            set.accept(v);
            btn.setMessage(toggleText(label, v));
        }).dimensions(x, y, w, 20).build();
        addDrawableChild(b);
    }

    private Text toggleText(String label, boolean on) {
        int col = on ? c.accent : 0xFF5555;
        return Text.literal(label + ": ").append(Text.literal(on ? "ON" : "OFF").styled(s -> s.withColor(col)));
    }

    private static String secs(int s) {
        if (s < 60) return s + " s";
        int m = s / 60, r = s % 60;
        return r == 0 ? m + " min" : m + " min " + r + " s";
    }

    private static class IntSlider extends SliderWidget {
        private final int min, max, step;
        private final String label;
        private final IntFunction<String> fmt;
        private final IntConsumer set;
        private boolean ready = false;

        IntSlider(int x, int y, int w, String label, int min, int max, int step, int cur,
                  IntFunction<String> fmt, IntConsumer set) {
            super(x, y, w, 20, Text.empty(), (double) (cur - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.fmt = fmt;
            this.set = set;
            this.ready = true;
            updateMessage();
        }

        private int current() {
            int v = min + (int) Math.round(value * (max - min) / step) * step;
            return Math.max(min, Math.min(max, v));
        }

        @Override
        protected void updateMessage() {
            if (!ready) return;
            setMessage(Text.literal(label + ": " + fmt.apply(current())));
        }

        @Override
        protected void applyValue() {
            set.accept(current());
        }
    }

    // ---------- rysowanie ----------

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int acc = 0xFF000000 | c.accent;
        ctx.fill(0, 0, width, height, 0xB0000000);
        ctx.fill(px, py, px + PW, py + PH, 0xF0121218);
        ctx.fill(px, py, px + PW, py + 2, acc);
        ctx.fill(px, py + PH - 2, px + PW, py + PH, acc);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int acc = 0xFF000000 | c.accent;

        ctx.drawTextWithShadow(textRenderer, Text.literal("AUTOTRZCINA"), px + 10, py + 9, acc);

        boolean run = FarmBot.isRunning();
        String st = (run ? "● " : "○ ") + FarmBot.status;
        String shown = textRenderer.trimToWidth(st, PW - 130);
        ctx.drawTextWithShadow(textRenderer, Text.literal(shown),
                px + PW - 10 - textRenderer.getWidth(shown), py + 9, run ? 0xFF55FF55 : 0xFFAAAAAA);

        int tw = (PW - 20) / TABS.length;
        ctx.fill(px + 10 + tab * tw, py + 45, px + 10 + tab * tw + tw - 2, py + 47, acc);

        int gray = 0xFFBBBBBB;
        int dim = 0xFF888888;
        if (tab == 0) {
            int y = rowY(2) + 34;
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("Zebrano łącznie: " + FarmBot.money(c.totalCollected) + " trzciny   |   Śmierci: " + c.deaths),
                    px + 12, y, gray);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("Sprzedano: " + FarmBot.money(c.totalSold) + "  |  Zarobiono: " + FarmBot.money(c.totalMoney)),
                    px + 12, y + 11, gray);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("Zarobek na godzinę: " + (run ? FarmBot.money(FarmBot.perHour()) : "-")),
                    px + 12, y + 22, gray);
            double est = FarmBot.estimateAfk();
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("Szac. zarobek za " + c.afkHours + " h AFK: "
                            + (est < 0 ? "po 1. cyklu (klik + zebrana trzcina)" : FarmBot.money(est))),
                    px + 12, y + 33, est < 0 ? dim : acc);
            String next = run ? "Następne kliknięcie za: " + FarmBot.fmt(FarmBot.secondsToNext()) : "Bot wyłączony";
            ctx.drawTextWithShadow(textRenderer, Text.literal(next), px + 12, y + 44, gray);
            boolean bar = FarmBot.baritoneLoaded();
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("Baritone: " + (bar ? "wykryty" : "BRAK - zainstaluj") + "   (O = menu, G = start/stop)"),
                    px + 12, y + 55, bar ? 0xFF55FF55 : 0xFFFF5555);
        } else if (tab == 1) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Stań w miejscu, otwórz menu (O) i kliknij."),
                    px + 12, rowY(4) + 2, dim);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Przy przycisku: patrz na niego i kliknij."),
                    px + 12, rowY(4) + 14, dim);
        } else if (tab == 3) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Komenda sklepu"), px + 12, rowY(0) + 6, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Sloty do kliknięcia"), px + 12, rowY(1) + 6, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Wzór kwoty (regex)"), px + 12, rowY(4) + 6, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Kolejność slotów: kompas, potem czerwona wełna."),
                    px + 12, rowY(5) + 4, dim);
        } else if (tab == 4) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Komenda home"), px + 12, rowY(2) + 6, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Sloty do kliknięcia"), px + 12, rowY(3) + 6, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Numery slotów: włącz je w zakładce Sklep."),
                    px + 12, rowY(4) + 2, dim);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Puste sloty = /home teleportuje od razu."),
                    px + 12, rowY(4) + 14, dim);
        } else if (tab == 5) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Jedzenie musi być w ekwipunku."),
                    px + 12, rowY(3) + 4, dim);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Pomijam złote jabłka, zgniłe mięso, surowego kurczaka itp."),
                    px + 12, rowY(3) + 16, dim);
        }

        if (!info.isEmpty() && System.currentTimeMillis() < infoUntil) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(info), px + PW / 2, py + PH - 40, acc);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        AutoConfig.save();
    }
}
