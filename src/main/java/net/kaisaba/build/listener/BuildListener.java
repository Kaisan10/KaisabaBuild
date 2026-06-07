package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.InventoryUtil;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 建築フェーズのブロック制御・コンパスメニュー処理。
 *
 * ロビーワールド: ブロック操作を全てキャンセル
 * アリーナ・BUILDING 以外: キャンセル
 * アリーナ・BUILDING: 自プロット外・壁への操作をキャンセル、建築完了者もキャンセル
 * デスしたら自プロットにリスポーン
 */
public class BuildListener implements Listener {

    private static final String BUILD_MENU_TITLE = "建築メニュー";
    private static final String BUILD_CONFIRM_TITLE = "建築完了の確認";
    private static final String BUILD_MENU_COMPASS = "建築メニュー";

    private final KaisabaBuild plugin;
    /** 移動警告メッセージの連続送信を防ぐ */
    private final Set<UUID> warnCooldown = new HashSet<>();

    public BuildListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    // ─── ブロック操作 ────────────────────────────────────────

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (cancelIfNotAllowed(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (cancelIfNotAllowed(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 建築フェーズ中、プレイヤーが自分のプロット外に出たら強制送還する。
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        if (!player.getWorld().getName().equals(arenaName)) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;

        int playerPlot = plugin.getPlotManager().getPlotIndex(player);
        int currentPlot = plugin.getPlotManager().getPlotAtLocation(event.getTo());

        boolean outOfPlot = currentPlot != playerPlot;
        int toY = event.getTo().getBlockY();
        boolean outOfY = toY < 64 || toY >= 126;

        if (outOfPlot || outOfY) {
            event.setCancelled(true);
            UUID uuid = player.getUniqueId();
            if (!warnCooldown.contains(uuid)) {
                player.sendMessage(Component.text("自分のプロット外には移動できません。", NamedTextColor.RED));
                warnCooldown.add(uuid);
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> warnCooldown.remove(uuid), 20L);
            }
            if (playerPlot >= 0) {
                World arenaWorld = plugin.getServer().getWorld(arenaName);
                if (arenaWorld != null) {
                    Location spawn = plugin.getPlotManager().getPlotSpawn(playerPlot, arenaWorld);
                    plugin.getServer().getScheduler().runTask(plugin, () -> player.teleport(spawn));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        if (!player.getWorld().getName().equals(arenaName)) return;

        int plotIndex = plugin.getPlotManager().getPlotIndex(player);
        if (plotIndex < 0) return;

        World arenaWorld = player.getWorld();
        Location spawn = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        event.setRespawnLocation(spawn);
    }

    // ─── アイテムドロップキャンセル ──────────────────────────

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (dropped.getType() == Material.COMPASS && InventoryUtil.hasDisplayName(dropped, BUILD_MENU_COMPASS)) {
            event.setCancelled(true);
        }
    }

    // ─── コンパスメニュー ────────────────────────────────────

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;
        if (!InventoryUtil.hasDisplayName(item, BUILD_MENU_COMPASS)) return;

        event.setCancelled(true);

        // 完了済みの場合はメッセージだけ
        if (plugin.getGameManager().isBuildComplete(player.getUniqueId())) {
            player.sendMessage(Component.text("すでに建築完了しています。他のプレイヤーを待っています...", NamedTextColor.YELLOW));
            return;
        }

        openBuildMenu(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;

        Component title = event.getView().title();
        String plain = PlainTextComponentSerializer.plainText().serialize(title);

        if (plain.equals(BUILD_MENU_TITLE)) {
            event.setCancelled(true);
            handleBuildMenuClick(player, event.getSlot());
            return;
        }

        if (plain.equals(BUILD_CONFIRM_TITLE)) {
            event.setCancelled(true);
            handleBuildConfirmClick(player, event.getSlot());
        }
    }

    // ─── GUI ────────────────────────────────────────────────

    private void openBuildMenu(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 9,
            Component.text(BUILD_MENU_TITLE, NamedTextColor.GREEN));

        inv.setItem(2, InventoryUtil.makeItem(
            Material.LIME_DYE,
            Component.text("建築完了", NamedTextColor.GREEN),
            Component.text("建築を完了して評価フェーズを待ちます", NamedTextColor.GRAY)
        ));

        inv.setItem(6, InventoryUtil.makeItem(
            Material.RED_DYE,
            Component.text("閉じる", NamedTextColor.RED)
        ));

        player.openInventory(inv);
    }

    private void openBuildConfirm(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 9,
            Component.text(BUILD_CONFIRM_TITLE, NamedTextColor.GOLD));

        inv.setItem(2, InventoryUtil.makeItem(
            Material.LIME_CONCRETE,
            Component.text("はい", NamedTextColor.GREEN),
            Component.text("建築を完了します。以降はブロック操作できません。", NamedTextColor.GRAY)
        ));

        inv.setItem(6, InventoryUtil.makeItem(
            Material.RED_CONCRETE,
            Component.text("いいえ", NamedTextColor.RED),
            Component.text("建築に戻ります。", NamedTextColor.GRAY)
        ));

        player.openInventory(inv);
    }

    private void handleBuildMenuClick(Player player, int slot) {
        if (slot == 2) {
            // 建築完了 → 確認 GUI へ
            openBuildConfirm(player);
        } else if (slot == 6) {
            player.closeInventory();
        }
    }

    private void handleBuildConfirmClick(Player player, int slot) {
        if (slot == 2) {
            // はい
            player.closeInventory();
            plugin.getGameManager().markBuildComplete(player);
        } else if (slot == 6) {
            // いいえ
            player.closeInventory();
        }
    }

    // ─── 内部ロジック ────────────────────────────────────────

    /**
     * ブロック操作を許可しない場合に true を返す。
     */
    private boolean cancelIfNotAllowed(Player player, Block block) {
        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        String worldName = block.getWorld().getName();

        // ロビー: 管理者は許可、それ以外はキャンセル
        if (worldName.equals(lobbyWorldName)) return !player.hasPermission("kaisababuild.admin");

        // アリーナ以外は何もしない
        if (!worldName.equals(arenaName)) return false;

        GameState state = plugin.getGameManager().getState();

        // 建築フェーズ以外はキャンセル
        if (state != GameState.BUILDING) return true;

        // 建築完了済みプレイヤーはキャンセル
        if (plugin.getGameManager().isBuildComplete(player.getUniqueId())) return true;

        // 壁・床下段（STRIPPED_OAK_WOOD）と天井（GLASS）は破壊・設置キャンセル
        Material type = block.getType();
        int by = block.getY();
        if (type == Material.STRIPPED_OAK_WOOD && by <= 63) return true;
        if (type == Material.STRIPPED_OAK_WOOD && plugin.getPlotManager().isWall(block.getLocation())) return true;
        if (type == Material.GLASS) return true;
        if (by >= 127) return true;

        // 自分のプロット外はキャンセル
        int playerPlot = plugin.getPlotManager().getPlotIndex(player);
        int blockPlot = plugin.getPlotManager().getPlotAtLocation(block.getLocation());

        if (playerPlot != blockPlot || blockPlot < 0) {
            player.sendMessage(Component.text("自分のプロット以外では建築ができません。", NamedTextColor.RED));
            return true;
        }

        return false;
    }
}
