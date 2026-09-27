package net.kaisaba.build;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ロビーからゲームを観戦しているプレイヤーを管理する。
 *
 * 観戦中のプレイヤーはスペクテイターモードでアリーナに入り、
 * 現在見ているプロットのみを移動できる。評価はできない（見るだけ）。
 */
public class SpectatorManager {

    /** 観戦中プレイヤー UUID → 現在見ているプロットインデックス */
    private final Map<UUID, Integer> spectators = new HashMap<>();

    private final KaisabaBuild plugin;

    public SpectatorManager(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    /** 指定プレイヤーが観戦中かどうか。 */
    public boolean isSpectating(UUID uuid) {
        return spectators.containsKey(uuid);
    }

    /** 現在見ているプロットインデックスを返す。観戦中でなければ -1。 */
    public int getCurrentPlot(UUID uuid) {
        return spectators.getOrDefault(uuid, -1);
    }

    /**
     * 観戦を開始する。
     * アクティブプレイヤーのリストから最初のプロットへテレポートし、スペクテイターモードにする。
     */
    public void startSpectating(Player player) {
        List<UUID> activePlayers = plugin.getGameManager().getActivePlayers();
        if (activePlayers.isEmpty()) return;

        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) return;

        // 最初のアクティブプレイヤーのプロットから観戦開始
        UUID firstTarget = activePlayers.get(0);
        int plotIndex = plugin.getPlotManager().getPlotIndexByUuid(firstTarget);
        if (plotIndex < 0) return;

        spectators.put(player.getUniqueId(), plotIndex);

        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);

        Location spawnLoc = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        player.teleport(spawnLoc);

        giveSpectatorHotbar(player);
    }

    /**
     * 観戦を終了してロビーに戻す。
     */
    public void stopSpectating(Player player) {
        if (!spectators.containsKey(player.getUniqueId())) return;
        spectators.remove(player.getUniqueId());

        // ロビーへ戻す
        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        World lobbyWorld = Bukkit.getWorld(lobbyWorldName);
        if (lobbyWorld == null) return;

        double x = plugin.getConfig().getDouble("lobby-spawn.x", 0);
        double y = plugin.getConfig().getDouble("lobby-spawn.y", 64);
        double z = plugin.getConfig().getDouble("lobby-spawn.z", 0);
        float yaw = (float) plugin.getConfig().getDouble("lobby-spawn.yaw", 0);
        float pitch = (float) plugin.getConfig().getDouble("lobby-spawn.pitch", 0);
        player.teleport(new Location(lobbyWorld, x, y, z, yaw, pitch));

        // ロビーアイテムを再付与
        net.kaisaba.build.listener.LobbyListener.giveLobbyItem(player);
    }

    /**
     * 次のプロットに移動する（アクティブプレイヤー順に循環）。
     */
    public void nextPlot(Player player) {
        UUID uuid = player.getUniqueId();
        if (!spectators.containsKey(uuid)) return;

        List<UUID> activePlayers = plugin.getGameManager().getActivePlayers();
        if (activePlayers.isEmpty()) return;

        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) return;

        int currentPlot = spectators.get(uuid);

        // activePlayers のリストからcurrentPlotのプレイヤーを探し、次のプレイヤーのプロットへ
        int currentIdx = -1;
        for (int i = 0; i < activePlayers.size(); i++) {
            int pPlot = plugin.getPlotManager().getPlotIndexByUuid(activePlayers.get(i));
            if (pPlot == currentPlot) {
                currentIdx = i;
                break;
            }
        }

        int nextIdx = (currentIdx + 1) % activePlayers.size();
        UUID nextTarget = activePlayers.get(nextIdx);
        int nextPlot = plugin.getPlotManager().getPlotIndexByUuid(nextTarget);
        if (nextPlot < 0) return;

        spectators.put(uuid, nextPlot);

        Location spawnLoc = plugin.getPlotManager().getPlotSpawn(nextPlot, arenaWorld);
        player.teleport(spawnLoc);

        giveSpectatorHotbar(player);
    }

    /**
     * 観戦中のホットバーを配布する。
     *   スロット0: 矢（次の建築へ）
     *   スロット8: 観戦終了（ロビーへ戻る）
     */
    public void giveSpectatorHotbar(Player player) {
        UUID uuid = player.getUniqueId();
        if (!spectators.containsKey(uuid)) return;

        List<UUID> activePlayers = plugin.getGameManager().getActivePlayers();
        int currentPlot = spectators.get(uuid);

        // 現在見ているプレイヤー名を解決
        String targetName = "不明";
        int currentIdx = 0;
        for (int i = 0; i < activePlayers.size(); i++) {
            int pPlot = plugin.getPlotManager().getPlotIndexByUuid(activePlayers.get(i));
            if (pPlot == currentPlot) {
                Player p = Bukkit.getPlayer(activePlayers.get(i));
                targetName = p != null ? p.getName() : "不明";
                currentIdx = i;
                break;
            }
        }

        net.kyori.adventure.text.Component arrowName = net.kyori.adventure.text.Component.text(
            "次の建築へ → [" + targetName + "] (" + (currentIdx + 1) + "/" + activePlayers.size() + ")",
            net.kyori.adventure.text.format.NamedTextColor.AQUA
        );
        net.kyori.adventure.text.Component exitName = net.kyori.adventure.text.Component.text(
            "観戦終了",
            net.kyori.adventure.text.format.NamedTextColor.RED
        );
        net.kyori.adventure.text.Component exitLore = net.kyori.adventure.text.Component.text(
            "右クリックでロビーに戻る",
            net.kyori.adventure.text.format.NamedTextColor.GRAY
        );

        player.getInventory().setItem(0,
            net.kaisaba.build.util.InventoryUtil.makeItem(org.bukkit.Material.ARROW, arrowName));
        player.getInventory().setItem(8,
            net.kaisaba.build.util.InventoryUtil.makeItem(org.bukkit.Material.RED_BED, exitName, exitLore));
    }

    /**
     * ゲーム終了時などで観戦者を全員ロビーへ戻す。
     */
    public void removeAllSpectators() {
        for (UUID uuid : new java.util.ArrayList<>(spectators.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                stopSpectating(p);
            } else {
                spectators.remove(uuid);
            }
        }
    }

    /** 観戦中プレイヤーを内部マップから除外する（ログアウト時用）。 */
    public void handleQuit(UUID uuid) {
        spectators.remove(uuid);
    }
}
