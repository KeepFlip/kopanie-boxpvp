package pl.kopanie.boxpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kopanie-boxpvp (CLIENT) - Fabric 1.20.1
 *
 * /post1, /post2           - rogi terenu (blok, na ktorym stoisz)
 * /kop <blok>              - slot 1
 * /kop2 ... /kop10 <blok>  - kolejne sloty (do 10 blokow)
 * /kop-start, /kop-stop    - wlacz / wylacz (to samo robi klawisz N)
 * /kop-lista, /kop-czysc   - podglad / czyszczenie slotow
 *
 * Kopie tak jak gracz trzymajacy lewy przycisk myszy: tylko bloki w zasiegu reki,
 * tylko widoczne (bez kopania przez sciany), z predkoscia zalezna od narzedzia.
 * Mod NIE porusza postacia ani nie obraca kamery - to Ty chodzisz po terenie.
 */
public class KopanieBoxPvp implements ClientModInitializer {

    private static final int MAX_SLOTS = 10;

    private static BlockPos pos1, pos2;
    private static final Block[] slots = new Block[MAX_SLOTS];
    private static boolean active = false;
    private static BlockPos current = null;
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Kopanie: wlacz/wylacz", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "Kopanie-boxpvp"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("post1").executes(ctx -> setPos(ctx.getSource(), true)));
            dispatcher.register(ClientCommandManager.literal("post2").executes(ctx -> setPos(ctx.getSource(), false)));

            for (int i = 1; i <= MAX_SLOTS; i++) {
                final int slot = i;
                String name = (i == 1) ? "kop" : "kop" + i;
                dispatcher.register(ClientCommandManager.literal(name)
                        .then(ClientCommandManager.argument("blok", IdentifierArgumentType.identifier())
                                .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), builder))
                                .executes(ctx -> setBlock(slot, ctx.getArgument("blok", Identifier.class)))));
            }

            dispatcher.register(ClientCommandManager.literal("kop-start").executes(ctx -> { start(MinecraftClient.getInstance()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-stop").executes(ctx -> { stop(MinecraftClient.getInstance()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-lista").executes(ctx -> { list(); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-czysc").executes(ctx -> {
                for (int i = 0; i < MAX_SLOTS; i++) slots[i] = null;
                stop(MinecraftClient.getInstance());
                msg("Wyczyszczono sloty blokow.", Formatting.YELLOW);
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (active) stop(client); else start(client);
            }
            if (active) tick(client);
        });
    }

    // ------------------------------------------------------------ komendy

    private static int setPos(FabricClientCommandSource src, boolean first) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        BlockPos pos = p.getBlockPos();
        if (first) pos1 = pos; else pos2 = pos;
        current = null;
        msg("Pozycja " + (first ? "1" : "2") + ": " + fmt(pos), Formatting.GREEN);
        return 1;
    }

    private static int setBlock(int slot, Identifier id) {
        if (!Registries.BLOCK.containsId(id)) {
            msg("Nie ma takiego bloku: " + id, Formatting.RED);
            return 0;
        }
        Block b = Registries.BLOCK.get(id);
        if (b.getDefaultState().isAir()) {
            msg("Nie mozna kopac powietrza.", Formatting.RED);
            return 0;
        }
        slots[slot - 1] = b;
        msg("Slot " + slot + " = " + id, Formatting.GREEN);
        return 1;
    }

    private static void start(MinecraftClient mc) {
        if (pos1 == null || pos2 == null) {
            msg("Najpierw ustaw teren: /post1 i /post2", Formatting.RED);
            return;
        }
        if (targets().isEmpty()) {
            msg("Ustaw jakis blok: /kop <blok> (np. /kop hay_block)", Formatting.RED);
            return;
        }
        active = true;
        current = null;
        msg("Kopanie WLACZONE (N albo /kop-stop wylacza).", Formatting.GREEN);
    }

    private static void stop(MinecraftClient mc) {
        active = false;
        current = null;
        if (mc != null && mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        msg("Kopanie WYLACZONE.", Formatting.YELLOW);
    }

    private static void list() {
        msg("post1: " + fmt(pos1) + " | post2: " + fmt(pos2), Formatting.AQUA);
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots[i] != null) msg("Slot " + (i + 1) + ": " + Registries.BLOCK.getId(slots[i]), Formatting.AQUA);
        }
        msg("Kopanie: " + (active ? "wlaczone" : "wylaczone"), Formatting.AQUA);
    }

    // ------------------------------------------------------------ kopanie

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null) {
            active = false;
            current = null;
            return;
        }
        if (mc.currentScreen != null) return; // pauza gdy otwarte menu/ekwipunek

        Set<Block> targets = targets();
        double reach = Math.max(1.0, im.getReachDistance() - 0.5);

        BlockHitResult hit = null;
        if (current != null) {
            if (inRegion(current) && isTarget(w, current, targets)) {
                hit = visibleHit(p, w, current, reach);
            }
            if (hit == null) current = null;
        }

        if (current == null) {
            im.cancelBlockBreaking();
            hit = pickNext(p, w, targets, reach);
            if (hit == null) return; // nic w zasiegu - chodz po terenie
            current = hit.getBlockPos();
        }

        if (im.updateBlockBreakingProgress(current, hit.getSide())) {
            mc.particleManager.addBlockBreakingParticles(current, hit.getSide());
            p.swingHand(Hand.MAIN_HAND);
        }
    }

    private static BlockHitResult pickNext(ClientPlayerEntity p, ClientWorld w, Set<Block> targets, double reach) {
        int minX = Math.min(pos1.getX(), pos2.getX()), maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY()), maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ()), maxZ = Math.max(pos1.getZ(), pos2.getZ());

        BlockPos pp = p.getBlockPos();
        int r = (int) Math.ceil(reach) + 1;
        int x0 = Math.max(minX, pp.getX() - r), x1 = Math.min(maxX, pp.getX() + r);
        int y0 = Math.max(minY, pp.getY() - r), y1 = Math.min(maxY, pp.getY() + r);
        int z0 = Math.max(minZ, pp.getZ() - r), z1 = Math.min(maxZ, pp.getZ() + r);

        Vec3d eye = p.getEyePos();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isTarget(w, pos, targets) && eye.distanceTo(Vec3d.ofCenter(pos)) <= reach) {
                        candidates.add(pos);
                    }
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(
                eye.squaredDistanceTo(Vec3d.ofCenter(a)), eye.squaredDistanceTo(Vec3d.ofCenter(b))));

        for (BlockPos pos : candidates) {
            BlockHitResult hit = visibleHit(p, w, pos, reach);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Zwraca trafienie tylko jesli blok jest realnie widoczny (nie za innym blokiem) i w zasiegu. */
    private static BlockHitResult visibleHit(ClientPlayerEntity p, ClientWorld w, BlockPos pos, double reach) {
        Vec3d eye = p.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        if (eye.distanceTo(center) > reach) return null;
        BlockHitResult r = w.raycast(new RaycastContext(eye, center,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos)) return r;
        return null;
    }

    private static boolean isTarget(ClientWorld w, BlockPos pos, Set<Block> targets) {
        BlockState st = w.getBlockState(pos);
        return !st.isAir() && targets.contains(st.getBlock()) && st.getHardness(w, pos) >= 0;
    }

    private static boolean inRegion(BlockPos p) {
        return p.getX() >= Math.min(pos1.getX(), pos2.getX()) && p.getX() <= Math.max(pos1.getX(), pos2.getX())
                && p.getY() >= Math.min(pos1.getY(), pos2.getY()) && p.getY() <= Math.max(pos1.getY(), pos2.getY())
                && p.getZ() >= Math.min(pos1.getZ(), pos2.getZ()) && p.getZ() <= Math.max(pos1.getZ(), pos2.getZ());
    }

    private static Set<Block> targets() {
        Set<Block> set = new HashSet<>();
        for (Block b : slots) if (b != null) set.add(b);
        return set;
    }

    // ------------------------------------------------------------ pomocnicze

    private static String fmt(BlockPos p) {
        return p == null ? "brak" : p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static void msg(String text, Formatting color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[Kopanie] " + text).formatted(color));
        }
    }
}
