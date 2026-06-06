package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 評価フェーズの GUI 操作を処理する。
 *
 * コンパス右クリック: 評価 GUI を開く
 * GUI クリック: RatingManager に委譲
 * 評価フェーズ中のドロップ: キャンセル
 */
public class RatingListener implements Listener {

    private static final String GUI_TITLE = "建築を評価する";
    private static final String COMPASS_NAME = "建築を評価する";

    private final KaisabaBuild plugin;

    public RatingListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.getGameManager().getState() != GameState.RATING) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;
        if (!InventoryUtil.hasDisplayName(item, COMPASS_NAME)) return;

        event.setCancelled(true);
        plugin.getRatingManager().openRatingGui(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.getGameManager().getState() != GameState.RATING) return;

        // 評価 GUI かどうかをタイトルで判定
        Component title = event.getView().title();
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(title);
        if (!plain.equals(GUI_TITLE)) return;

        event.setCancelled(true);
        plugin.getRatingManager().handleGuiClick(player, event.getSlot());
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.getGameManager().getState() == GameState.RATING) {
            event.setCancelled(true);
        }
    }
}
