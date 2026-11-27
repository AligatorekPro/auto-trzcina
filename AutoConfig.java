package pl.autotrzcina;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;

public class AutoConfig {
    public static class Pos {
        public int x, y, z;

        public Pos() {
        }

        public Pos(BlockPos p) {
            x = p.getX();
            y = p.getY();
            z = p.getZ();
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        public String str() {
            return x + " " + y + " " + z;
        }
    }

    // pozycje
    public Pos buttonStand;
    public Pos button;
    public Pos collect;
    public Pos sell;

    // czasy
    public int intervalSec = 600;
    public int collectSec = 60;      // min. przerwa miedzy sprzedazami
    public int clickDelayMs = 500;

    // sklep
    public String sellCommand = "/sklep";
    public String sellSlots = "";
    public boolean depositMode = false; // juz nieuzywane
    public boolean showSlotNumbers = false;

    // statystyki i raport
    public boolean chatReport = true;
    public boolean debugShop = false;
    public String moneyRegex = "(?i)sprzeda.*?\\bza\\s*\\$?\\s*([0-9][0-9.,\\s]*)|sprzeda.*?([0-9][0-9.,\\s]*)\\s*(?:\\$|zł)";
    public long totalCollected = 0;
    public long totalSold = 0;
    public double totalMoney = 0;

    // auto-skok
    public boolean autoJump = false;
    public int jumpMinutes = 5;

    // inne
    public boolean hud = true;
    public boolean skipBaritoneCheck = false;
    public int accent = 0x3DDC84;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AutoConfig INSTANCE = new AutoConfig();

    public static AutoConfig get() {
        return INSTANCE;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autotrzcina.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                AutoConfig c = GSON.fromJson(Files.readString(file()), AutoConfig.class);
                if (c != null) INSTANCE = c;
            }
        } catch (Exception e) {
            INSTANCE = new AutoConfig();
        }
        if (INSTANCE.sellCommand == null) INSTANCE.sellCommand = "/sklep";
        if (INSTANCE.sellSlots == null) INSTANCE.sellSlots = "";
        if (INSTANCE.moneyRegex == null) INSTANCE.moneyRegex = new AutoConfig().moneyRegex;
        save();
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(INSTANCE));
        } catch (Exception ignored) {
        }
    }
}
