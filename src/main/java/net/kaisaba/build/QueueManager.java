package net.kaisaba.build;

import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * キューとカウントダウンを管理する。
 *
 * 参加: {@link #addToQueue(Player)}
 * 離脱: {@link #removeFromQueue(UUID)}
 * min-players 以上で BossBar カウントダウン開始
 * 人数が min を下回ったらカウントダウンをキャンセル
 * カウントダウン完了で {@link GameManager#startGame(List)} を呼ぶ
 */
public class QueueManager {

    private final KaisabaBuild plugin;
    private final GameManager gameManager;

    private final List<UUID> queue = new ArrayList<>();
    private BukkitTask countdownTask = null;
    private BossBar bossBar = null;
    private int countdownSeconds;

    public QueueManager(KaisabaBuild plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    // ─── キュー操作 ──────────────────────────────────────────

    public boolean addToQueue(Player player) {
        int maxPlayers = plugin.getConfig().getInt("max-players", 16);

        // ペナルティチェック（早期離脱ペナルティ中はキュー参加不可）
        if (!plugin.getPenaltyManager().checkAndNotify(player)) return false;

        if (gameManager.getState() != GameState.IDLE && gameManager.getState() != GameState.COUNTDOWN) {
            player.sendMessage(Component.text("現在ゲーム中です。次のゲームをお待ちください。", NamedTextColor.RED));
            return false;
        }
        if (queue.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("既にキューに入っています。", NamedTextColor.YELLOW));
            return false;
        }
        if (queue.size() >= maxPlayers) {
            player.sendMessage(Component.text("キューが満員です。", NamedTextColor.RED));
            return false;
        }

        queue.add(player.getUniqueId());
        // 参加人数を全体にアナウンス（プレフィックスなし）
        plugin.getServer().broadcast(Component.text(
            "[建築バトル] " + player.getName() + " がキューに参加しました。(" + queue.size() + "/" + maxPlayers + ")",
            NamedTextColor.GRAY
        ));

        checkCountdown();
        return true;
    }

    public void removeFromQueue(UUID uuid) {
        if (!queue.remove(uuid)) return;

        Player p = plugin.getServer().getPlayer(uuid);
        String name = (p != null) ? p.getName() : uuid.toString().substring(0, 8);
        plugin.getServer().broadcast(Component.text(
            "[建築バトル] " + name + " がキューから退出しました。(" + queue.size() + "人)",
            NamedTextColor.GRAY
        ));

        checkCountdown();
    }

    public boolean isInQueue(UUID uuid) {
        return queue.contains(uuid);
    }

    public List<UUID> getQueue() {
        return Collections.unmodifiableList(queue);
    }

    public void clearQueue() {
        queue.clear();
        stopCountdown();
    }

    // ─── カウントダウン制御 ──────────────────────────────────

    private void checkCountdown() {
        int minPlayers = plugin.getConfig().getInt("min-players", 3);

        if (queue.size() >= minPlayers) {
            if (countdownTask == null) {
                startCountdown();
            }
        } else {
            if (countdownTask != null) {
                stopCountdown();
                plugin.getServer().broadcast(Component.text(
                    "[建築バトル] 参加者が " + minPlayers + " 人を下回ったためカウントダウンを中断しました。",
                    NamedTextColor.YELLOW
                ));
            }
        }
    }

    private void startCountdown() {
        countdownSeconds = plugin.getConfig().getInt("countdown-seconds", 30);
        bossBar = BossBar.bossBar(
            Component.text("建築バトル開始まで " + countdownSeconds + " 秒"),
            1.0f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS
        );
        showBossBarToQueue();

        int total = countdownSeconds;
        countdownTask = new BukkitRunnable() {
            int seconds = total;
            @Override
            public void run() {
                seconds--;
                if (seconds <= 0) {
                    cancel();
                    countdownTask = null;
                    hideBossBarFromQueue();
                    // キューのスナップショットを GameManager に渡す
                    List<UUID> snapshot = new ArrayList<>(queue);
                    queue.clear();
                    gameManager.startGame(snapshot);
                    return;
                }
                bossBar.name(Component.text("建築バトル開始まで " + seconds + " 秒"));
                bossBar.progress(seconds / (float) total);
            }
        }.runTaskTimer(plugin, 20L, 20L); // 1秒ごと
    }

    private void stopCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        hideBossBarFromQueue();
        bossBar = null;
    }

    private void showBossBarToQueue() {
        if (bossBar == null) return;
        for (UUID uuid : queue) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.showBossBar(bossBar);
        }
    }

    private void hideBossBarFromQueue() {
        if (bossBar == null) return;
        for (UUID uuid : queue) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.hideBossBar(bossBar);
        }
        // アリーナにいるプレイヤーにも念のため非表示
        plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bossBar));
    }
}
