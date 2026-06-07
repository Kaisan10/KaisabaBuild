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
            MessageUtil.send(rater, Component.text("評価する建築がありません。どうやって始めたの？", NamedTextColor.YELLOW));
            return;
        }

        // 全建築を評価済みかチェック（自分以外の全員が対象）
        Map<UUID, Integer> myRatings = ratings.get(rater.getUniqueId());
        List<UUID> rateable = targets.stream()
            .filter(u -> !u.equals(rater.getUniqueId()))
            .toList();
        boolean allRated = rateable.stream().allMatch(myRatings::containsKey);
        if (allRated && !rateable.isEmpty()) {
            rater.closeInventory();
            MessageUtil.send(rater, Component.text(
                "全ての建築の評価が完了したら評価メニューから完了ボタンを押してください。",
                NamedTextColor.GREEN
            ));
            return;
        }

        int pointer = pointers.getOrDefault(rater.getUniqueId(), 0);
        pointer = pointer % targets.size();
        pointers.put(rater.getUniqueId(), pointer);

        UUID targetUuid = targets.get(pointer);
        Player target = Bukkit.getPlayer(targetUuid);
        String targetName = (target != null) ? target.getName() : targetUuid.toString().substring(0, 8);
        boolean isSelf = targetUuid.equals(rater.getUniqueId());

        // 現在の評価値（未評価なら 0）
        int currentScore = myRatings.getOrDefault(targetUuid, 0);

        Inventory inv = Bukkit.createInventory(null, 27,
            Component.text(GUI_TITLE, NamedTextColor.DARK_PURPLE));

        // スロット 4: 現在の対象名
        inv.setItem(4, InventoryUtil.makeItem(
            isSelf ? Material.PLAYER_HEAD : Material.WRITTEN_BOOK,
            Component.text(targetName + " の建築" + (isSelf ? "  (自分)" : ""), NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("(" + (pointer + 1) + "/" + targets.size() + ")", NamedTextColor.GRAY)
        ));

        // スロット 9: 前へ
        inv.setItem(9, InventoryUtil.makeItem(
            Material.ARROW,
            Component.text("← 前の建築", NamedTextColor.AQUA)
        ));

        // スロット 11-15: ★1 〜 ★5（中央に配置）
        for (int star = 1; star <= 5; star++) {
            Material mat = isSelf ? Material.GRAY_DYE
                : (star <= currentScore) ? Material.GOLD_NUGGET : Material.IRON_NUGGET;
            Component name = isSelf
                ? Component.text("自分の建築は評価できません", NamedTextColor.GRAY)
                : Component.text("★".repeat(star), NamedTextColor.GOLD);
            inv.setItem(9 + star, InventoryUtil.makeItem(mat, name));
        }

        // スロット 17: 次へ
        inv.setItem(17, InventoryUtil.makeItem(
            Material.ARROW,
            Component.text("次の建築 →", NamedTextColor.AQUA)
        ));

        rater.openInventory(inv);
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
        int pointer = pointers.getOrDefault(raterUuid, 0) % targets.size();
        UUID targetUuid = targets.get(pointer);
        boolean isSelf = targetUuid.equals(raterUuid);

        // ★1〜★5 (slot 10-14)
        if (slot >= 10 && slot <= 14) {
            if (isSelf) {
                MessageUtil.send(rater, Component.text("自分の建築は評価できません。", NamedTextColor.RED));
                return false;
            }
            int score = slot - 9; // 1〜5
            ratings.get(raterUuid).put(targetUuid, score);
            // 評価後に次へ進み、openRatingGui内で全評価完了チェック
            pointers.put(raterUuid, (pointer + 1) % targets.size());
            openRatingGui(rater);
            return true;
        }

        // ← 前 (slot 9)
        if (slot == 9) {
            pointers.put(raterUuid, (pointer - 1 + targets.size()) % targets.size());
            openRatingGui(rater);
            return true;
        }

        // 次 → (slot 17)
        if (slot == 17) {
            pointers.put(raterUuid, (pointer + 1) % targets.size());
            openRatingGui(rater);
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
        int count = 0;
        double prevScore = Double.MAX_VALUE;
        for (Map.Entry<UUID, Double> entry : sorted) {
            count++;
            // 前の点数と異なる場合のみ順位を更新（同点なら同順位）
            if (entry.getValue() < prevScore) {
                rank = count;
                prevScore = entry.getValue();
            }
            Player p = Bukkit.getPlayer(entry.getKey());
            String name = (p != null) ? p.getName() : entry.getKey().toString().substring(0, 8);
            MessageUtil.broadcast(Component.text(
                rank + "位: " + name + "  平均 " + String.format("%.1f", entry.getValue()) + " 点",
                rank == 1 ? NamedTextColor.GOLD : rank == 2 ? NamedTextColor.GRAY : NamedTextColor.WHITE
            ));
        }
    }

    // ─── 内部ユーティリティ ─────────────────────────────────

    /** 全参加者リストを返す（自分を含む）。自分の建築は評価不可だが選択肢には表示する。 */
    private List<UUID> getTargetsFor(UUID raterUuid) {
        return new ArrayList<>(participants);
    }

    /** 被評価者のプロットスポーンへ評価者をテレポート（← / → ボタン用）。 */
    public void teleportToTarget(Player rater, UUID targetUuid) {
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        org.bukkit.World arenaWorld = Bukkit.getWorld(arenaName);
        if (arenaWorld == null) return;

        int plotIndex = plugin.getPlotManager().getPlotIndexByUuid(targetUuid);
        if (plotIndex < 0) return;

        org.bukkit.Location loc = plugin.getPlotManager().getPlotSpawn(plotIndex, arenaWorld);
        rater.teleport(loc);
    }
}
