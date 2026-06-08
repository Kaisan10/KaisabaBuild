package net.kaisaba.build.listener;

import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * サーバー全体のルールを強制するリスナー。
 * - 全ワールドをピースフルに維持
 * - PvP を全面禁止
 */
public class ServerRuleListener implements Listener {

    private final JavaPlugin plugin;

    public ServerRuleListener(JavaPlugin plugin) {
        this.plugin = plugin;
        // 登録時点で既にロード済みのワールドをピースフルに設定
        for (World world : plugin.getServer().getWorlds()) {
            world.setDifficulty(Difficulty.PEACEFUL);
        }
    }

    /** 新たにロードされたワールドもピースフルに設定する。 */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        event.getWorld().setDifficulty(Difficulty.PEACEFUL);
    }

    /** プレイヤー間ダメージをキャンセルして PvP を禁止する。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }
}
