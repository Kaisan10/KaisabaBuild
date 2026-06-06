package net.kaisaba.build;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ゲーム早期離脱ペナルティを管理する。
 *
 * ゲーム開始から {@code GRACE_PERIOD_MS} 以内に離脱したプレイヤーに
 * {@code PENALTY_MS} のキュー参加禁止ペナルティを与える。
 *
 * 付与: {@link #applyPenalty(UUID)}
 * チェック: {@link #isPenalized(UUID)}
 * 解除: {@link #clearPenalty(UUID)}
 */
public class PenaltyManager {

    /** ペナルティが発生するゲーム開始からの猶予時間（ミリ秒）= 3分 */
    public static final long GRACE_PERIOD_MS = 3 * 60 * 1000L;

    /** ペナルティ継続時間（ミリ秒）= 5分 */
    public static final long PENALTY_MS = 5 * 60 * 1000L;

    /** UUID → ペナルティ解除時刻（エポックミリ秒） */
    private final Map<UUID, Long> penalties = new HashMap<>();

    // ─── ペナルティ操作 ──────────────────────────────────────

    /**
     * プレイヤーにペナルティを付与する。
     * ゲーム開始 3 分以内に離脱したプレイヤーを対象に {@link GameManager} から呼ぶ。
     */
    public void applyPenalty(UUID uuid) {
        penalties.put(uuid, System.currentTimeMillis() + PENALTY_MS);
    }

    /**
     * プレイヤーがペナルティ中かどうかを返す。
     * 期限切れのエントリは自動で削除する。
     */
    public boolean isPenalized(UUID uuid) {
        Long expiry = penalties.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            penalties.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * ペナルティの残り秒数を返す。ペナルティがなければ 0。
     */
    public long getRemainingSeconds(UUID uuid) {
        Long expiry = penalties.get(uuid);
        if (expiry == null) return 0L;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (remaining / 1000) : 0L;
    }

    /**
     * ペナルティを手動で解除する。
     *
     * @return 解除できた（ペナルティが存在した）なら true
     */
    public boolean clearPenalty(UUID uuid) {
        return penalties.remove(uuid) != null;
    }

    /**
     * キュー参加可否をチェックし、ペナルティ中なら拒否メッセージを送って false を返す。
     * {@link QueueManager#addToQueue(Player)} の先頭で使う。
     */
    public boolean checkAndNotify(Player player) {
        if (!isPenalized(player.getUniqueId())) return true;
        long secs = getRemainingSeconds(player.getUniqueId());
        player.sendMessage(Component.text(
            "早期離脱ペナルティ中です。あと " + secs + " 秒経過するとキューに参加できます。",
            NamedTextColor.RED
        ));
        return false;
    }
}
