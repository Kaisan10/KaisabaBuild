package net.kaisaba.build.listener;

import com.fastasyncworldedit.core.extent.FaweRegionExtent;
import com.fastasyncworldedit.core.limit.FaweLimit;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * WorldEdit の EditSessionEvent にプロセッサを追加し、
 * 建築フェーズ中のプレイヤーが自分のプロット外を編集できないようにする。
 *
 * FAWEはExtentチェーンをバイパスしてチャンク直書きするため、
 * setExtent / MaskingExtent は効かない。
 * FaweRegionExtent を継承した IBatchProcessor として
 * addProcessor() で注入することで、チャンクレベルで制限する。
 */
public class WorldEditRestrictor {

    private final KaisabaBuild plugin;

    public WorldEditRestrictor(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) return;

        // アリーナワールドでのみ制限する
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        if (event.getWorld() == null) return;
        if (!event.getWorld().getName().equals(arenaName)) return;

        // IDLE（ゲーム外）は制限しない。BUILDING・RATING中は制限する
        GameState state = plugin.getGameManager().getState();
        if (state == GameState.IDLE) return;

        UUID uuid = actor.getUniqueId();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;

        // 管理者は制限しない
        if (player.hasPermission("kaisababuild.admin")) return;

        int plotIndex = plugin.getPlotManager().getPlotIndex(player);
        int[] min = plotIndex >= 0 ? plugin.getPlotManager().getPlotMin(plotIndex) : null;
        int[] max = plotIndex >= 0 ? plugin.getPlotManager().getPlotMax(plotIndex) : null;

        // FaweRegionExtent を継承したプロセッサを addProcessor で注入
        // これにより FAWEのチャンク直書きパイプラインにも制限が効く
        PlotRegionProcessor processor = new PlotRegionProcessor(event.getExtent(), min, max);
        event.getExtent().addProcessor(processor);
    }

    /**
     * FaweRegionExtent を継承したプロット範囲制限プロセッサ。
     * contains() で範囲外の座標を false にし、
     * processSet() でチャンク書き込み時に範囲外ブロックを null に戻す。
     */
    private static class PlotRegionProcessor extends FaweRegionExtent {

        // プロットのワールド座標範囲（null = 全拒否）
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final boolean allowAll; // false = 全拒否

        PlotRegionProcessor(Extent extent, int[] min, int[] max) {
            super(extent, FaweLimit.MAX);
            if (min != null && max != null) {
                this.minX = min[0]; this.minY = min[1]; this.minZ = min[2];
                this.maxX = max[0]; this.maxY = max[1]; this.maxZ = max[2];
                this.allowAll = false;
            } else {
                // プロット未割当 → 全拒否
                this.minX = Integer.MAX_VALUE; this.minY = Integer.MAX_VALUE; this.minZ = Integer.MAX_VALUE;
                this.maxX = Integer.MIN_VALUE; this.maxY = Integer.MIN_VALUE; this.maxZ = Integer.MIN_VALUE;
                this.allowAll = false;
            }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        @Override
        public boolean contains(int chunkX, int chunkZ) {
            // チャンク単位の事前フィルタ: チャンクがプロット範囲に一切かからなければ false
            int cMinX = chunkX << 4;
            int cMaxX = cMinX + 15;
            int cMinZ = chunkZ << 4;
            int cMaxZ = cMinZ + 15;
            return cMaxX >= minX && cMinX <= maxX && cMaxZ >= minZ && cMinZ <= maxZ;
        }

        @Override
        public Collection<Region> getRegions() {
            return Collections.emptyList();
        }

        @Override
        public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            // チャンク全体が範囲外 → 全ブロックをクリアして返す（null を返すと AbstractChangeSet が NPE）
            if (!contains(chunkX, chunkZ)) {
                for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
                    if (set.load(layer) != null) set.setBlocks(layer, null);
                }
                return set;
            }

            // チャンクが部分的に範囲内の場合、範囲外のブロックを AIR にする
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;

            for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
                char[] blocks = set.load(layer);
                if (blocks == null) continue;

                int baseY = layer << 4;
                boolean modified = false;

                for (int i = 0; i < blocks.length; i++) {
                    if (blocks[i] == 0) continue; // 変更なし
                    int lx = i & 0xF;
                    int ly = (i >> 8) & 0xF;
                    int lz = (i >> 4) & 0xF;
                    int wx = baseX + lx;
                    int wy = baseY + ly;
                    int wz = baseZ + lz;
                    if (!contains(wx, wy, wz)) {
                        blocks[i] = 0; // 変更をキャンセル
                        modified = true;
                    }
                }

                if (modified) {
                    set.setBlocks(layer, blocks);
                }
            }

            return set;
        }

        @Override
        public Extent construct(Extent child) {
            return this;
        }
    }
}
