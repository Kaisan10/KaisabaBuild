package net.kaisaba.build;

import net.kaisaba.build.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プロット（建築区画）の座標・割り当て・リセットを管理する。
 *
 * プロット配置:
 *   4×4 グリッド、最大 16 プロット
 *   プロットサイズ: 64×64
 *   壁幅: 2 (BARRIER)
 *   ピッチ: 66
 *   プロット i の原点: (col*66, 64, row*66)  (col = i%4, row = i/4)
 *   スポーン:          (originX+32, 65, originZ+32)
 */
public class PlotManager {

    private static final int PLOT_SIZE = 64;
    private static final int WALL_WIDTH = 2;
    private static final int PITCH = PLOT_SIZE + WALL_WIDTH; // 66
    private static final int COLS = 4;
    /** 床下段（壊せない）の Y 座標 */
    private static final int FLOOR_BASE_Y = 63;
    /** 床上段（砂・壊せる）の Y 座標 */
    private static final int FLOOR_Y = 64;
    private static final int BUILD_MIN_Y = 65;
    private static final int BUILD_MAX_Y = 319;
    /** 壁の高さ（プロット幅と同じ） */
    private static final int WALL_HEIGHT = PLOT_SIZE; // 64
    /** 天井の Y 座標（床下段から壁高さ分上） */
    private static final int CEILING_Y = FLOOR_BASE_Y + WALL_HEIGHT; // 127

    /** プレイヤー UUID → プロット番号 (0-15) */
    private final Map<UUID, Integer> playerPlot = new HashMap<>();
    /** プロット番号 → 使用中か */
    private final boolean[] usedPlots = new boolean[16];

    private final KaisabaBuild plugin;

    public PlotManager(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    // ─── 割り当て ───────────────────────────────────────────

    /**
     * プレイヤーに空きプロットを割り当てる。
     *
     * @return 割り当てたプロット番号。空きがなければ -1。
     */
    public int assignPlot(Player player) {
        for (int i = 0; i < 16; i++) {
            if (!usedPlots[i]) {
                usedPlots[i] = true;
                playerPlot.put(player.getUniqueId(), i);
                createPlotRegion(i, player.getUniqueId());
                return i;
            }
        }
        return -1;
    }

    public void releasePlot(UUID uuid) {
        Integer idx = playerPlot.remove(uuid);
        if (idx != null) {
            usedPlots[idx] = false;
            clearPlotRegion(idx);
        }
    }

    public void releaseAll() {
        playerPlot.clear();
        for (int i = 0; i < 16; i++) usedPlots[i] = false;
        clearAllPlotRegions();
    }

    /** プレイヤーのプロット番号を返す。未割り当てなら -1。 */
    public int getPlotIndex(Player player) {
        return playerPlot.getOrDefault(player.getUniqueId(), -1);
    }

    /** UUID からプロット番号を返す。未割り当てなら -1。RatingManager から使用。 */
    public int getPlotIndexByUuid(UUID uuid) {
        return playerPlot.getOrDefault(uuid, -1);
    }

    /** Y座標が建築可能範囲外（天井より上または床より下）かどうか。 */
    public boolean isOutOfBuildRange(int y) {
        return y < BUILD_MIN_Y || y > CEILING_Y;
    }

    /**
     * プロットの建築可能範囲の最小座標を返す。
     * WorldEditRestrictor のマスク生成で使用。
     */
    public int[] getPlotMin(int plotIndex) {
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        return new int[]{ col * PITCH, FLOOR_Y, row * PITCH };
    }

    /**
     * プロットの建築可能範囲の最大座標を返す。
     * WorldEditRestrictor のマスク生成で使用。
     */
    public int[] getPlotMax(int plotIndex) {
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        return new int[]{ col * PITCH + PLOT_SIZE - 1, CEILING_Y - 1, row * PITCH + PLOT_SIZE - 1 };
    }

    // ─── 座標計算 ────────────────────────────────────────────

    /** プロット i のスポーン Location を返す。 */
    public Location getPlotSpawn(int plotIndex, World world) {
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        double x = col * PITCH + 32.5;
        double z = row * PITCH + 32.5;
        return new Location(world, x, BUILD_MIN_Y, z);
    }

    /**
     * 指定座標がどのプロット内にあるかを返す。
     * 壁・プロット外なら -1。
     */
    public int getPlotAtLocation(Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        if (x < 0 || z < 0) return -1;

        int col = x / PITCH;
        int row = z / PITCH;
        if (col >= COLS || row >= COLS) return -1;

        int localX = x % PITCH;
        int localZ = z % PITCH;
        // 壁領域 (PLOT_SIZE 以降) は -1
        if (localX >= PLOT_SIZE || localZ >= PLOT_SIZE) return -1;

        return row * COLS + col;
    }

    /** 座標が壁（バリア）領域かどうか。 */
    public boolean isWall(Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        if (x < 0 || z < 0) return false;
        int localX = x % PITCH;
        int localZ = z % PITCH;
        return localX >= PLOT_SIZE || localZ >= PLOT_SIZE;
    }

    /**
     * 爆発・ピストンで絶対に壊してはいけない座標かどうか。
     * 壁領域・床下段(Y<=FLOOR_BASE_Y)・天井(Y>=CEILING_Y) が対象。
     */
    public boolean isIndestructible(Location loc) {
        if (isWall(loc)) return true;
        int y = loc.getBlockY();
        return y <= FLOOR_BASE_Y || y >= CEILING_Y;
    }

    // ─── セットアップ・リセット ──────────────────────────────

    /**
     * アリーナの床・壁を全プロット分初期配置する。
     * /kb setup から呼ぶ。同期処理（負荷注意）。
     */
    public void setupArena(World world) {
        for (int i = 0; i < 16; i++) {
            placeFloorAndWalls(i, world);
        }
        setupArenaRegion();
    }

    // ─── WorldGuard リージョン管理 ───────────────────────────

    private RegionManager getRegionManager() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("arena-world", "arena"));
        if (world == null) return null;
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
    }

    /** /kb setup 時に arena 全体をカバーする保護リージョンを作成する。 */
    private void setupArenaRegion() {
        RegionManager rm = getRegionManager();
        if (rm == null) return;
        BlockVector3 min = BlockVector3.at(-WALL_WIDTH, FLOOR_BASE_Y, -WALL_WIDTH);
        BlockVector3 max = BlockVector3.at(COLS * PITCH - 1, CEILING_Y, COLS * PITCH - 1);
        ProtectedCuboidRegion arena = new ProtectedCuboidRegion("kb_arena", min, max);
        rm.addRegion(arena);
    }

    /** プレイヤーをメンバーとしたプロットリージョンを作成/更新する。 */
    private void createPlotRegion(int plotIndex, UUID uuid) {
        RegionManager rm = getRegionManager();
        if (rm == null) return;
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        BlockVector3 min = BlockVector3.at(col * PITCH, FLOOR_BASE_Y, row * PITCH);
        BlockVector3 max = BlockVector3.at(col * PITCH + PLOT_SIZE - 1, CEILING_Y, row * PITCH + PLOT_SIZE - 1);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion("plot_" + plotIndex, min, max);
        region.setPriority(1);
        ProtectedRegion parent = rm.getRegion("kb_arena");
        if (parent != null) {
            try { region.setParent(parent); } catch (Exception ignored) {}
        }
        region.getMembers().addPlayer(uuid);
        rm.addRegion(region);
    }

    /** プロットリージョンのメンバーをクリアする。 */
    private void clearPlotRegion(int plotIndex) {
        RegionManager rm = getRegionManager();
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion("plot_" + plotIndex);
        if (region != null) region.getMembers().clear();
    }

    /** 全プロットリージョンのメンバーをクリアする。 */
    private void clearAllPlotRegions() {
        RegionManager rm = getRegionManager();
        if (rm == null) return;
        for (int i = 0; i < 16; i++) {
            ProtectedRegion region = rm.getRegion("plot_" + i);
            if (region != null) region.getMembers().clear();
        }
    }

    /**
     * 指定プロットをリセットする（建築物削除 + 床・壁再設置）。
     * BukkitRunnable で非同期分散処理。
     */
    /** 1 tick あたりに処理する Y 層数 */
    private static final int LAYERS_PER_TICK = 8;

    public void resetPlot(int plotIndex, World world) {
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        int originX = col * PITCH;
        int originZ = row * PITCH;

        new BukkitRunnable() {
            int y = BUILD_MIN_Y;

            @Override
            public void run() {
                int end = Math.min(y + LAYERS_PER_TICK, BUILD_MAX_Y + 1);
                for (; y < end; y++) {
                    for (int x = originX; x < originX + PLOT_SIZE; x++) {
                        for (int z = originZ; z < originZ + PLOT_SIZE; z++) {
                            world.getBlockAt(x, y, z).setType(Material.AIR, false);
                        }
                    }
                }
                if (y > BUILD_MAX_Y) {
                    // ブロック削除完了 → 床と壁を再設置
                    placeFloorAndWalls(plotIndex, world);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 床（2層）・壁（STRIPPED_OAK_WOOD）・天井（GLASS）を配置する内部メソッド。
     *
     * 各プロットは4辺すべてに壁を持つ。
     * 壁は各プロットの originX-1〜originX（-X側 = A-D辺）、originX+PLOT_SIZE〜originX+PLOT_SIZE+1（+X側 = B-C辺）、
     * および originZ 側・originZ+PLOT_SIZE 側の計4辺に設置する。
     * ただし壁幅分のオーバーラップが生じても上書きで問題ない。
     */
    private void placeFloorAndWalls(int plotIndex, World world) {
        int col = plotIndex % COLS;
        int row = plotIndex / COLS;
        int originX = col * PITCH;
        int originZ = row * PITCH;

        // ── 床 ──────────────────────────────────────────────────
        // 床下段: Y=63, STRIPPED_OAK_WOOD（壊せない）
        // 床上段: Y=64, SAND（壊せる）
        for (int x = originX; x < originX + PLOT_SIZE; x++) {
            for (int z = originZ; z < originZ + PLOT_SIZE; z++) {
                world.getBlockAt(x, FLOOR_BASE_Y, z).setType(Material.STRIPPED_OAK_WOOD, false);
                world.getBlockAt(x, FLOOR_Y, z).setType(Material.SAND, false);
            }
        }

        // ── 壁（全4辺） ─────────────────────────────────────────
        // 壁を設置する X の範囲: originX-WALL_WIDTH 〜 originX+PLOT_SIZE+WALL_WIDTH-1
        // 壁を設置する Z の範囲: originZ-WALL_WIDTH 〜 originZ+PLOT_SIZE+WALL_WIDTH-1
        // ただし、各辺の壁のみを設置する（プロット内部には置かない）。

        // +X 側の壁 (B-C辺): x = originX+PLOT_SIZE 〜 originX+PLOT_SIZE+WALL_WIDTH-1
        for (int wx = originX + PLOT_SIZE; wx < originX + PLOT_SIZE + WALL_WIDTH; wx++) {
            for (int wz = originZ - WALL_WIDTH; wz < originZ + PLOT_SIZE + WALL_WIDTH; wz++) {
                for (int wy = FLOOR_BASE_Y; wy < FLOOR_BASE_Y + WALL_HEIGHT; wy++) {
                    world.getBlockAt(wx, wy, wz).setType(Material.STRIPPED_OAK_WOOD, false);
                }
            }
        }

        // -X 側の壁 (A-D辺): x = originX-WALL_WIDTH 〜 originX-1
        for (int wx = originX - WALL_WIDTH; wx < originX; wx++) {
            for (int wz = originZ - WALL_WIDTH; wz < originZ + PLOT_SIZE + WALL_WIDTH; wz++) {
                for (int wy = FLOOR_BASE_Y; wy < FLOOR_BASE_Y + WALL_HEIGHT; wy++) {
                    world.getBlockAt(wx, wy, wz).setType(Material.STRIPPED_OAK_WOOD, false);
                }
            }
        }

        // +Z 側の壁 (C-D辺): z = originZ+PLOT_SIZE 〜 originZ+PLOT_SIZE+WALL_WIDTH-1
        for (int wz = originZ + PLOT_SIZE; wz < originZ + PLOT_SIZE + WALL_WIDTH; wz++) {
            for (int wx = originX; wx < originX + PLOT_SIZE; wx++) {
                for (int wy = FLOOR_BASE_Y; wy < FLOOR_BASE_Y + WALL_HEIGHT; wy++) {
                    world.getBlockAt(wx, wy, wz).setType(Material.STRIPPED_OAK_WOOD, false);
                }
            }
        }

        // -Z 側の壁 (A-B辺): z = originZ-WALL_WIDTH 〜 originZ-1
        for (int wz = originZ - WALL_WIDTH; wz < originZ; wz++) {
            for (int wx = originX; wx < originX + PLOT_SIZE; wx++) {
                for (int wy = FLOOR_BASE_Y; wy < FLOOR_BASE_Y + WALL_HEIGHT; wy++) {
                    world.getBlockAt(wx, wy, wz).setType(Material.STRIPPED_OAK_WOOD, false);
                }
            }
        }

        // ── 天井 ────────────────────────────────────────────────
        // Y=CEILING_Y, GLASS でプロット全体＋壁幅を覆う
        for (int x = originX - WALL_WIDTH; x < originX + PLOT_SIZE + WALL_WIDTH; x++) {
            for (int z = originZ - WALL_WIDTH; z < originZ + PLOT_SIZE + WALL_WIDTH; z++) {
                world.getBlockAt(x, CEILING_Y, z).setType(Material.GLASS, false);
            }
        }
    }
}
