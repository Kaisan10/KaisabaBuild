package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.InventoryUtil;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ロビーワールドのイベントを処理する。
 *
 * 参加時: ロビースポーンにテレポート + 木のツルハシ付与
 * ツルハシ右クリック: キュー参加/退出のトグル（クールダウン付き）
 * アイテムドロップ・インベントリクリック: キャンセル
 * ログアウト: キューから除外
 */
public class LobbyListener implements Listener {

    public static final String JOIN_ITEM_NAME = "キューに参加";
    public static final String LEAVE_ITEM_NAME = "キューから退出";

    /** キュー操作のクールダウン（ミリ秒） */
    private static final long COOLDOWN_MS = 5000L;
    /** 二重発火防止ウィンドウ（ミリ秒）- メッセージなしでスキップ */
    private static final long DEDUP_MS = 200L;

    /** UUID → 最後にキュー操作した時刻（ms） */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** UUID → 最後にイベントを処理した時刻（二重発火防止用） */
    private final Map<UUID, Long> dedup = new HashMap<>();

    private final KaisabaBuild plugin;

    public LobbyListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String arenaName = plugin.getConfig().getString("arena-world", "arena");

        if (player.getWorld().getName().equals(arenaName)) {
            // アリーナにログインした場合 → ゲームが動いていなければロビーへ
            if (plugin.getGameManager().getState() == net.kaisaba.build.GameState.IDLE) {
                teleportToLobbySpawn(player);
                giveLobbyItem(player);
            } else {
                // ゲーム中でも activePlayers に含まれていない場合はロビーへ
                if (!plugin.getGameManager().getActivePlayers().contains(player.getUniqueId())) {
                    teleportToLobbySpawn(player);
                    giveLobbyItem(player);
                }
            }
            return;
        }

        if (!isLobby(player)) return;
        // ロビースポーンにテレポート
        teleportToLobbySpawn(player);
        giveLobbyItem(player);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // アリーナから別ワールドに移動した場合の処理は onPlayerTeleport でカバー済み
        // ロビーに来たらアイテム配布
        if (isLobby(player)) {
            giveLobbyItem(player);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // オフハンドによる二重発火を防ぐ（メインハンドのみ処理）
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!isLobby(player)) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.WOODEN_PICKAXE) return;
        // 表示名が JOIN か LEAVE のツルハシのみ対象
        String displayName = InventoryUtil.getDisplayName(item);
        if (!JOIN_ITEM_NAME.equals(displayName) && !LEAVE_ITEM_NAME.equals(displayName)) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();

        // 200ms以内の二重発火は無音でスキップ
        if (now - dedup.getOrDefault(player.getUniqueId(), 0L) < DEDUP_MS) return;
        dedup.put(player.getUniqueId(), now);

        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remaining = COOLDOWN_MS - (now - last);

        // クールダウン中はここで止める（ペナルティメッセージと重複しないよう先にチェック）
        if (remaining > 0) {
            player.sendMessage(Component.text(
                "キュー操作が早すぎます。あと " + (remaining / 1000 + 1) + " 秒待ってください。",
                NamedTextColor.YELLOW
            ));
            return;
        }

        if (plugin.getQueueManager().isInQueue(player.getUniqueId())) {
            cooldowns.put(player.getUniqueId(), now);
            plugin.getQueueManager().removeFromQueue(player.getUniqueId());
            player.sendMessage(Component.text("キューから退出しました。", NamedTextColor.YELLOW));
            setItemName(player, JOIN_ITEM_NAME, NamedTextColor.YELLOW);
        } else {
            // addToQueue 内でペナルティチェックを行い、ペナルティ中なら false が返る
            if (plugin.getQueueManager().addToQueue(player)) {
                cooldowns.put(player.getUniqueId(), now);
                player.sendMessage(Component.text("キューに参加しました。", NamedTextColor.GREEN));
                setItemName(player, LEAVE_ITEM_NAME, NamedTextColor.RED);
            }
            // 失敗時はクールダウンを記録しない（ペナルティ中は再試行できるように）
        }
    }

    /**
     * 建築フェーズ中のみ、アリーナ外・プロット外へのテレポートを防ぐ。
     * IDLE・RATING フェーズはガードしない（ロビー帰還・評価移動を許可）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        String arenaName = plugin.getConfig().getString("arena-world", "arena");

        // 建築フェーズ以外はガードしない
        if (plugin.getGameManager().getState() != net.kaisaba.build.GameState.BUILDING) return;

        // アリーナワールドにいるプレイヤーのみ対象
        if (!player.getWorld().getName().equals(arenaName)) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        // アリーナ外へのテレポートはキャンセル
        if (!to.getWorld().getName().equals(arenaName)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("アリーナ外へは移動できません！", NamedTextColor.RED));
            sendToPlotSpawn(player, arenaName);
            return;
        }

        // アリーナ内でも自プロット外へのテレポートをキャンセル（エンダーパール対策）
        int playerPlot = plugin.getPlotManager().getPlotIndex(player);
        int targetPlot = plugin.getPlotManager().getPlotAtLocation(to);
        if (playerPlot < 0 || playerPlot != targetPlot) {
            event.setCancelled(true);
            player.sendMessage(Component.text("自分のプロット外には移動できません！", NamedTextColor.RED));
            sendToPlotSpawn(player, arenaName);
        }
    }

    private void sendToPlotSpawn(Player player, String arenaName) {
        int plotIndex = plugin.getPlotManager().getPlotIndex(player);
        if (plotIndex >= 0) {
            World arenaWorld = plugin.getServer().getWorld(arenaName);
            if (arenaWorld != null) {
                Location spawn = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
                Bukkit.getScheduler().runTask(plugin, () -> player.teleport(spawn));
            }
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isLobby(player) && !player.hasPermission("kaisababuild.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isLobby(player)) return;
        if (player.hasPermission("kaisababuild.admin")) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isLobby(player)) return;
        // リスポーン後（1tick後）にアイテムを再付与
        Bukkit.getScheduler().runTask(plugin, () -> giveLobbyItem(player));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // ゲーム中の早期離脱ペナルティチェック（BUILDING/RATING フェーズ対象）
        plugin.getGameManager().handlePlayerQuit(player.getUniqueId());
        plugin.getQueueManager().removeFromQueue(player.getUniqueId());
        cooldowns.remove(player.getUniqueId());
    }

    // ─── ヘルパー ────────────────────────────────────────────

    private boolean isLobby(Player player) {
        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        return player.getWorld().getName().equals(lobbyWorldName);
    }

    private void teleportToLobbySpawn(Player player) {
        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        World lobbyWorld = plugin.getServer().getWorld(lobbyWorldName);
        if (lobbyWorld == null) return;

        double x = plugin.getConfig().getDouble("lobby-spawn.x", 0);
        double y = plugin.getConfig().getDouble("lobby-spawn.y", 64);
        double z = plugin.getConfig().getDouble("lobby-spawn.z", 0);
        float yaw = (float) plugin.getConfig().getDouble("lobby-spawn.yaw", 0);
        float pitch = (float) plugin.getConfig().getDouble("lobby-spawn.pitch", 0);
        player.teleport(new Location(lobbyWorld, x, y, z, yaw, pitch));
    }

    /** ゲーム終了後にロビーに戻ったプレイヤーにも呼ぶ（GameManager から使用）。 */
    public static void giveLobbyItem(Player player) {
        if (player.hasPermission("kaisababuild.admin")) {
            // 管理者: インベントリはclearしない。スロット4にツルハシだけ置く
            player.getInventory().setItem(4, makePickaxe(JOIN_ITEM_NAME, NamedTextColor.YELLOW));
            return;
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().setItem(4, makePickaxe(JOIN_ITEM_NAME, NamedTextColor.YELLOW));
    }

    /** ゲーム終了後、ロビー戻り時にアイテム名をリセット。 */
    public static void resetLobbyItem(Player player) {
        giveLobbyItem(player); // 同じく JOIN 状態にリセット
    }

    private void setItemName(Player player, String name, NamedTextColor color) {
        player.getInventory().setItem(4, makePickaxe(name, color));
    }

    private static ItemStack makePickaxe(String name, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.WOODEN_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        item.setItemMeta(meta);
        return item;
    }
}
