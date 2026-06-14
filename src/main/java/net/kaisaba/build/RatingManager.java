package net.kaisaba.build;

import net.kaisaba.build.util.InventoryUtil;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 評価データの保存・ホットバー配布・結果集計を管理する。
 *
 * ホットバー構成:
 *   スロット0: 矢（次の建築へ）
 *   スロット1: 空
 *   スロット2: ☆1
 *   スロット3: ☆2
 *   スロット4: ☆3
 *   スロット5: ☆4
 *   スロット6: ☆5
 *   スロット7: 空
 *   スロット8: 評価完了ボタン
 *
 * RatingListener側はスロット番号で操作を判定する（アイテム名依存なし）。
 */
public class RatingManager {

    /** 評価者UUID → (被評価者UUID → 点数) */
    private final Map<UUID, Map<UUID, Integer>> ratings = new HashMap<>();

    /** 評価者UUID → 現在のポインタ */
    private final Map<UUID, Integer> pointers = new HashMap<>();

    /** 評価完了済みプレイヤー（ホットバーをロックするため） */
    private final Set<UUID> hotbarLocked = new HashSet<>();

    /** 評価フェーズの被評価者リスト */
    private List<UUID> participants = new ArrayList<>();

    private final KaisabaBuild plugin;

    public RatingManager(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    /** 評価フェーズ開始時に呼ぶ。 */
    public void startRating(List<UUID> participantList) {
        ratings.clear();
        pointers.clear();
        hotbarLocked.clear();
        participants = new ArrayList<>(participantList);

        for (UUID uuid : participantList) {
            ratings.put(uuid, new HashMap<>());
            pointers.put(uuid, 0);
        }
    }

    /** 評価データをリセットする（ゲーム終了後に呼ぶ）。 */
    public void reset() {
        ratings.clear();
        pointers.clear();
        hotbarLocked.clear();
        participants.clear();
    }

    // ─── ホットバー ─────────────────────────────────────────

    /**
     * 評価用ホットバーを配布する。
     * 評価完了済み（hotbarLocked）のプレイヤーには giveCompletedHotbar を呼ぶ。
     */
    public void giveRatingHotbar(Player rater) {
        UUID raterUuid = rater.getUniqueId();

        // 完了済みプレイヤーには完了ホットバーを渡してそれ以上触らない
        if (hotbarLocked.contains(raterUuid)) {
            giveCompletedHotbar(rater);
            return;
        }

        List<UUID> targets = getTargetsFor(raterUuid);

        int pointer = pointers.getOrDefault(raterUuid, 0);
        if (!targets.isEmpty()) pointer = pointer % targets.size();
        UUID targetUuid = targets.isEmpty() ? null : targets.get(pointer);
        boolean isSelf = targetUuid != null && targetUuid.equals(raterUuid);

        String targetName = resolvePlayerName(targetUuid);
        Map<UUID, Integer> myRatings = ratings.getOrDefault(raterUuid, new HashMap<>());
        int currentScore = (targetUuid != null) ? myRatings.getOrDefault(targetUuid, 0) : 0;

        // 全員評価完了チェック（自分除く）
        List<UUID> rateable = targets.stream().filter(u -> !u.equals(raterUuid)).toList();
        boolean allRated = !rateable.isEmpty() && rateable.stream().allMatch(myRatings::containsKey);

        rater.getInventory().clear();

        // スロット0: 矢（次の建築へ）
        String arrowLabel = isSelf
            ? "次の建築 → (今: 自分)"
            : "次の建築 →  [" + targetName + "]";
        Component arrowLore = isSelf
            ? Component.text("自分の建築を見ています", NamedTextColor.GRAY)
            : Component.text("(" + (pointer + 1) + "/" + targets.size() + ")", NamedTextColor.GRAY);
        rater.getInventory().setItem(0, InventoryUtil.makeItem(
            Material.ARROW,
            Component.text(arrowLabel, NamedTextColor.AQUA),
            arrowLore
        ));

        // スロット2〜6: ☆1〜☆5
        for (int star = 1; star <= 5; star++) {
            int slot = star + 1; // 2〜6
            if (isSelf) {
                rater.getInventory().setItem(slot, InventoryUtil.makeItem(
                    Material.GRAY_CONCRETE,
                    Component.text("自分の建築は評価できません", NamedTextColor.GRAY)
                ));
            } else {
                boolean selected = (star <= currentScore);
                Material mat = selected ? Material.YELLOW_CONCRETE : Material.WHITE_CONCRETE;
                String starLabel = "☆".repeat(star) + "  [" + targetName + "]";
                Component starName = Component.text(starLabel, selected ? NamedTextColor.GOLD : NamedTextColor.WHITE);
                rater.getInventory().setItem(slot, InventoryUtil.makeItem(mat, starName));
            }
        }

        // スロット8: 評価完了ボタン
        Material completeMat = allRated ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        Component completeLabel = Component.text("評価完了", allRated ? NamedTextColor.GREEN : NamedTextColor.RED);
        Component completeLore = allRated
            ? Component.text("全員の評価が完了！右クリックで完了。", NamedTextColor.GREEN)
            : Component.text("まだ評価していない建築があります。", NamedTextColor.RED);
        rater.getInventory().setItem(8, InventoryUtil.makeItem(completeMat, completeLabel, completeLore));
    }

    /**
     * 評価完了後のホットバー（ロック状態）。
     */
    public void giveCompletedHotbar(Player rater) {
        rater.getInventory().clear();
        rater.getInventory().setItem(4, InventoryUtil.makeItem(
            Material.LIME_CONCRETE,
            Component.text("評価完了済み", NamedTextColor.GREEN),
            Component.text("他のプレイヤーの評価を待っています...", NamedTextColor.GRAY)
        ));
    }

    /**
     * 評価完了としてロックする（GameManager.markRatingComplete から呼ぶ）。
     */
    public void lockHotbar(Player rater) {
        hotbarLocked.add(rater.getUniqueId());
        giveCompletedHotbar(rater);
    }

    // ─── インタラクション処理（スロット番号で判定）──────────────

    /** スロット0: 次の建築へ。 */
    public void handleNextArrow(Player rater) {
        UUID raterUuid = rater.getUniqueId();
        if (hotbarLocked.contains(raterUuid)) return;

        List<UUID> targets = getTargetsFor(raterUuid);
        if (targets.isEmpty()) return;

        int pointer = pointers.getOrDefault(raterUuid, 0);
        int newPointer = (pointer + 1) % targets.size();
        pointers.put(raterUuid, newPointer);

        UUID newTarget = targets.get(newPointer);
        teleportToTarget(rater, newTarget);
        giveRatingHotbar(rater);
    }

    /**
     * スロット2〜6: ☆評価。
     * @param star 1〜5
     */
    public void handleStarClick(Player rater, int star) {
        UUID raterUuid = rater.getUniqueId();
        if (hotbarLocked.contains(raterUuid)) return;

        List<UUID> targets = getTargetsFor(raterUuid);
        if (targets.isEmpty()) return;

        int pointer = pointers.getOrDefault(raterUuid, 0) % targets.size();
        UUID targetUuid = targets.get(pointer);

        if (targetUuid.equals(raterUuid)) {
            MessageUtil.send(rater, Component.text("自分の建築は評価できません。", NamedTextColor.RED));
            return;
        }

        ratings.get(raterUuid).put(targetUuid, star);
        MessageUtil.send(rater, Component.text(
            resolvePlayerName(targetUuid) + " の建築を ☆" + star + " で評価しました！",
            NamedTextColor.YELLOW
        ));

        // UIを更新（選択した☆の表示を反映）
        giveRatingHotbar(rater);
    }

    /**
     * スロット8: 評価完了ボタン。
     * 全員分の評価が済んでいない場合は拒否してメッセージ送信。
     * @return true なら確認GUIを開いてよい
     */
    public boolean canComplete(Player rater) {
        UUID raterUuid = rater.getUniqueId();
        if (hotbarLocked.contains(raterUuid)) return false;

        Map<UUID, Integer> myRatings = ratings.getOrDefault(raterUuid, new HashMap<>());
        List<UUID> rateable = getTargetsFor(raterUuid).stream()
            .filter(u -> !u.equals(raterUuid)).toList();

        if (rateable.isEmpty() || rateable.stream().allMatch(myRatings::containsKey)) {
            return true;
        }

        MessageUtil.send(rater, Component.text(
            "まだ評価していない建築があります！全員分を評価してから完了してください。",
            NamedTextColor.RED
        ));
        return false;
    }

    // ─── 結果集計 ────────────────────────────────────────────

    public void announceResults() {
        Map<UUID, Double> averages = new LinkedHashMap<>();
        for (UUID target : participants) {
            List<Integer> scores = new ArrayList<>();
            for (Map<UUID, Integer> raterMap : ratings.values()) {
                if (raterMap.containsKey(target)) scores.add(raterMap.get(target));
            }
            double avg = scores.isEmpty() ? 0.0
                : scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            averages.put(target, avg);
        }

        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(averages.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        MessageUtil.broadcast(Component.text("━━━ 建築バトル 結果発表 ━━━", NamedTextColor.GOLD, TextDecoration.BOLD));
        int rank = 1;
        int count = 0;
        double prevScore = Double.MAX_VALUE;
        for (Map.Entry<UUID, Double> entry : sorted) {
            count++;
            if (entry.getValue() < prevScore) {
                rank = count;
                prevScore = entry.getValue();
            }
            String name = resolvePlayerName(entry.getKey());
            MessageUtil.broadcast(Component.text(
                rank + "位: " + name + "  平均 " + String.format("%.1f", entry.getValue()) + " 点",
                rank == 1 ? NamedTextColor.GOLD : rank == 2 ? NamedTextColor.GRAY : NamedTextColor.WHITE
            ));
        }
    }

    // ─── 内部ユーティリティ ─────────────────────────────────

    private List<UUID> getTargetsFor(UUID raterUuid) {
        return new ArrayList<>(participants);
    }

    private String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "不明";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName())
            .orElse(uuid.toString().substring(0, 8));
    }

    public void teleportToTarget(Player rater, UUID targetUuid) {
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        org.bukkit.World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) return;

        int plotIndex = plugin.getPlotManager().getPlotIndexByUuid(targetUuid);
        if (plotIndex < 0) return;

        org.bukkit.Location base = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        int safeY = base.getBlockY();
        for (int y = 126; y >= base.getBlockY(); y--) {
            org.bukkit.block.Block feet  = arenaWorld.getBlockAt(base.getBlockX(), y,     base.getBlockZ());
            org.bukkit.block.Block head  = arenaWorld.getBlockAt(base.getBlockX(), y + 1, base.getBlockZ());
            org.bukkit.block.Block below = arenaWorld.getBlockAt(base.getBlockX(), y - 1, base.getBlockZ());
            if (feet.getType() == org.bukkit.Material.AIR
                    && head.getType() == org.bukkit.Material.AIR
                    && below.getType() != org.bukkit.Material.AIR) {
                safeY = y;
                break;
            }
        }
        rater.teleport(new org.bukkit.Location(arenaWorld, base.getX(), safeY, base.getZ(),
                base.getYaw(), base.getPitch()));
    }
}
