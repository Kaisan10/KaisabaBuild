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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Iterator;
import java.util.List;

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

