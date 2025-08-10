package com.koolname.bigcaves.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OreEsp extends Module {
    private static final Logger LOGGER = Logger.getLogger(OreEsp.class.getName());

    public enum ScanMode { BigCaves, AroundPlayer, Both }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scan");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<ScanMode> scanMode = sgScan.add(new EnumSetting.Builder<ScanMode>()
        .name("scan-mode")
        .description("Which method to use for finding ores.")
        .defaultValue(ScanMode.Both)
        .build()
    );

    private final Setting<Integer> playerScanRadius = sgScan.add(new IntSetting.Builder()
        .name("player-scan-radius")
        .description("Scan radius around the player in 'Around Player' or 'Both' modes.")
        .defaultValue(32)
        .min(16).sliderMax(128)
        .visible(() -> scanMode.get() != ScanMode.BigCaves)
        .build()
    );

    private final Setting<Integer> despawnDistance = sgScan.add(new IntSetting.Builder()
        .name("despawn-distance")
        .description("Distance at which ore ESP will disappear.")
        .defaultValue(100)
        .min(32).sliderMax(256)
        .build()
    );

    private final Setting<Integer> scanDelay = sgScan.add(new IntSetting.Builder()
        .name("scan-delay-ticks")
        .description("Delay in ticks between scans.")
        .defaultValue(40)
        .min(20).sliderMax(400)
        .build()
    );

    private final Setting<Boolean> sendChatMessage = sgGeneral.add(new BoolSetting.Builder()
        .name("send-chat-message")
        .description("Send a message in chat when a new vein is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> ores = sgGeneral.add(new BlockListSetting.Builder()
        .name("ores")
        .description("Ores to search for.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes will be rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("hidden-color")
        .description("ESP color for ores hidden behind blocks.")
        .defaultValue(new SettingColor(75, 255, 75, 100))
        .build()
    );

    private final Setting<SettingColor> visibleColor = sgRender.add(new ColorSetting.Builder()
        .name("visible-color")
        .description("ESP color for ores in direct line of sight.")
        .defaultValue(new SettingColor(255, 255, 0, 225))
        .build()
    );

    private BigCavesFinder bigCavesFinder;
    private Thread scanThread;

    private final Set<BlockPos> exposedOres = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<BlockPos> scannedCaveAreas = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Map<BlockPos, Long> visibleCacheTimestamps = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> visibleCache = new ConcurrentHashMap<>();
    private final long visibilityCacheMillis = 2500; // Cache raycast results for 2.5s

    public OreEsp() {
        super(com.koolname.bigcaves.BigCavesAddon.CATEGORY, "ore-esp", "Finds and highlights exposed ores (optimized).");

        if (ores.get().isEmpty()) {
            ores.get().addAll(List.of(
                Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
                Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE
            ));
        }
    }

    @Override
    public void onActivate() {
        bigCavesFinder = Modules.get().get(BigCavesFinder.class);
        exposedOres.clear();
        scannedCaveAreas.clear();
        visibleCache.clear();
        visibleCacheTimestamps.clear();
        startScanThread();
    }

    @Override
    public void onDeactivate() {
        exposedOres.clear();
        scannedCaveAreas.clear();
        visibleCache.clear();
        visibleCacheTimestamps.clear();
        if (scanThread != null && scanThread.isAlive()) {
            scanThread.interrupt();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (espColor.get().a == 0 && visibleColor.get().a == 0) return;

        for (BlockPos pos : exposedOres) {
            SettingColor colorToRender = isOreVisibleCached(pos) ? visibleColor.get() : espColor.get();
            if (colorToRender.a > 0) {
                event.renderer.box(pos, colorToRender, colorToRender, shapeMode.get(), 0);
            }
        }
    }

    private boolean isOreVisibleCached(BlockPos orePos) {
        if (mc.world == null || mc.player == null) return false;

        long now = System.currentTimeMillis();
        Long stamp = visibleCacheTimestamps.get(orePos);
        if (stamp != null && (now - stamp) < visibilityCacheMillis) {
            Boolean cached = visibleCache.get(orePos);
            if (cached != null) return cached;
        }

        boolean visible = isOreVisible(orePos);
        visibleCache.put(orePos, visible);
        visibleCacheTimestamps.put(orePos, now);
        return visible;
    }

    private boolean isOreVisible(BlockPos orePos) {
        if (mc.world == null || mc.player == null) return false;

        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        RaycastContext context = new RaycastContext(
            cameraPos,
            Vec3d.ofCenter(orePos),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );

        BlockHitResult hitResult = mc.world.raycast(context);
        return hitResult.getType() == HitResult.Type.MISS || hitResult.getBlockPos().equals(orePos);
    }

    private void startScanThread() {
        scanThread = new Thread(() -> {
            while (isActive() && mc.world != null && mc.player != null) {
                try {
                    cleanupOres();

                    ScanMode mode = scanMode.get();
                    if (mode == ScanMode.BigCaves || mode == ScanMode.Both) scanBigCaves();
                    if (mode == ScanMode.AroundPlayer || mode == ScanMode.Both) scanAroundPlayer();

                    // Intentional sleep between scans (tick-based delay)
                    Thread.sleep(Math.max(50L, scanDelay.get() * 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Unexpected error in ore scan thread", e);
                }
            }
        }, "OreEsp-ScanThread");

        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void cleanupOres() {
        if (mc.world == null || mc.player == null) return;
        long despawnDistSq = (long) despawnDistance.get() * despawnDistance.get();

        exposedOres.removeIf(pos ->
            !ores.get().contains(mc.world.getBlockState(pos).getBlock()) ||
                mc.player.getBlockPos().getSquaredDistance(pos) > despawnDistSq
        );
    }

    private void scanAroundPlayer() {
        if (mc.world == null || mc.player == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = playerScanRadius.get();
        int minChunkX = (playerPos.getX() - radius) >> 4;
        int maxChunkX = (playerPos.getX() + radius) >> 4;
        int minChunkZ = (playerPos.getZ() - radius) >> 4;
        int maxChunkZ = (playerPos.getZ() + radius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Chunk chunk = mc.world.getChunk(cx, cz);
                if (chunk instanceof EmptyChunk) continue;

                int chunkStartX = cx << 4;
                int chunkStartZ = cz << 4;
                int yLow = Math.max(mc.world.getDimension().minY(), playerPos.getY() - radius);
                int yHigh = Math.min(mc.world.getDimension().height() - 1, playerPos.getY() + radius);

                for (int x = chunkStartX; x < chunkStartX + 16; x++) {
                    for (int z = chunkStartZ; z < chunkStartZ + 16; z++) {
                        int dx = x - playerPos.getX();
                        int dz = z - playerPos.getZ();
                        if (dx * dx + dz * dz > radius * radius) continue;

                        for (int y = yLow; y <= yHigh; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            Block block = mc.world.getBlockState(pos).getBlock();
                            if (ores.get().contains(block) && isExposed(pos) && exposedOres.add(pos)) {
                                findOreVein(pos.toImmutable(), block);
                            }
                        }
                    }
                }
            }
        }
    }

    private void scanBigCaves() {
        if (bigCavesFinder == null || !bigCavesFinder.isActive() || bigCavesFinder.foundCaves.isEmpty()) return;

        for (BlockPos caveAreaPos : bigCavesFinder.foundCaves) {
            if (Thread.interrupted()) return;
            if (scannedCaveAreas.add(caveAreaPos)) {
                scanAreaForExposedOres(caveAreaPos);
            }
        }
    }

    private void scanAreaForExposedOres(BlockPos areaStartPos) {
        if (mc.world == null) return;
        int volumeSize = bigCavesFinder.checkVolumeSize.get();

        for (int x = 0; x < volumeSize; x++) {
            for (int y = 0; y < volumeSize; y++) {
                for (int z = 0; z < volumeSize; z++) {
                    BlockPos currentPos = areaStartPos.add(x, y, z);
                    if (mc.world.getChunk(currentPos.getX() >> 4, currentPos.getZ() >> 4) instanceof EmptyChunk) continue;

                    Block block = mc.world.getBlockState(currentPos).getBlock();
                    if (ores.get().contains(block) && isExposed(currentPos) && exposedOres.add(currentPos)) {
                        findOreVein(currentPos.toImmutable(), block);
                    }
                }
            }
        }
    }

    private void findOreVein(BlockPos seedPos, Block oreType) {
        if (mc.world == null) return;

        if (sendChatMessage.get()) {
            info("Found ore vein: %s.", oreType.getName().getString());
        }

        List<BlockPos> queue = new ArrayList<>();
        queue.add(seedPos);
        int head = 0;

        while (head < queue.size()) {
            BlockPos current = queue.get(head++);
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;

                        BlockPos neighbor = current.add(x, y, z);
                        if (exposedOres.contains(neighbor)) continue;

                        if (mc.world.getBlockState(neighbor).getBlock() == oreType) {
                            exposedOres.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
    }

    private boolean isExposed(BlockPos pos) {
        if (mc.world == null) return false;
        for (Direction direction : Direction.values()) {
            if (isAir(pos.offset(direction))) return true;
        }
        return false;
    }

    private boolean isAir(BlockPos pos) {
        if (mc.world == null) return false;
        Block block = mc.world.getBlockState(pos).getBlock();
        return block == Blocks.AIR || block == Blocks.CAVE_AIR;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(exposedOres.size());
    }
}
