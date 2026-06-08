package net.kaisaba.build.util;

import net.kaisaba.build.KaisabaBuild;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * メッセージ送信ユーティリティ。
 *
 * プレイヤー向け: プレフィックスなし（[KB] は見せない）
 * 管理者向け: [KB] プレフィックス付き
 */
public final class MessageUtil {

    /** 管理者向けプレフィックス */
    private static final Component ADMIN_PREFIX = Component.text("[KB] ", NamedTextColor.GOLD);

    private MessageUtil() {}

    // プレイヤー向け（プレフィックスなし） 

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message));
    }

    public static void send(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    public static void broadcast(String message) {
        KaisabaBuild.getInstance().getServer().broadcast(Component.text(message));
    }

    public static void broadcast(Component message) {
        KaisabaBuild.getInstance().getServer().broadcast(message);
    }

    /** アリーナにいる全プレイヤーにメッセージを送る（プレフィックスなし）。 */
    public static void broadcastToArena(String message) {
        String arenaName = KaisabaBuild.getInstance().getConfig().getString("arena-world", "arena");
        Component msg = Component.text(message);
        KaisabaBuild.getInstance().getServer().getWorlds().stream()
            .filter(w -> w.getName().equals(arenaName))
            .flatMap(w -> w.getPlayers().stream())
            .forEach(p -> p.sendMessage(msg));
    }

    // ─── 管理者向け（[KB] プレフィックス付き） ──────────────

    public static void sendAdmin(CommandSender sender, String message) {
        sendAdmin(sender, Component.text(message));
    }

    public static void sendAdmin(CommandSender sender, Component message) {
        sender.sendMessage(ADMIN_PREFIX.append(message));
    }

    public static void broadcastAdmin(String message) {
        KaisabaBuild.getInstance().getServer().broadcast(ADMIN_PREFIX.append(Component.text(message)));
    }
}
