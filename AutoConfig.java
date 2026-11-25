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
    public Pos buttonStand;   // gdzie stoje przy przycisku
    public Pos button;        // blok przycisku
    public Pos collect;       // gdzie zbieram trzcine
    public Pos sell;          // gdzie sprzedaje (opcjonalne)

    // czasy
    public int intervalSec = 600;
    public int collectSec = 60;
    public int clickDelayMs = 500;

    // sklep
    public String sellCommand = "/sklep";
    public String sellSlots = "";
    public boolean depositMode = false;
    public boolean showSlotNumbers = false;

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
        save();
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(INSTANCE));
        } catch (Exception ignored) {
        }
    }
}
