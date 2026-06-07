package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 評価フェーズの GUI 操作・評価完了メニューを処理する。
 *
 * スロット4 コンパス（建築を評価する）右クリック: 評価 GUI を開く
 * 評価 GUI スロット26: 評価完了確認メニューを開く
 * GUI クリック: RatingManager に委譲 or 完了処理
 * 評価フェーズ中のドロップ: キャンセル
 */
public class RatingListener implements Listener {

    private static final String RATING_GUI_TITLE = "建築を評価する";
    private static final String RATING_COMPASS_NAME = "建築を評価する";
    private static final String RATING_CONFIRM_TITLE = "評価完了の確認";

    private final KaisabaBuild plugin;

    public RatingListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.getGameManager().getState() != GameState.RATING) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;

        String name = InventoryUtil.getDisplayName(item);
        if (RATING_COMPASS_NAME.equals(name)) {
            event.setCancelled(true);
            plugin.getRatingManager().openRatingGui(player);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.getGameManager().getState() != GameState.RATING) return;

        Component title = event.getView().title();
        String plain = PlainTextComponentSerializer.plainText().serialize(title);

        if (plain.equals(RATING_GUI_TITLE)) {
            event.setCancelled(true);
            if (event.getSlot() == 26) {
                openRatingConfirm(player);
            } else {
                plugin.getRatingManager().handleGuiClick(player, event.getSlot());
            }
            return;
        }

        if (plain.equals(RATING_CONFIRM_TITLE)) {
            event.setCancelled(true);
            handleRatingConfirmClick(player, event.getSlot());
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.getGameManager().getState() == GameState.RATING) {
            event.setCancelled(true);
        }
    }

    /** 評価フェーズ中はアリーナワールドでのエンティティスポーンを禁止する。 */
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (plugin.getGameManager().getState() != GameState.RATING) return;
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        if (event.getEntity().getWorld().getName().equals(arenaName)) {
            event.setCancelled(true);
        }
    }

    // ─── GUI ────────────────────────────────────────────────

    private void openRatingConfirm(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 9,
            Component.text(RATING_CONFIRM_TITLE, NamedTextColor.DARK_GREEN));

        inv.setItem(2, InventoryUtil.makeItem(
            Material.LIME_CONCRETE,
            Component.text("はい", NamedTextColor.GREEN),
            Component.text("評価を完了します。", NamedTextColor.GRAY)
        ));

        inv.setItem(6, InventoryUtil.makeItem(
            Material.RED_CONCRETE,
            Component.text("いいえ", NamedTextColor.RED),
            Component.text("評価に戻ります。", NamedTextColor.GRAY)
        ));

        player.openInventory(inv);
    }

    private void handleRatingConfirmClick(Player player, int slot) {
        if (slot == 2) {
            player.closeInventory();
            plugin.getGameManager().markRatingComplete(player);
        } else if (slot == 6) {
            player.closeInventory();
        }
    }
}
