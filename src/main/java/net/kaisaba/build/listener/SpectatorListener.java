package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * 観戦中プレイヤーのイベントを処理する。
 *
 * スロット0: 矢（次のプロットへ）
 * スロット8: 観戦終了（ロビーへ戻る）
 * 観戦中は現在見ているプロット以外へのテレポートを禁止する。
 */
public class SpectatorListener implements Listener {

    private final KaisabaBuild plugin;

    public SpectatorListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!plugin.getSpectatorManager().isSpectating(player.getUniqueId())) return;

        event.setCancelled(true);

        int heldSlot = player.getInventory().getHeldItemSlot();
        switch (heldSlot) {
            case 0 -> plugin.getSpectatorManager().nextPlot(player);
            case 8 -> plugin.getSpectatorManager().stopSpectating(player);
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (plugin.getSpectatorManager().isSpectating(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.getSpectatorManager().isSpectating(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (plugin.getSpectatorManager().isSpectating(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 観戦中は現在見ているプロット以外へのテレポートを禁止する。
     * スペクテイターモードでのフライ移動も Location 変化イベントではなく
     * PlayerMoveEvent になるが、テレポート（エンダーパール等）はここでガードする。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getSpectatorManager().isSpectating(player.getUniqueId())) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        String arenaName = plugin.getConfig().getString("arena-world", "arena");

        // アリーナ以外へのテレポートはキャンセル（ただし stopSpectating 内のロビーへの帰還は除く）
        // stopSpectating 呼び出し時にはすでに spectators から除外されているので isSpectating は false になる
        // → ここに来る時点ではアリーナ内へのテレポートのみ制限する
        if (!to.getWorld().getName().equals(arenaName)) {
            event.setCancelled(true);
            return;
        }

        // 現在見ているプロットと同じプロット内かチェック
        int currentPlot = plugin.getSpectatorManager().getCurrentPlot(player.getUniqueId());
        int targetPlot = plugin.getPlotManager().getPlotAtLocation(to);
        if (currentPlot < 0 || targetPlot != currentPlot) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getSpectatorManager().handleQuit(event.getPlayer().getUniqueId());
    }
}
