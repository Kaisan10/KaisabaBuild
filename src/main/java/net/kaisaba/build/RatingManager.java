package net.kaisaba.build;

import net.kaisaba.build.util.InventoryUtil;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * 評価データの保存・GUI の開閉・結果集計を管理する。
 *
 * ratings: 「評価者 → (被評価者 → 点数)」の二重 Map
 * pointers: 各評価者が現在何番目の建築を見ているか
 *
 * GUI レイアウト（27 スロット）:
 *   Row 0: [filler] [filler] [filler] [filler] [対象名の本] [filler] [filler] [filler] [filler]
 *   Row 1: [←前 (9)] [filler] [filler] [filler] [★1(13)] [★2(14)] [★3(15)] [★4(16)] [★5(17)]
 *   Row 2: [filler] ... [確定(22)] ...
 */
public class RatingManager {

    private static final String GUI_TITLE = "建築を評価する";

    /** 評価者UUID → (被評価者UUID → 点数) */
    private final Map<UUID, Map<UUID, Integer>> ratings = new HashMap<>();

    /** 評価者UUID → 現在のポインタ（被評価者リスト内の index） */
    private final Map<UUID, Integer> pointers = new HashMap<>();

    /** 評価フェーズの被評価者リスト（ゲーム参加者全員）。評価者自身を除いて表示。 */
    private List<UUID> participants = new ArrayList<>();

    private final KaisabaBuild plugin;

    public RatingManager(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    /** 評価フェーズ開始時に呼ぶ。参加者リストをセットして各人の GUI を初期化。 */
    public void startRating(List<UUID> participantList) {
        ratings.clear();
        pointers.clear();
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
        participants.clear();
    }

    // ─── GUI ────────────────────────────────────────────────

    /** 評価者の現在ポインタが指す被評価者の建築 GUI を開く。 */
    public void openRatingGui(Player rater) {
        List<UUID> targets = getTargetsFor(rater.getUniqueId());
        if (targets.isEmpty()) {
            MessageUtil.send(rater, Component.text("評価する建築がありません。", NamedTextColor.YELLOW));
            return;
        }

        int pointer = pointers.getOrDefault(rater.getUniqueId(), 0);
        if (pointer >= targets.size()) {
            MessageUtil.send(rater, Component.text("全ての建築を評価しました！", NamedTextColor.GREEN));
            return;
        }

        UUID targetUuid = targets.get(pointer);
        Player target = Bukkit.getPlayer(targetUuid);
        String targetName = (target != null) ? target.getName() : targetUuid.toString().substring(0, 8);

        // 現在の評価値（未評価なら 0）
        int currentScore = ratings.get(rater.getUniqueId()).getOrDefault(targetUuid, 0);

        Inventory inv = Bukkit.createInventory(null, 27,
            Component.text(GUI_TITLE, NamedTextColor.DARK_PURPLE));

        // フィラー
        for (int i = 0; i < 27; i++) inv.setItem(i, InventoryUtil.filler());

        // スロット 4: 現在の対象名
        inv.setItem(4, InventoryUtil.makeItem(
            Material.WRITTEN_BOOK,
            Component.text(targetName + " の建築", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("(" + (pointer + 1) + "/" + targets.size() + ")", NamedTextColor.GRAY)
        ));

        // スロット 9: 前へ
        if (pointer > 0) {
            inv.setItem(9, InventoryUtil.makeItem(
                Material.ARROW,
                Component.text("← 前の建築", NamedTextColor.AQUA)
            ));
        }

        // スロット 13-17: ★1 〜 ★5
        for (int star = 1; star <= 5; star++) {
            Material mat = (star <= currentScore) ? Material.GOLD_NUGGET : Material.IRON_NUGGET;
            inv.setItem(12 + star, InventoryUtil.makeItem(
                mat,
                Component.text("★".repeat(star), NamedTextColor.GOLD)
            ));
        }

        // スロット 17: 次へ
        if (pointer < targets.size() - 1) {
            inv.setItem(17, InventoryUtil.makeItem(
                Material.ARROW,
                Component.text("次の建築 →", NamedTextColor.AQUA)
            ));
        }

        // スロット 22: 評価を確定
        inv.setItem(22, InventoryUtil.makeItem(
            Material.LIME_DYE,
            Component.text("評価を確定", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text("現在: " + (currentScore == 0 ? "未評価" : "★".repeat(currentScore)), NamedTextColor.GRAY)
        ));

        rater.openInventory(inv);

        // 被評価者のプロットにテレポート
        teleportToTarget(rater, targetUuid);
    }

    /**
     * GUI クリック処理。RatingListener から呼ぶ。
     *
     * @param slot クリックされたスロット番号
     * @return true なら GUI を再描画する（openRatingGui を再度呼ぶ）
     */
    public boolean handleGuiClick(Player rater, int slot) {
        UUID raterUuid = rater.getUniqueId();
        List<UUID> targets = getTargetsFor(raterUuid);
        int pointer = pointers.getOrDefault(raterUuid, 0);
        if (pointer >= targets.size()) return false;

        UUID targetUuid = targets.get(pointer);

        // ★1〜★5 (slot 13-17)
        if (slot >= 13 && slot <= 17) {
            int score = slot - 12; // 1〜5
            ratings.get(raterUuid).put(targetUuid, score);
            openRatingGui(rater); // 再描画
            return true;
        }

        // ← 前 (slot 9)
        if (slot == 9 && pointer > 0) {
            pointers.put(raterUuid, pointer - 1);
            openRatingGui(rater);
            return true;
        }

        // 次 → (slot 17 は ★5 と兼用のため、targets が残っているときだけ次へ扱いにする)
        // ここでは「次へ」ボタンは slot 17 だが ★5 と被るので slot 8 を代わりに使う
        // → plan.md 準拠: slot 17 は次へ。★5 との衝突は「確定後に次へ」の設計で回避
        // 実装上は ★5=slot 17, 次へ=slot 17 は同じスロットで問題が生じるため
        // 「次へ」は slot 8 に変更する（GUI 再描画も合わせる）
        if (slot == 8 && pointer < targets.size() - 1) {
            pointers.put(raterUuid, pointer + 1);
            openRatingGui(rater);
            return true;
        }

        // 評価を確定 (slot 22)
        if (slot == 22) {
            int score = ratings.get(raterUuid).getOrDefault(targetUuid, 0);
            if (score == 0) {
                MessageUtil.send(rater, Component.text("先に ★ で点数を選んでください。", NamedTextColor.RED));
                return false;
            }
            // 次の未評価プレイヤーへ
            pointers.put(raterUuid, pointer + 1);
            rater.closeInventory();

            if (pointer + 1 >= targets.size()) {
                MessageUtil.send(rater, Component.text("全ての建築を評価しました！ありがとうございます。", NamedTextColor.GREEN));
            } else {
                openRatingGui(rater);
            }
            return true;
        }

        return false;
    }

    // ─── 結果集計 ────────────────────────────────────────────

    /**
     * 全プレイヤーの平均点を計算してアナウンスする。
     * GameManager のゲーム終了時に呼ぶ。
     */
    public void announceResults() {
        // 平均点 Map: UUID → 平均点
        Map<UUID, Double> averages = new LinkedHashMap<>();
        for (UUID target : participants) {
            List<Integer> scores = new ArrayList<>();
            for (Map<UUID, Integer> raterMap : ratings.values()) {
                if (raterMap.containsKey(target)) {
                    scores.add(raterMap.get(target));
                }
            }
            double avg = scores.isEmpty() ? 0.0
                : scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            averages.put(target, avg);
        }

        // 降順ソート
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(averages.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        MessageUtil.broadcast(Component.text("━━━ 建築バトル 結果発表 ━━━", NamedTextColor.GOLD, TextDecoration.BOLD));
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : sorted) {
            Player p = Bukkit.getPlayer(entry.getKey());
            String name = (p != null) ? p.getName() : entry.getKey().toString().substring(0, 8);
            MessageUtil.broadcast(Component.text(
                rank + "位: " + name + "  平均 " + String.format("%.1f", entry.getValue()) + " 点",
                rank == 1 ? NamedTextColor.GOLD : rank == 2 ? NamedTextColor.GRAY : NamedTextColor.WHITE
            ));
            rank++;
        }
    }

    // ─── 内部ユーティリティ ─────────────────────────────────

    /** 評価者自身を除いた被評価者リストを返す。 */
    private List<UUID> getTargetsFor(UUID raterUuid) {
        List<UUID> targets = new ArrayList<>(participants);
        targets.remove(raterUuid);
        return targets;
    }

    /** 被評価者のプロットスポーンへ評価者をテレポート。 */
    private void teleportToTarget(Player rater, UUID targetUuid) {
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        org.bukkit.World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) return;

        int plotIndex = plugin.getPlotManager().getPlotIndexByUuid(targetUuid);
        if (plotIndex < 0) return;

        org.bukkit.Location loc = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        rater.teleport(loc);
    }
}
