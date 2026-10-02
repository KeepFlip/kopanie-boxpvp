package pl.kopanie.boxpvp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Kopanie-boxpvp
 *
 * /post1, /post2        - zaznacza rogi terenu (blok, na ktorym stoisz)
 * /kop <blok>           - slot 1 blokow do kopania
 * /kop2 ... /kop10 <blok> - kolejne sloty (do 10 roznych blokow)
 * /kop-start            - zaczyna automatyczne kopanie w terenie
 * /kop-stop             - konczy kopanie
 * /kop-lista            - pokazuje ustawienia
 * /kop-czysc            - czysci sloty blokow
 */
public class KopanieBoxPvp implements ModInitializer {

    // ====== USTAWIENIA ======
    /** Ile blokow max rozbijamy na 1 tick (20 ticków = 1 sekunda). */
    private static final int BREAKS_PER_TICK = 8;
    /** Ile blokow max sprawdzamy na tick (zeby nie lagowac przy duzym terenie). */
    private static final int SCANS_PER_TICK = 4096;
    /** Maksymalna objetosc terenu w blokach. */
    private static final long MAX_VOLUME = 10_000_000L;
    private static final int MAX_SLOTS = 10;
    // ========================

    private static class PlayerData {
        BlockPos pos1, pos2;
        RegistryKey<World> world1, world2;
        final Block[] slots = new Block[MAX_SLOTS];
        boolean active = false;
        long cursor = 0;
    }

    private static final Map<UUID, PlayerData> DATA = new HashMap<>();

    private static PlayerData data(ServerPlayerEntity p) {
        return DATA.computeIfAbsent(p.getUuid(), k -> new PlayerData());
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                PlayerData d = DATA.get(player.getUuid());
                if (d != null && d.active) {
                    tickDigging(player, d);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DATA.remove(handler.getPlayer().getUuid()));
    }

    // ---------------------------------------------------------------- komendy

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("post1").executes(ctx -> setPos(ctx, true)));
        dispatcher.register(literal("post2").executes(ctx -> setPos(ctx, false)));

        for (int i = 1; i <= MAX_SLOTS; i++) {
            final int slot = i;
            String name = (i == 1) ? "kop" : "kop" + i;
            dispatcher.register(literal(name)
                    .then(argument("blok", IdentifierArgumentType.identifier())
                            .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), builder))
                            .executes(ctx -> setBlock(ctx, slot))));
        }

        dispatcher.register(literal("kop-start").executes(this::start));
        dispatcher.register(literal("kop-stop").executes(this::stop));
        dispatcher.register(literal("kop-lista").executes(this::list));
        dispatcher.register(literal("kop-czysc").executes(this::clear));
    }

    private int setPos(CommandContext<ServerCommandSource> ctx, boolean first) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        PlayerData d = data(player);
        BlockPos pos = player.getBlockPos();
        RegistryKey<World> w = player.getWorld().getRegistryKey();
        if (first) {
            d.pos1 = pos;
            d.world1 = w;
        } else {
            d.pos2 = pos;
            d.world2 = w;
        }
        d.cursor = 0;
        msg(player, "Ustawiono pozycje " + (first ? "1" : "2") + ": " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), Formatting.GREEN);
        return 1;
    }

    private int setBlock(CommandContext<ServerCommandSource> ctx, int slot) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Identifier id = IdentifierArgumentType.getIdentifier(ctx, "blok");
        if (!Registries.BLOCK.containsId(id)) {
            msg(player, "Nie ma takiego bloku: " + id, Formatting.RED);
            return 0;
        }
        Block block = Registries.BLOCK.get(id);
        if (block.getDefaultState().isAir()) {
            msg(player, "Nie mozna kopac powietrza.", Formatting.RED);
            return 0;
        }
        data(player).slots[slot - 1] = block;
        msg(player, "Slot " + slot + " = " + id, Formatting.GREEN);
        return 1;
    }

    private int start(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        PlayerData d = data(player);

        if (d.pos1 == null || d.pos2 == null) {
            msg(player, "Najpierw ustaw teren: /post1 i /post2", Formatting.RED);
            return 0;
        }
        if (!d.world1.equals(d.world2)) {
            msg(player, "post1 i post2 sa w roznych wymiarach!", Formatting.RED);
            return 0;
        }
        if (activeBlocks(d).isEmpty()) {
            msg(player, "Ustaw jakis blok: /kop <blok> (np. /kop hay_block)", Formatting.RED);
            return 0;
        }
        if (volume(d) > MAX_VOLUME) {
            msg(player, "Teren jest za duzy (max " + MAX_VOLUME + " blokow).", Formatting.RED);
            return 0;
        }
        d.active = true;
        d.cursor = 0;
        msg(player, "Kopanie WLACZONE. Wylaczysz przez /kop-stop", Formatting.GREEN);
        return 1;
    }

    private int stop(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        data(player).active = false;
        msg(player, "Kopanie WYLACZONE.", Formatting.YELLOW);
        return 1;
    }

    private int list(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        PlayerData d = data(player);
        msg(player, "post1: " + fmt(d.pos1) + " | post2: " + fmt(d.pos2), Formatting.AQUA);
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (d.slots[i] != null) {
                msg(player, "Slot " + (i + 1) + ": " + Registries.BLOCK.getId(d.slots[i]), Formatting.AQUA);
            }
        }
        msg(player, "Kopanie: " + (d.active ? "wlaczone" : "wylaczone"), Formatting.AQUA);
        return 1;
    }

    private int clear(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        PlayerData d = data(player);
        for (int i = 0; i < MAX_SLOTS; i++) d.slots[i] = null;
        d.active = false;
        msg(player, "Wyczyszczono wszystkie sloty blokow, kopanie wylaczone.", Formatting.YELLOW);
        return 1;
    }

    // ---------------------------------------------------------------- kopanie

    private static Set<Block> activeBlocks(PlayerData d) {
        Set<Block> set = new HashSet<>();
        for (Block b : d.slots) if (b != null) set.add(b);
        return set;
    }

    private static long volume(PlayerData d) {
        long sx = Math.abs(d.pos1.getX() - d.pos2.getX()) + 1L;
        long sy = Math.abs(d.pos1.getY() - d.pos2.getY()) + 1L;
        long sz = Math.abs(d.pos1.getZ() - d.pos2.getZ()) + 1L;
        return sx * sy * sz;
    }

    private void tickDigging(ServerPlayerEntity player, PlayerData d) {
        if (d.pos1 == null || d.pos2 == null) return;
        if (!(player.getWorld() instanceof ServerWorld world)) return;
        if (!world.getRegistryKey().equals(d.world1)) return; // gracz w innym wymiarze - czekamy

        Set<Block> targets = activeBlocks(d);
        if (targets.isEmpty()) return;

        int minX = Math.min(d.pos1.getX(), d.pos2.getX());
        int minY = Math.min(d.pos1.getY(), d.pos2.getY());
        int minZ = Math.min(d.pos1.getZ(), d.pos2.getZ());
        long sx = Math.abs(d.pos1.getX() - d.pos2.getX()) + 1L;
        long sy = Math.abs(d.pos1.getY() - d.pos2.getY()) + 1L;
        long sz = Math.abs(d.pos1.getZ() - d.pos2.getZ()) + 1L;
        long vol = sx * sy * sz;

        int broken = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();

        for (int scanned = 0; scanned < SCANS_PER_TICK && broken < BREAKS_PER_TICK; scanned++) {
            long idx = d.cursor % vol;
            d.cursor = (d.cursor + 1) % vol;

            int x = minX + (int) (idx % sx);
            int y = minY + (int) ((idx / sx) % sy);
            int z = minZ + (int) (idx / (sx * sy));
            p.set(x, y, z);

            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;

            BlockState state = world.getBlockState(p);
            if (state.isAir() || !targets.contains(state.getBlock())) continue;
            if (state.getHardness(world, p) < 0) continue; // np. bedrock

            BlockPos immutable = p.toImmutable();
            BlockEntity be = world.getBlockEntity(immutable);
            List<ItemStack> drops = Block.getDroppedStacks(state, world, immutable, be, player, player.getMainHandStack());
            world.breakBlock(immutable, false, player);
            for (ItemStack stack : drops) {
                player.getInventory().offerOrDrop(stack); // do ekwipunku, a jak pelny - pod nogi
            }
            broken++;
        }
    }

    // ---------------------------------------------------------------- pomocnicze

    private static String fmt(BlockPos p) {
        return p == null ? "brak" : p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static void msg(ServerPlayerEntity player, String text, Formatting color) {
        player.sendMessage(Text.literal("[Kopanie] " + text).formatted(color), false);
    }
}
