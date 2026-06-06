package net.kaisaba.build.command;

import net.kaisaba.build.GameState;
import net.kaisaba.build.KaisabaBuild;
import net.kaisaba.build.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /kb コマンドハンドラ。
 * 全メッセージは管理者向けなので [KB] プレフィックス付き（sendAdmin）。
 *
 * /kb setlobby  - 現在地をロビースポーンに設定
 * /kb setup     - アリーナ床・壁を初期配置
 * /kb start     - 強制ゲーム開始（テスト用）
 * /kb stop      - 強制ゲーム終了
 * /kb reset     - アリーナ全プロットリセット
 */
public class KaisabaBuildCommand implements CommandExecutor {

    private final KaisabaBuild plugin;

    public KaisabaBuildCommand(KaisabaBuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("kaisababuild.admin")) {
            MessageUtil.sendAdmin(sender, Component.text("権限がありません。", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "setlobby" -> cmdSetLobby(sender);
            case "setup"    -> cmdSetup(sender);
            case "start"    -> cmdStart(sender);
            case "stop"     -> cmdStop(sender);
            case "reset"    -> cmdReset(sender);
            case "penalty"  -> cmdPenalty(sender, args);
            default -> { sendHelp(sender); yield true; }
        };
    }

    private boolean cmdSetLobby(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendAdmin(sender, Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return true;
        }

        plugin.getConfig().set("lobby-world", player.getWorld().getName());
        plugin.getConfig().set("lobby-spawn.x", player.getLocation().getX());
        plugin.getConfig().set("lobby-spawn.y", player.getLocation().getY());
        plugin.getConfig().set("lobby-spawn.z", player.getLocation().getZ());
        plugin.getConfig().set("lobby-spawn.yaw", (double) player.getLocation().getYaw());
        plugin.getConfig().set("lobby-spawn.pitch", (double) player.getLocation().getPitch());
        plugin.saveConfig();

        MessageUtil.sendAdmin(sender, Component.text("ロビースポーンを設定しました。", NamedTextColor.GREEN));
        return true;
    }

    private boolean cmdSetup(CommandSender sender) {
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = plugin.getServer().getWorld(arenaName);
        if (arenaWorld == null) {
            MessageUtil.sendAdmin(sender, Component.text(
                "アリーナワールド '" + arenaName + "' が見つかりません。", NamedTextColor.RED));
            return true;
        }

        MessageUtil.sendAdmin(sender, Component.text("セットアップを開始します…", NamedTextColor.YELLOW));
        plugin.getPlotManager().setupArena(arenaWorld);
        MessageUtil.sendAdmin(sender, Component.text("セットアップ完了！", NamedTextColor.GREEN));
        return true;
    }

    private boolean cmdStart(CommandSender sender) {
        if (plugin.getGameManager().getState() != GameState.IDLE) {
            MessageUtil.sendAdmin(sender, Component.text("ゲームは既に進行中です。", NamedTextColor.RED));
            return true;
        }

        List<UUID> queue = new ArrayList<>(plugin.getQueueManager().getQueue());
        if (queue.isEmpty()) {
            MessageUtil.sendAdmin(sender, Component.text("キューにプレイヤーがいません。", NamedTextColor.RED));
            return true;
        }

        plugin.getQueueManager().clearQueue();
        plugin.getGameManager().startGame(queue);
        MessageUtil.sendAdmin(sender, Component.text("ゲームを強制開始しました。", NamedTextColor.GREEN));
        return true;
    }

    private boolean cmdStop(CommandSender sender) {
        if (plugin.getGameManager().getState() == GameState.IDLE) {
            MessageUtil.sendAdmin(sender, Component.text("現在ゲームは進行していません。", NamedTextColor.YELLOW));
            return true;
        }
        plugin.getGameManager().forceStop();
        MessageUtil.sendAdmin(sender, Component.text("ゲームを強制終了しました。", NamedTextColor.GREEN));
        return true;
    }

    private boolean cmdReset(CommandSender sender) {
        String arenaName = plugin.getConfig().getString("arena-world", "arena");
        World arenaWorld = plugin.getServer().getWorld(arenaName);
        if (arenaWorld == null) {
            MessageUtil.sendAdmin(sender, Component.text(
                "アリーナワールド '" + arenaName + "' が見つかりません。", NamedTextColor.RED));
            return true;
        }

        MessageUtil.sendAdmin(sender, Component.text("全プロットのリセットを開始します…", NamedTextColor.YELLOW));
        for (int i = 0; i < 16; i++) {
            final int plotIndex = i;
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getPlotManager().resetPlot(plotIndex, arenaWorld),
                (long) i * 5L
            );
        }
        MessageUtil.sendAdmin(sender, Component.text("リセットをスケジュールしました。", NamedTextColor.GREEN));
        return true;
    }

    private boolean cmdPenalty(CommandSender sender, String[] args) {
        // /kb penalty clear <player>
        if (args.length < 3 || !args[1].equalsIgnoreCase("clear")) {
            MessageUtil.sendAdmin(sender, Component.text(
                "使い方: /kb penalty clear <プレイヤー名>", NamedTextColor.YELLOW));
            return true;
        }

        String targetName = args[2];
        // オフラインプレイヤーにも対応
        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            MessageUtil.sendAdmin(sender, Component.text(
                "プレイヤー '" + targetName + "' が見つかりません。", NamedTextColor.RED));
            return true;
        }

        boolean cleared = plugin.getPenaltyManager().clearPenalty(target.getUniqueId());
        if (cleared) {
            MessageUtil.sendAdmin(sender, Component.text(
                target.getName() + " のペナルティを解除しました。", NamedTextColor.GREEN));
            // オンラインなら本人にも通知
            Player online = plugin.getServer().getPlayer(target.getUniqueId());
            if (online != null) {
                online.sendMessage(Component.text(
                    "管理者によってペナルティが解除されました。", NamedTextColor.GREEN));
            }
        } else {
            MessageUtil.sendAdmin(sender, Component.text(
                target.getName() + " にはペナルティがありません。", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("─── KaisabaBuild コマンド ───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/kb setlobby          ", NamedTextColor.YELLOW)
            .append(Component.text("現在地をロビースポーンに設定", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/kb setup             ", NamedTextColor.YELLOW)
            .append(Component.text("アリーナの床・壁を初期配置", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/kb start             ", NamedTextColor.YELLOW)
            .append(Component.text("ゲームを強制開始（テスト用）", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/kb stop              ", NamedTextColor.YELLOW)
            .append(Component.text("ゲームを強制終了", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/kb reset             ", NamedTextColor.YELLOW)
            .append(Component.text("アリーナ全プロットをリセット", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/kb penalty clear <名前>", NamedTextColor.YELLOW)
            .append(Component.text("プレイヤーの早期離脱ペナルティを解除", NamedTextColor.WHITE)));
    }
}
