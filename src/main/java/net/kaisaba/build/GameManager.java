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
import java.util.List;
import java.util.UUID;

/**
 * ゲームの状態遷移・タイマー・テレポートを管理する。
 *
 * IDLE → (startGame) → BUILDING → (buildTimer終了) → RATING → (ratingTimer終了) → IDLE
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
            // 管理者向けにのみ [KB] 付きで通知
            MessageUtil.broadcastAdmin("アリーナワールド '" + arenaName + "' が見つかりません。/kb setup を実行してください。");
            return;
        }

        activePlayers.clear();
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

            // テレポート後 1tick 後に GameMode を設定
            // → Multiverse が teleport に反応してゲームモードを上書きするより後に実行される
            final Player fp = player;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                fp.setGameMode(GameMode.CREATIVE);
                fp.getInventory().clear();
                fp.sendMessage(Component.text(
                    "建築フェーズ開始！制限時間内に自由に建築してください。",
                    NamedTextColor.GREEN, TextDecoration.BOLD
                ));
                fp.sendMessage(Component.text(
                    "あなたのプロット番号: " + (plotIndex + 1),
                    NamedTextColor.YELLOW
                ));
            }, 2L); // 2tick 後（余裕を持たせる）

            activePlayers.add(uuid);
        }

        if (activePlayers.isEmpty()) {
            state = GameState.IDLE;
            return;
        }

        state = GameState.BUILDING;
        gameStartTime = System.currentTimeMillis();
        int buildMinutes = plugin.getConfig().getInt("build-time-minutes", 20);

        // 全員への開始アナウンス（[KB]なし）
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

    // ─── 評価フェーズ ────────────────────────────────────────

    private void startRating() {
        if (state != GameState.BUILDING) return;
        state = GameState.RATING;

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

        ItemStack compass = makeCompass();
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.getInventory().clear();
            p.getInventory().setItem(4, compass);
        }

        plugin.getRatingManager().startRating(activePlayers);

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

    // ─── ゲーム終了 ──────────────────────────────────────────

    public void endGame() {
        if (state == GameState.IDLE) return;

        cancelTimers();
        plugin.getRatingManager().announceResults();

        // state を先に IDLE にする → onPlayerTeleport のガードがロビーテレポートをキャンセルしない
        state = GameState.IDLE;
        gameStartTime = -1L;

        String lobbyWorldName = plugin.getConfig().getString("lobby-world", "world");
        Location lobbySpawn = getLobbySpawn(lobbyWorldName);

        List<UUID> playersToSend = new ArrayList<>(activePlayers);

        // プロットリセット（非同期・分散処理）
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld != null) {
            // アリーナのエンティティー（プレイヤー以外）を全部キル
            for (org.bukkit.entity.Entity entity : arenaWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
            // 割り当て済みプロットをすべてリセット（全員ログアウト中でも正しくリセットされる）
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
        // タイマーをすべてキャンセルし、強制的に IDLE に移行
        cancelTimers();
        if (state != GameState.IDLE) {
            endGame();
        }
        // endGame が IDLE にしていない場合の安全策
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

    private ItemStack makeCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("建築を評価する", NamedTextColor.LIGHT_PURPLE));
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
     * ゲーム開始から {@link PenaltyManager#GRACE_PERIOD_MS} 以内の離脱なら
     * {@link PenaltyManager} にペナルティを記録する。
     *
     * <p>LobbyListener の PlayerQuitEvent から呼ぶ。
     */
    public void handlePlayerQuit(UUID uuid) {
        if (state == GameState.IDLE) return;
        if (!activePlayers.contains(uuid)) return;

        // 3分以内の離脱かチェック
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

        // 全員退出したらゲーム終了
        if (activePlayers.isEmpty()) {
            endGame();
        }
    }
}
