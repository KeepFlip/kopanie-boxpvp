package pl.kopanie.boxpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
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
 * Mod sam: patrzy na blok (plynny obrot), podchodzi do generatorow w terenie,
 * kopie jeden blok naraz jak lewy przycisk myszy i je wolowine gdy glod <= 4.
 * Kilof trzymaj w aktywnym slocie, wolowine w hotbarze.
 */
public class KopanieBoxPvp implements ClientModInitializer {

    // ====== USTAWIENIA ======
    private static final int EAT_AT_FOOD = 4;      // je gdy glod <= 4 (20 = pelny pasek)
    private static final int EAT_UNTIL_FOOD = 18;  // przestaje jesc gdy glod >= 18
    private static final float MAX_TURN = 25f;     // max obrot kamery na tick (stopnie)
    private static final float ALIGN_DEG = 4f;     // kopie gdy patrzy dokladnie na blok
    private static final int WALK_RANGE_H = 32;    // jak daleko szuka generatorow (poziomo)
    private static final int WALK_RANGE_V = 8;     // jak daleko szuka generatorow (pionowo)
    private static final int STUCK_TICKS = 100;    // po ilu tickach bez ruchu uznaje ze utknal
    // ========================

    private static final int MAX_SLOTS = 10;

    private static BlockPos pos1, pos2;
    private static final Block[] slots = new Block[MAX_SLOTS];
    private static boolean active = false;
    private static BlockPos current = null;
    private static KeyBinding toggleKey;

    private static BlockPos walkGoal = null;
    private static Vec3d lastPos = null;
    private static int stuckTicks = 0;
    private static int tickCounter = 0;
    private static boolean eating = false;
    private static int prevSlot = -1;
    private static boolean warnedNoFood = false;
    private static boolean warnedEmpty = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Kopanie: wlacz/wylacz", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "Kopanie-boxpvp"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("post1").executes(ctx -> setPos(true)));
            dispatcher.register(ClientCommandManager.literal("post2").executes(ctx -> setPos(false)));

            for (int i = 1; i <= MAX_SLOTS; i++) {
                final int slot = i;
                String name = (i == 1) ? "kop" : "kop" + i;
                dispatcher.register(ClientCommandManager.literal(name)
                        .then(ClientCommandManager.argument("blok", IdentifierArgumentType.identifier())
                                .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), builder))
                                .executes(ctx -> setBlock(slot, ctx.getArgument("blok", Identifier.class)))));
            }

            dispatcher.register(ClientCommandManager.literal("kop-start").executes(ctx -> { start(MinecraftClient.getInstance()); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-stop").executes(ctx -> { stop(MinecraftClient.getInstance(), true); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-lista").executes(ctx -> { list(); return 1; }));
            dispatcher.register(ClientCommandManager.literal("kop-czysc").executes(ctx -> {
                for (int i = 0; i < MAX_SLOTS; i++) slots[i] = null;
                stop(MinecraftClient.getInstance(), false);
                msg("Wyczyszczono sloty blokow.", Formatting.YELLOW);
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (active) stop(client, true); else start(client);
            }
            if (active) tick(client);
        });
    }

    // ------------------------------------------------------------ komendy

    private static int setPos(boolean first) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        BlockPos pos = p.getBlockPos();
        if (first) pos1 = pos; else pos2 = pos;
        current = null;
        walkGoal = null;
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
        walkGoal = null;
        stuckTicks = 0;
        lastPos = null;
        eating = false;
        warnedNoFood = false;
        warnedEmpty = false;
        msg("Kopanie WLACZONE (N albo /kop-stop wylacza).", Formatting.GREEN);
    }

    private static void stop(MinecraftClient mc, boolean announce) {
        boolean wasActive = active;
        active = false;
        current = null;
        walkGoal = null;
        if (mc != null) {
            releaseKeys(mc);
            if (eating && mc.player != null && prevSlot >= 0) mc.player.getInventory().selectedSlot = prevSlot;
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        }
        eating = false;
        if (announce && wasActive) msg("Kopanie WYLACZONE.", Formatting.YELLOW);
    }

    private static void list() {
        msg("post1: " + fmt(pos1) + " | post2: " + fmt(pos2), Formatting.AQUA);
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots[i] != null) msg("Slot " + (i + 1) + ": " + Registries.BLOCK.getId(slots[i]), Formatting.AQUA);
        }
        msg("Kopanie: " + (active ? "wlaczone" : "wylaczone"), Formatting.AQUA);
    }

    // ------------------------------------------------------------ glowna petla

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null || !p.isAlive()) {
            stop(mc, false);
            return;
        }
        if (mc.currentScreen != null) { // pauza gdy otwarte menu/ekwipunek
            releaseKeys(mc);
            return;
        }
        tickCounter++;

        if (handleEating(mc, p, im)) return;

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
            if (hit != null) {
                current = hit.getBlockPos();
                selectBestTool(p, w.getBlockState(current));
            }
        }

        if (hit != null) {
            // blok w zasiegu: stoimy, patrzymy na niego i kopiemy
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            stuckTicks = 0;
            warnedEmpty = false;
            float[] err = lookAt(p, hit.getPos());
            if (Math.max(err[0], err[1]) <= ALIGN_DEG) {
                if (im.updateBlockBreakingProgress(current, hit.getSide())) {
                    mc.particleManager.addBlockBreakingParticles(current, hit.getSide());
                    p.swingHand(Hand.MAIN_HAND);
                }
            }
            return;
        }

        walk(mc, p, w, targets);
    }

    // ------------------------------------------------------------ chodzenie

    private static void walk(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w, Set<Block> targets) {
        if (walkGoal == null || tickCounter % 10 == 0 || !isTarget(w, walkGoal, targets)) {
            walkGoal = findWalkGoal(p, w, targets);
        }
        if (walkGoal == null) {
            releaseKeys(mc);
            if (!warnedEmpty) {
                msg("Nie widze blokow do kopania w poblizu (podejdz do terenu).", Formatting.YELLOW);
                warnedEmpty = true;
            }
            return;
        }
        warnedEmpty = false;

        float[] err = lookAt(p, Vec3d.ofCenter(walkGoal));
        mc.options.forwardKey.setPressed(err[0] < 25f);
        mc.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());

        Vec3d now = p.getPos();
        if (lastPos != null && now.squaredDistanceTo(lastPos) < 0.0004) stuckTicks++; else stuckTicks = 0;
        lastPos = now;
        if (stuckTicks > STUCK_TICKS) {
            stop(mc, false);
            msg("Utknalem - ustaw sie blizej generatora i wlacz ponownie (N).", Formatting.RED);
        }
    }

    /** Najblizszy blok z listy w terenie, ktory ma odslonieta chociaz jedna strone. */
    private static BlockPos findWalkGoal(ClientPlayerEntity p, ClientWorld w, Set<Block> targets) {
        int minX = Math.min(pos1.getX(), pos2.getX()), maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY()), maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ()), maxZ = Math.max(pos1.getZ(), pos2.getZ());
        BlockPos pp = p.getBlockPos();
        int x0 = Math.max(minX, pp.getX() - WALK_RANGE_H), x1 = Math.min(maxX, pp.getX() + WALK_RANGE_H);
        int y0 = Math.max(minY, pp.getY() - WALK_RANGE_V), y1 = Math.min(maxY, pp.getY() + WALK_RANGE_V);
        int z0 = Math.max(minZ, pp.getZ() - WALK_RANGE_H), z1 = Math.min(maxZ, pp.getZ() + WALK_RANGE_H);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    m.set(x, y, z);
                    if (!isTarget(w, m, targets)) continue;
                    double d = m.getSquaredDistance(p.getPos());
                    if (d >= bestDist) continue;
                    if (!isExposed(w, m)) continue;
                    bestDist = d;
                    best = m.toImmutable();
                }
            }
        }
        return best;
    }

    private static boolean isExposed(ClientWorld w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!w.getBlockState(n).isOpaqueFullCube(w, n)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ jedzenie

    /** Zwraca true gdy aktualnie je (reszta logiki ma sie wtedy nie wykonywac). */
    private static boolean handleEating(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        int food = p.getHungerManager().getFoodLevel();
        if (food > EAT_AT_FOOD) warnedNoFood = false;

        if (!eating && food <= EAT_AT_FOOD) {
            int s = findFoodSlot(p);
            if (s >= 0) {
                prevSlot = p.getInventory().selectedSlot;
                p.getInventory().selectedSlot = s;
                eating = true;
                current = null;
                im.cancelBlockBreaking();
            } else if (!warnedNoFood) {
                msg("Glod <= " + EAT_AT_FOOD + ", a nie mam wolowiny w hotbarze!", Formatting.RED);
                warnedNoFood = true;
            }
        }

        if (eating) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            ItemStack st = p.getInventory().getStack(p.getInventory().selectedSlot);
            if (food >= EAT_UNTIL_FOOD || !isBeef(st)) {
                eating = false;
                mc.options.useKey.setPressed(false);
                if (prevSlot >= 0) p.getInventory().selectedSlot = prevSlot;
                return false;
            }
            mc.options.useKey.setPressed(true);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ zmiana narzedzi

    /** Wybiera z hotbara najlepsze narzedzie do danego bloku (pomija wolowine i prawie zepsute). */
    private static void selectBestTool(ClientPlayerEntity p, BlockState state) {
        int cur = p.getInventory().selectedSlot;
        int best = cur;
        float bestScore = toolScore(p.getInventory().getStack(cur), state);
        for (int i = 0; i < 9; i++) {
            if (i == cur) continue;
            ItemStack st = p.getInventory().getStack(i);
            if (st.isEmpty() || isBeef(st)) continue;
            float sc = toolScore(st, state);
            if (sc > bestScore + 0.01f) {
                bestScore = sc;
                best = i;
            }
        }
        if (best != cur) p.getInventory().selectedSlot = best;
    }

    private static float toolScore(ItemStack st, BlockState state) {
        if (st.isEmpty()) return 1f;
        if (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 1) return 0f; // nie psuj narzedzia do konca
        float speed = st.getMiningSpeedMultiplier(state);
        if (speed > 1f) {
            int eff = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, st);
            if (eff > 0) speed += eff * eff + 1;
        }
        return speed;
    }

    private static boolean isBeef(ItemStack st) {
        return st.isOf(Items.COOKED_BEEF) || st.isOf(Items.BEEF);
    }

    private static int findFoodSlot(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.COOKED_BEEF)) return i;
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(Items.BEEF)) return i;
        return -1;
    }

    // ------------------------------------------------------------ kopanie / celowanie

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

    private static BlockHitResult visibleHit(ClientPlayerEntity p, ClientWorld w, BlockPos pos, double reach) {
        Vec3d eye = p.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        if (eye.distanceTo(center) > reach) return null;
        BlockHitResult r = w.raycast(new RaycastContext(eye, center,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos)) return r;
        return null;
    }

    /** Plynnie obraca kamere w strone celu. Zwraca {blad_yaw, blad_pitch} po obrocie. */
    private static float[] lookAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float wantPitch = (float) -(MathHelper.atan2(dy, h) * 180.0 / Math.PI);

        float dYaw = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float dPitch = wantPitch - p.getPitch();
        p.setYaw(p.getYaw() + MathHelper.clamp(dYaw, -MAX_TURN, MAX_TURN));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(dPitch, -MAX_TURN, MAX_TURN), -90f, 90f));

        return new float[]{
                Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())),
                Math.abs(wantPitch - p.getPitch())
        };
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

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.useKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

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
