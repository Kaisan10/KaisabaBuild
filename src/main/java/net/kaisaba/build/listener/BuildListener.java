package net.kaisaba.build.listener;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 建築フェーズのブロック制御。
 *
 * ロビーワールド: ブロック操作を全てキャンセル
 * アリーナ・BUILDING 以外: キャンセル
 * アリーナ・BUILDING: 自プロット外・壁への操作をキャンセル
 * デスしたら自プロットにリスポーン
 */
public class BuildListener implements Listener {

    private final KaisabaBuild plugin;
    /** 移動警告メッセージの連続送信を防ぐ（UUID → 最後に警告した時刻ms） */
    private final Set<UUID> warnCooldown = new HashSet<>();

    public BuildListener(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

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
        // ブロック単位で移動した時のみチェック（負荷軽減）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        if (!player.getWorld().getName().equals(arenaName)) return;
        if (plugin.getGameManager().getState() != GameState.BUILDING) return;

        int playerPlot = plugin.getPlotManager().getPlotIndex(player);
        int currentPlot = plugin.getPlotManager().getPlotAtLocation(event.getTo());

        // 自プロット外（壁・他プロット・アリーナ外）またはY範囲外に出た
        // 砂の床(Y=64)には立てるので下限は63以下（FLOOR_BASE_Y以下）
        boolean outOfPlot = currentPlot != playerPlot;
        int toY = event.getTo().getBlockY();
        boolean outOfY = toY < 64 || toY >= 126;

        if (outOfPlot || outOfY) {
            event.setCancelled(true);
            UUID uuid = player.getUniqueId();
            if (!warnCooldown.contains(uuid)) {
                player.sendMessage(Component.text("自分のプロット外には移動できません！", NamedTextColor.RED));
                warnCooldown.add(uuid);
                // 1秒後にクールダウン解除
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> warnCooldown.remove(uuid), 20L);
            }
            // 次tick でプロットスポーンへ強制送還（エンダーパール等の対策）
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

        // アリーナでデスした → プロットスポーンに戻す
        int plotIndex = plugin.getPlotManager().getPlotIndex(player);
        if (plotIndex < 0) return;

        World arenaWorld = player.getWorld();
        Location spawn = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        event.setRespawnLocation(spawn);
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

        // 壁・床下段（STRIPPED_OAK_WOOD）と天井（GLASS）は破壊・設置キャンセル
        Material type = block.getType();
        int by = block.getY();
        // 床下段: STRIPPED_OAK_WOOD かつ Y<=63
        if (type == Material.STRIPPED_OAK_WOOD && by <= 63) return true;
        // 壁領域: プロット外の STRIPPED_OAK_WOOD
        if (type == Material.STRIPPED_OAK_WOOD && plugin.getPlotManager().isWall(block.getLocation())) return true;
        // 天井: GLASS そのものを壊せない
        if (type == Material.GLASS) return true;
        // 天井の上（Y>=127）へのブロック設置・破壊をキャンセル
        if (by >= 127) return true;

        // 自分のプロット外はキャンセル
        int playerPlot = plugin.getPlotManager().getPlotIndex(player);
        int blockPlot = plugin.getPlotManager().getPlotAtLocation(block.getLocation());

        if (playerPlot != blockPlot || blockPlot < 0) {
            player.sendMessage(Component.text("自分の区画以外では建築できません！", NamedTextColor.RED));
            return true;
        }

        return false;
    }
}
