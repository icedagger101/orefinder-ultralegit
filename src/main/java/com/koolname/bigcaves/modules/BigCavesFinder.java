package com.koolname.bigcaves.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BigCavesFinder extends Module {
    private static final Logger LOGGER = Logger.getLogger(BigCavesFinder.class.getName());

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scan Settings");
    private final SettingGroup sgRender = settings.createGroup("Render Settings");

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("scan-delay-ticks")
        .description("Delay in ticks between scans.")
        .defaultValue(200)
        .min(20).sliderMax(600)
        .build()
    );

    private final Setting<Integer> maxScanHeight = sgScan.add(new IntSetting.Builder()
        .name("max-scan-height")
        .description("Will not scan caves above this Y level.")
        .defaultValue(128)
        .min(-64).sliderMax(320)
        .build()
    );

    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder()
        .name("horizontal-radius")
        .description("Horizontal scan radius around the player.")
        .defaultValue(80)
        .min(16).sliderMax(256)
        .build()
    );

    private final Setting<Integer> verticalRadius = sgScan.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("Vertical scan radius around the player.")
        .defaultValue(40)
        .min(16).sliderMax(128)
        .build()
    );

    private final Setting<Integer> caveThreshold = sgScan.add(new IntSetting.Builder()
        .name("min-air-blocks")
        .description("Minimum number of air blocks in a volume to consider it as a cave candidate.")
        .defaultValue(2000)
        .min(500).sliderMax(4000)
        .build()
    );

    private final Setting<Integer> minCaveSize = sgScan.add(new IntSetting.Builder()
        .name("min-connected-air")
        .description("Minimum number of connected air blocks to be considered a large cave.")
        .defaultValue(5000)
        .min(1000).sliderMax(20000)
        .build()
    );

    public final Setting<Integer> checkVolumeSize = sgScan.add(new IntSetting.Builder()
        .name("check-volume-size")
        .description("Size of cubic volume to check for air blocks (e.g., 16x16x16).")
        .defaultValue(16)
        .min(8).sliderMax(32)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes will be rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("ESP color.")
        .defaultValue(new SettingColor(255, 75, 75, 90))
        .build()
    );

    public final Set<BlockPos> foundCaves = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Thread scanThread;
    private final Map<Long, Long> volumeCache = new ConcurrentHashMap<>();
    private final int cacheTtlMillis = 5000; // Cache volume origins for 5 seconds

    // Flood fill node limits to avoid heavy processing
    private final int maxFloodNodesSingleplayer = 200_000;
    private final int maxFloodNodesMultiplayer = 20_000;

    public BigCavesFinder() {
        super(com.koolname.bigcaves.BigCavesAddon.CATEGORY, "big-caves-finder", "Finds and highlights large cave systems (optimized).");
    }

    @Override
    public void onActivate() {
        foundCaves.clear();
        volumeCache.clear();
        startScanThread();
    }

    @Override
    public void onDeactivate() {
        foundCaves.clear();
        volumeCache.clear();
        if (scanThread != null && scanThread.isAlive()) {
            scanThread.interrupt();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (espColor.get().a == 0) return;
        int size = checkVolumeSize.get();
        for (BlockPos pos : foundCaves) {
            double x2 = pos.getX() + size;
            double y2 = pos.getY() + size;
            double z2 = pos.getZ() + size;
            event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), x2, y2, z2, espColor.get(), espColor.get(), shapeMode.get(), 0);
        }
    }

    private void startScanThread() {
        scanThread = new Thread(() -> {
            while (isActive() && mc.world != null && mc.player != null) {
                try {
                    foundCaves.clear();

                    boolean singleplayer = mc.isIntegratedServerRunning();
                    int floodLimit = singleplayer ? maxFloodNodesSingleplayer : maxFloodNodesMultiplayer;

                    BlockPos playerPos = mc.player.getBlockPos();
                    int step = checkVolumeSize.get();
                    int worldBottomY = mc.world.getDimension().minY();
                    int hRadius = Math.min(scanRadius.get(), 128);
                    int vRadius = Math.min(verticalRadius.get(), 64);

                    int startX = playerPos.getX() - hRadius;
                    int endX = playerPos.getX() + hRadius;
                    int startY = Math.max(worldBottomY, playerPos.getY() - vRadius);
                    int endY = Math.min(maxScanHeight.get(), playerPos.getY() + vRadius);
                    int startZ = playerPos.getZ() - hRadius;
                    int endZ = playerPos.getZ() + hRadius;

                    for (int x = startX; x <= endX; x += step) {
                        if (Thread.interrupted()) return;
                        for (int z = startZ; z <= endZ; z += step) {
                            if (Thread.interrupted()) return;
                            if (!isColumnChunksReady(x, z, step)) continue;

                            for (int y = startY; y <= endY; y += step) {
                                if (Thread.interrupted()) return;

                                BlockPos origin = new BlockPos(x, y, z);
                                long key = volumeKey(origin);
                                long now = System.currentTimeMillis();

                                Long lastSeen = volumeCache.get(key);
                                if (lastSeen != null && (now - lastSeen) < cacheTtlMillis) continue;

                                if (!quickAirEstimate(origin, step)) {
                                    volumeCache.put(key, now);
                                    continue;
                                }

                                int airCount = countAirWithEarlyExit(origin, step, caveThreshold.get());
                                volumeCache.put(key, now);
                                if (airCount < caveThreshold.get()) continue;

                                BlockPos center = origin.add(step / 2, step / 2, step / 2);
                                if (!mc.world.getBlockState(center).isAir() || mc.world.isSkyVisible(center)) continue;

                                int connected = countConnectedAirBounded(center, mc.player.getBlockPos(), floodLimit);
                                if (connected >= minCaveSize.get()) {
                                    foundCaves.add(origin);
                                }
                            }
                        }
                    }

                    // Intentional sleep between scans (tick-based delay)
                    Thread.sleep(Math.max(50L, scanDelay.get() * 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Unexpected error in cave scan thread", e);
                }
            }
        }, "BigCavesFinder-ScanThread");

        scanThread.setDaemon(true);
        scanThread.start();
    }

    private long volumeKey(BlockPos pos) {
        long x = pos.getX();
        long y = pos.getY();
        long z = pos.getZ();
        return (x & 0x1FFFFFL) | ((y & 0xFFFFFL) << 21) | ((z & 0x1FFFFF) << 41);
    }

    private boolean isColumnChunksReady(int blockX, int blockZ, int size) {
        if (mc.world == null) return false;
        int startChunkX = blockX >> 4;
        int endChunkX = (blockX + size) >> 4;
        int startChunkZ = blockZ >> 4;
        int endChunkZ = (blockZ + size) >> 4;

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
                if (mc.world.getChunk(cx, cz) instanceof EmptyChunk) return false;
            }
        }
        return true;
    }

    private boolean quickAirEstimate(BlockPos origin, int size) {
        if (mc.world == null) return false;
        int samples = 12;
        int air = 0;
        Random rnd = new Random(volumeKey(origin));
        for (int i = 0; i < samples; i++) {
            if (mc.world.getBlockState(origin.add(rnd.nextInt(size), rnd.nextInt(size), rnd.nextInt(size))).isAir()) {
                air++;
            }
        }
        return air * 5 >= samples;
    }

    private int countAirWithEarlyExit(BlockPos origin, int size, int threshold) {
        if (mc.world == null) return 0;
        int air = 0;
        int remaining = size * size * size;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    if (mc.world.getBlockState(origin.add(x, y, z)).isAir()) air++;
                    remaining--;
                    if (air + remaining < threshold) return air;
                }
            }
        }
        return air;
    }

    private int countConnectedAirBounded(BlockPos start, BlockPos playerPos, int nodeLimit) {
        if (mc.world == null || !mc.world.getBlockState(start).isAir()) return 0;

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        int count = 0;
        long radiusSq = (long) scanRadius.get() * scanRadius.get();

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            count++;
            if (count >= nodeLimit) return count;

            for (Direction dir : Direction.values()) {
                BlockPos next = pos.offset(dir);
                if (next.getSquaredDistance(playerPos) > radiusSq) continue;
                if (!visited.add(next)) continue;
                if (mc.world.getChunk(next.getX() >> 4, next.getZ() >> 4) instanceof EmptyChunk) continue;
                if (!mc.world.getBlockState(next).isAir()) continue;
                queue.add(next);
            }
        }
        return count;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(foundCaves.size());
    }
}
