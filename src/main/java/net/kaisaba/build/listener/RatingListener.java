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
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 評価フェーズのホットバー操作・評価完了確認メニューを処理する。
 *
 * ホットバーのスロット番号で操作を判定（アイテム名依存なし）:
 *   スロット0: 矢（次の建築へ）
 *   スロット2〜6: ☆1〜☆5
 *   スロット8: 評価完了ボタン
 */
public class RatingListener implements Listener {

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
        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;

        event.setCancelled(true);

        switch (heldSlot) {
            case 0 -> // 矢: 次の建築へ
                plugin.getRatingManager().handleNextArrow(player);

            case 2, 3, 4, 5, 6 -> { // ☆1〜☆5
                int star = heldSlot - 1; // slot2→1, slot3→2, ..., slot6→5
                plugin.getRatingManager().handleStarClick(player, star);
            }

            case 8 -> { // 評価完了ボタン
                if (plugin.getRatingManager().canComplete(player)) {
                    openRatingConfirm(player);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.getGameManager().getState() != GameState.RATING) return;

        Component title = event.getView().title();
        String plain = PlainTextComponentSerializer.plainText().serialize(title);

        // 評価完了確認GUIのクリックのみ処理
        if (plain.equals(RATING_CONFIRM_TITLE)) {
            event.setCancelled(true);
            handleRatingConfirmClick(player, event.getSlot());
            return;
        }

        // それ以外のインベントリ操作はすべてキャンセル
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.getGameManager().getState() == GameState.RATING) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
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

    // ─── 評価完了確認GUI ─────────────────────────────────────

    private void openRatingConfirm(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 9,
            Component.text(RATING_CONFIRM_TITLE, NamedTextColor.DARK_GREEN));

        inv.setItem(1, InventoryUtil.makeItem(
            Material.LIME_CONCRETE,
            Component.text("はい", NamedTextColor.GREEN),
            Component.text("評価を完了します。", NamedTextColor.GRAY)
        ));

        inv.setItem(7, InventoryUtil.makeItem(
            Material.RED_CONCRETE,
            Component.text("いいえ", NamedTextColor.RED),
            Component.text("評価に戻ります。", NamedTextColor.GRAY)
        ));

        player.openInventory(inv);
    }

    private void handleRatingConfirmClick(Player player, int slot) {
        if (slot == 1) {
            player.closeInventory();
            plugin.getGameManager().markRatingComplete(player);
        } else if (slot == 7) {
            player.closeInventory();
        }
    }
}
