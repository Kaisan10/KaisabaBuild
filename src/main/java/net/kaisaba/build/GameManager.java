package net.kaisaba.build;

import net.kaisaba.build.listener.LobbyListener;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ゲームの状態遷移・タイマー・テレポートを管理する。
 *
 * IDLE → (startGame) → BUILDING → (buildTimer終了 or 全員完了) → RATING → (ratingTimer終了 or 全員完了) → IDLE
 *
 * 依存: PlotManager, QueueManager, RatingManager（KaisabaBuild経由で参照）
 */
public class GameManager {

    private GameState state = GameState.IDLE;
    private BukkitTask buildTimer = null;
    private BukkitTask ratingTimer = null;

    /** 建築フェーズ用ボスバー */
    private BossBar buildBossBar = null;
    /** 評価フェーズ用ボスバー */
    private BossBar ratingBossBar = null;
    /** 建築フェーズ残り秒数カウントダウン用タスク */
    private BukkitTask buildCountdownTask = null;
    /** 評価フェーズ残り秒数カウントダウン用タスク */
    private BukkitTask ratingCountdownTask = null;

    /** 現在のゲームに参加しているプレイヤー UUID リスト */
    private final List<UUID> activePlayers = new ArrayList<>();

    /** 建築完了済みプレイヤーの UUID セット */
    private final Set<UUID> completedBuilders = new HashSet<>();

    /** 評価完了済みプレイヤーの UUID セット */
    private final Set<UUID> completedRaters = new HashSet<>();

    /** 建築フェーズ開始時刻（エポックミリ秒）。IDLE 時は -1。 */
    private long gameStartTime = -1L;

    private final KaisabaBuild plugin;

    public GameManager(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    public GameState getState() { return state; }

    // ─── ゲーム開始 ──────────────────────────────────────────

    /**
     * キューから引き渡されたプレイヤーリストでゲームを開始する。
     * QueueManager のカウントダウン完了時に呼ばれる。
     */
    public void startGame(List<UUID> uuids) {
        if (state != GameState.IDLE) return;

        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) {
            MessageUtil.broadcastAdmin("アリーナワールド '" + arenaName + "' が見つかりません。/kb setup を実行してください。");
            return;
        }

        activePlayers.clear();
        completedBuilders.clear();
        completedRaters.clear();
        PlotManager plotManager = plugin.getPlotManager();
        plotManager.releaseAll();

        for (UUID uuid : uuids) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            int plotIndex = plotManager.assignPlot(player);
            if (plotIndex < 0) {
                player.sendMessage(Component.text("プロットの空きがありません。", NamedTextColor.RED));
                continue;
            }

            Location spawn = plotManager.getPlotSpawn(plotIndex, arenaWorld);
            player.teleport(spawn);

            // テレポート後 2tick 後に GameMode・インベントリを設定
            final Player fp = player;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                fp.setGameMode(GameMode.CREATIVE);
                fp.getInventory().clear();
                fp.getInventory().setItem(8, makeBuildMenuCompass());
                fp.sendMessage(Component.text(
                    "建築フェーズ開始！制限時間内に自由に建築してください。",
                    NamedTextColor.GREEN, TextDecoration.BOLD
                ));
                fp.sendMessage(Component.text(
                    "あなたのプロット番号: " + (plotIndex + 1),
                    NamedTextColor.YELLOW
                ));
                fp.sendMessage(Component.text(
                    "右端のコンパスで建築完了ボタンを押せます。",
                    NamedTextColor.GRAY
                ));
            }, 2L);

            activePlayers.add(uuid);
        }

        if (activePlayers.isEmpty()) {
            state = GameState.IDLE;
            return;
        }

        state = GameState.BUILDING;
        gameStartTime = System.currentTimeMillis();
        int buildMinutes = plugin.getConfig().getInt("build-time-minutes", 20);

        plugin.getServer().broadcast(Component.text(
            "━━━ 建築バトル 建築フェーズ開始！制限時間: " + buildMinutes + " 分 ━━━",
            NamedTextColor.YELLOW, TextDecoration.BOLD
        ));

        // 建築フェーズ ボスバー
        int buildTotalSec = buildMinutes * 60;
        buildBossBar = BossBar.bossBar(
            Component.text("建築フェーズ残り " + buildMinutes + " 分 00 秒", NamedTextColor.YELLOW),
            1.0f,
            BossBar.Color.YELLOW,
            BossBar.Overlay.PROGRESS
        );
        showBossBarToPlayers(buildBossBar);

        // 建築フェーズ 1秒ごとカウントダウン表示タスク
        buildCountdownTask = new BukkitRunnable() {
            int remaining = buildTotalSec;
            @Override
            public void run() {
                remaining--;
                if (remaining <= 0) { cancel(); buildCountdownTask = null; return; }
                int m = remaining / 60;
                int s = remaining % 60;
                buildBossBar.name(Component.text(
                    String.format("建築フェーズ残り %d 分 %02d 秒", m, s), NamedTextColor.YELLOW));
                buildBossBar.progress(Math.max(0f, remaining / (float) buildTotalSec));
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // 建築タイマー（フェーズ終了）
        buildTimer = new BukkitRunnable() {
            @Override public void run() { startRating(); }
        }.runTaskLater(plugin, (long) buildTotalSec * 20);
    }

    // ─── 建築完了 ────────────────────────────────────────────

    /**
     * プレイヤーが建築完了を宣言した時に呼ぶ。
     */
    public void markBuildComplete(Player player) {
        if (state != GameState.BUILDING) return;
        UUID uuid = player.getUniqueId();
        if (completedBuilders.contains(uuid)) {
            player.sendMessage(Component.text("すでに建築完了しています。", NamedTextColor.YELLOW));
            return;
        }

        completedBuilders.add(uuid);
        player.sendMessage(Component.text(
            "建築完了しました！他のプレイヤーを待っています...",
            NamedTextColor.GREEN, TextDecoration.BOLD
        ));

        // 全員に完了通知
        int done = completedBuilders.size();
        int total = activePlayers.size();
        for (UUID uid : activePlayers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.sendMessage(Component.text(
                    player.getName() + " が建築完了しました！（" + done + "/" + total + "）",
                    NamedTextColor.AQUA
                ));
            }
        }

        checkAllBuildComplete();
    }

    /** 全員が建築完了済みなら評価フェーズへ即移行する。 */
    private void checkAllBuildComplete() {
        if (completedBuilders.containsAll(activePlayers)) {
            // 建築タイマーをキャンセルして即時移行
            if (buildTimer != null) { buildTimer.cancel(); buildTimer = null; }
            startRating();
        }
    }

    /** プレイヤーが建築完了済みかどうかを返す。BuildListener で使用。 */
    public boolean isBuildComplete(UUID uuid) {
        return completedBuilders.contains(uuid);
    }

    // ─── 評価フェーズ ────────────────────────────────────────

    private void startRating() {
        if (state != GameState.BUILDING) return;
        state = GameState.RATING;
        plugin.getAntiFreecam().setEnabled(false, activePlayers); // 評価中は全プロットを見せる・チャンク再送トリガー

        completedBuilders.clear();
        completedRaters.clear();

        // 建築フェーズ終了 タイトル表示
        Title buildEndTitle = Title.title(
            Component.text("終了", NamedTextColor.RED, TextDecoration.BOLD),
            Component.text("建築フェーズが終了しました！", NamedTextColor.YELLOW),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.showTitle(buildEndTitle);
        }

        // 建築フェーズ ボスバー非表示
        hideBossBarFromPlayers(buildBossBar);
        buildBossBar = null;
        if (buildCountdownTask != null) { buildCountdownTask.cancel(); buildCountdownTask = null; }

        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.getInventory().clear();
        }

        plugin.getRatingManager().startRating(activePlayers);

        // ホットバーはstartRating後に配布し、最初の対象のプロットへテレポートする
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                plugin.getRatingManager().giveRatingHotbar(p);
                if (!activePlayers.isEmpty()) {
                    plugin.getRatingManager().teleportToTarget(p, activePlayers.get(0));
                }
            }
        }

        int ratingMinutes = plugin.getConfig().getInt("rating-time-minutes", 5);
        plugin.getServer().broadcast(Component.text(
            "━━━ 建築バトル 評価フェーズ開始！コンパスで他の建築を評価してください。制限時間: " + ratingMinutes + " 分 ━━━",
            NamedTextColor.AQUA, TextDecoration.BOLD
        ));

        // 評価フェーズ ボスバー
        int ratingTotalSec = ratingMinutes * 60;
        ratingBossBar = BossBar.bossBar(
            Component.text("評価フェーズ残り " + ratingMinutes + " 分 00 秒", NamedTextColor.AQUA),
            1.0f,
            BossBar.Color.BLUE,
            BossBar.Overlay.PROGRESS
        );
        showBossBarToPlayers(ratingBossBar);

        // 評価フェーズ 1秒ごとカウントダウン表示タスク
        ratingCountdownTask = new BukkitRunnable() {
            int remaining = ratingTotalSec;
            @Override
            public void run() {
                remaining--;
                if (remaining <= 0) { cancel(); ratingCountdownTask = null; return; }
                int m = remaining / 60;
                int s = remaining % 60;
                ratingBossBar.name(Component.text(
                    String.format("評価フェーズ残り %d 分 %02d 秒", m, s), NamedTextColor.AQUA));
                ratingBossBar.progress(Math.max(0f, remaining / (float) ratingTotalSec));
            }
        }.runTaskTimer(plugin, 20L, 20L);

        ratingTimer = new BukkitRunnable() {
            @Override public void run() { endGame(); }
        }.runTaskLater(plugin, (long) ratingTotalSec * 20);
    }

    // ─── 評価完了 ────────────────────────────────────────────

    /**
     * プレイヤーが評価完了を宣言した時に呼ぶ。
     */
    public void markRatingComplete(Player player) {
        if (state != GameState.RATING) return;
        UUID uuid = player.getUniqueId();
        if (completedRaters.contains(uuid)) {
            player.sendMessage(Component.text("すでに評価完了しています。", NamedTextColor.YELLOW));
            return;
        }

        completedRaters.add(uuid);
        player.sendMessage(Component.text(
            "評価完了しました！他のプレイヤーを待っています...",
            NamedTextColor.GREEN, TextDecoration.BOLD
        ));
        plugin.getRatingManager().lockHotbar(player);

        // 全員に完了通知
        int done = completedRaters.size();
        int total = activePlayers.size();
        for (UUID uid : activePlayers) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.sendMessage(Component.text(
                    player.getName() + " が評価完了しました！（" + done + "/" + total + "）",
                    NamedTextColor.LIGHT_PURPLE
                ));
            }
        }

        checkAllRatingComplete();
    }

    /** 全員が評価完了済みならゲームを即終了する。 */
    private void checkAllRatingComplete() {
        if (completedRaters.containsAll(activePlayers)) {
            if (ratingTimer != null) { ratingTimer.cancel(); ratingTimer = null; }
            endGame();
        }
    }

    // ─── ゲーム終了 ──────────────────────────────────────────

    public void endGame() {
        if (state == GameState.IDLE) return;

        cancelTimers();
        plugin.getRatingManager().announceResults();

        // state を先に IDLE にする → onPlayerTeleport のガードがロビーテレポートをキャンセルしない
        state = GameState.IDLE;
        gameStartTime = -1L;
        plugin.getAntiFreecam().setEnabled(true); // 評価フェーズ終了 → 再び制限

        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        Location lobbySpawn = getLobbySpawn(lobbyWorldName);

        List<UUID> playersToSend = new ArrayList<>(activePlayers);

        // プロットリセット（非同期・分散処理）
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld != null) {
            for (org.bukkit.entity.Entity entity : arenaWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
            for (int i = 0; i < 16; i++) {
                final int plotIndex = i;
                new BukkitRunnable() {
                    @Override public void run() {
                        plugin.getPlotManager().resetPlot(plotIndex, arenaWorld);
                    }
                }.runTaskLater(plugin, (long) plotIndex * 5L);
            }
        }

        plugin.getRatingManager().reset();
        plugin.getPlotManager().releaseAll();
        activePlayers.clear();
        completedBuilders.clear();
        completedRaters.clear();

        plugin.getServer().broadcast(Component.text(
            "━━━ 建築バトル終了！ロビーに戻ります ━━━",
            NamedTextColor.GOLD, TextDecoration.BOLD
        ));

        for (UUID uuid : playersToSend) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.closeInventory();
            p.teleport(lobbySpawn);
            final Player fp = p;
            Bukkit.getScheduler().runTaskLater(plugin, () -> LobbyListener.giveLobbyItem(fp), 2L);
        }
    }

    public void forceStop() {
        cancelTimers();
        if (state != GameState.IDLE) {
            endGame();
        }
        state = GameState.IDLE;
    }

    // ─── ヘルパー ────────────────────────────────────────────

    private void cancelTimers() {
        if (buildTimer != null) { buildTimer.cancel(); buildTimer = null; }
        if (ratingTimer != null) { ratingTimer.cancel(); ratingTimer = null; }
        if (buildCountdownTask != null) { buildCountdownTask.cancel(); buildCountdownTask = null; }
        if (ratingCountdownTask != null) { ratingCountdownTask.cancel(); ratingCountdownTask = null; }
        hideBossBarFromPlayers(buildBossBar); buildBossBar = null;
        hideBossBarFromPlayers(ratingBossBar); ratingBossBar = null;
        completedBuilders.clear();
        completedRaters.clear();
    }

    private Location getLobbySpawn(String lobbyWorldName) {
        World lobbyWorld = Bukkit.getWorld(lobbyWorldName);
        if (lobbyWorld == null) lobbyWorld = Bukkit.getWorlds().get(0);

        double x = plugin.getConfig().getDouble("lobby-spawn.x", 0);
        double y = plugin.getConfig().getDouble("lobby-spawn.y", 64);
        double z = plugin.getConfig().getDouble("lobby-spawn.z", 0);
        float yaw = (float) plugin.getConfig().getDouble("lobby-spawn.yaw", 0);
        float pitch = (float) plugin.getConfig().getDouble("lobby-spawn.pitch", 0);
        return new Location(lobbyWorld, x, y, z, yaw, pitch);
    }

    /** 建築フェーズ用メニューコンパス（スロット8）。 */
    private ItemStack makeBuildMenuCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("建築メニュー", NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }





    /** 参加中の全プレイヤーにボスバーを表示する。 */
    private void showBossBarToPlayers(BossBar bar) {
        if (bar == null) return;
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.showBossBar(bar);
        }
    }

    /** 全オンラインプレイヤーからボスバーを非表示にする。 */
    private void hideBossBarFromPlayers(BossBar bar) {
        if (bar == null) return;
        plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
    }

    public List<UUID> getActivePlayers() { return activePlayers; }

    /**
     * プレイヤーがゲーム中に離脱した際に呼ぶ。
     */
    public void handlePlayerQuit(UUID uuid) {
        if (state == GameState.IDLE) return;
        if (!activePlayers.contains(uuid)) return;

        long elapsed = System.currentTimeMillis() - gameStartTime;
        if (gameStartTime > 0 && elapsed < PenaltyManager.GRACE_PERIOD_MS) {
            plugin.getPenaltyManager().applyPenalty(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(Component.text(
                    "ゲーム開始直後に離脱したため、" + (PenaltyManager.PENALTY_MS / 60000) + " 分間のキュー参加禁止ペナルティが付与されました。",
                    NamedTextColor.RED
                ));
            }
        }

        activePlayers.remove(uuid);
        completedBuilders.remove(uuid);
        completedRaters.remove(uuid);

        // 全員退出したらゲーム終了
        if (activePlayers.isEmpty()) {
            endGame();
            return;
        }

        // 離脱後に全員完了チェック（残りメンバーが全員完了していれば移行）
        if (state == GameState.BUILDING) {
            checkAllBuildComplete();
        } else if (state == GameState.RATING) {
            checkAllRatingComplete();
        }
    }
}
