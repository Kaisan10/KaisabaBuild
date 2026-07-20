package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.Material;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.entity.Enderman;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Shulker;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * ピストン・爆発から壁・プロット外ブロックを保護する。
 *
 * - ピストン: イベントをキャンセル（回路の信号は止まらない）
 * - 爆発 (リスポーンアンカー・エンドクリスタル等): 保護対象ブロックだけをリストから除去
 * - ドラゴンの卵: 右クリックによるテレポートをキャンセル
 */
public class ArenaProtectionListener implements Listener {

    private final KaisabaBuild plugin;

    public ArenaProtectionListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    // ─── ピストン ────────────────────────────────────────────

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!isArena(event.getBlock().getWorld().getName())) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;
        if (wouldViolate(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!isArena(event.getBlock().getWorld().getName())) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;
        if (wouldViolate(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    // ─── 爆発 ────────────────────────────────────────────────

    /**
     * リスポーンアンカーなどのブロック爆発。
     * 保護対象ブロックを爆発リストから除去するだけなので他ブロックへの爆発ダメージは続く。
     */
    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!isArena(event.getBlock().getWorld().getName())) return;
        removeProtectedBlocks(event.blockList());
    }

    /**
     * エンティティ由来の爆発（TNT等）。同様に保護ブロックを除去。
     */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isArena(event.getLocation().getWorld().getName())) return;
        removeProtectedBlocks(event.blockList());
    }

    // ─── ドラゴンの卵 ─────────────────────────────────────────

    /** ドア・トラップドア・フェンスゲートの開閉をアリーナ内で許可するブロック種別セット */
    private static final Set<Material> OPENABLE_BLOCKS = EnumSet.of(
        // ドア
        Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR,
        Material.JUNGLE_DOOR, Material.ACACIA_DOOR, Material.DARK_OAK_DOOR,
        Material.MANGROVE_DOOR, Material.CHERRY_DOOR, Material.BAMBOO_DOOR,
        Material.CRIMSON_DOOR, Material.WARPED_DOOR, Material.COPPER_DOOR,
        Material.EXPOSED_COPPER_DOOR, Material.WEATHERED_COPPER_DOOR,
        Material.OXIDIZED_COPPER_DOOR, Material.WAXED_COPPER_DOOR,
        Material.WAXED_EXPOSED_COPPER_DOOR, Material.WAXED_WEATHERED_COPPER_DOOR,
        Material.WAXED_OXIDIZED_COPPER_DOOR, Material.IRON_DOOR,
        // トラップドア
        Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR, Material.BIRCH_TRAPDOOR,
        Material.JUNGLE_TRAPDOOR, Material.ACACIA_TRAPDOOR, Material.DARK_OAK_TRAPDOOR,
        Material.MANGROVE_TRAPDOOR, Material.CHERRY_TRAPDOOR, Material.BAMBOO_TRAPDOOR,
        Material.CRIMSON_TRAPDOOR, Material.WARPED_TRAPDOOR, Material.COPPER_TRAPDOOR,
        Material.EXPOSED_COPPER_TRAPDOOR, Material.WEATHERED_COPPER_TRAPDOOR,
        Material.OXIDIZED_COPPER_TRAPDOOR, Material.WAXED_COPPER_TRAPDOOR,
        Material.WAXED_EXPOSED_COPPER_TRAPDOOR, Material.WAXED_WEATHERED_COPPER_TRAPDOOR,
        Material.WAXED_OXIDIZED_COPPER_TRAPDOOR, Material.IRON_TRAPDOOR,
        // フェンスゲート
        Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
        Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
        Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE, Material.BAMBOO_FENCE_GATE,
        Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE
    );

    /**
     * アリーナ内のドア・トラップドア・フェンスゲートの開閉をプレイヤー全員に許可する。
     * WorldGuard の制限を解除するため HIGHEST 優先度で処理する。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (!OPENABLE_BLOCKS.contains(clicked.getType())) return;
        if (!isArena(clicked.getWorld().getName())) return;
        // アリーナ内のドア系ブロックのインタラクトを常に許可
        event.setCancelled(false);
    }

    /**
     * ドラゴンの卵を右クリックするとテレポートしてしまう挙動をキャンセルする。
     * 左クリック（破壊）は通常通り許可。
     */
    @EventHandler
    public void onDragonEggInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.DRAGON_EGG) return;
        if (!isArena(clicked.getWorld().getName())) return;
        event.setCancelled(true);
    }

    // エンダーマンテレポート禁止 ────────────────────────────────────

    /** アリーナワールド内のエンダーマンのテレポートを全フェーズで禁止する。 */
    @EventHandler
    public void onEndermanTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;
        if (!isArena(event.getEntity().getWorld().getName())) return;
        event.setCancelled(true);
    }

    // シュルカーテレポート禁止 ─────────────────────────────────────────

    /**
     * アリーナ内のシュルカーが別プロットへテレポートしようとしたときにキャンセルする。
     * シュルカーは内部でウォール患イテレポートするため、EntityTeleportEvent で肉える。
     */
    @EventHandler
    public void onShulkerTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof Shulker)) return;
        if (!isArena(event.getEntity().getWorld().getName())) return;
        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        if (to == null) return;
        int fromPlot = plugin.getPlotManager().getPlotAtLocation(from);
        int toPlot = plugin.getPlotManager().getPlotAtLocation(to);
        // 別プロットまたはプロット外への移動は禁止
        if (fromPlot != toPlot) {
            event.setCancelled(true);
        }
    }

    // ─── 共通ロジック ────────────────────────────────────────

    /** アリーナワールドかどうか。 */
    private boolean isArena(String worldName) {
        return worldName.equals(plugin.getConfig().getString("arena-world", "arena"));
    }

    /**
     * 爆発で壊されるブロックリストから保護対象を除去する。
     * blockList() はミュータブルなのでイテレータで安全に削除できる。
     */
    private void removeProtectedBlocks(List<Block> blockList) {
        Iterator<Block> it = blockList.iterator();
        while (it.hasNext()) {
            if (isProtected(it.next().getLocation())) {
                it.remove();
            }
        }
    }

    /**
     * 動かされるブロック群またはその移動先が壁・プロット外に触れるなら true。
     */
    private boolean wouldViolate(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            if (isProtected(block.getLocation())) return true;
            if (isProtected(block.getRelative(direction).getLocation())) return true;
        }
        return false;
    }

    /**
     * 壊してはいけないブロック（壁・床下段・天井・プロット外）なら保護対象。
     */
    private boolean isProtected(Location loc) {
        return plugin.getPlotManager().isIndestructible(loc)
                || plugin.getPlotManager().getPlotAtLocation(loc) < 0;
    }
}

