package net.kaisaba.build;

import net.kaisaba.build.command.KaisabaBuildCommand;
import net.kaisaba.build.listener.BuildListener;
import net.kaisaba.build.listener.LobbyListener;
import net.kaisaba.build.listener.RatingListener;
import net.kaisaba.build.listener.ArenaProtectionListener;
import net.kaisaba.build.listener.ServerRuleListener;
import net.kaisaba.build.listener.WorldEditRestrictor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * KaisabaBuild メインクラス。
 * マネージャーの生成・リスナー登録・コマンド登録を行う。
 */
public class KaisabaBuild extends JavaPlugin {

    private static KaisabaBuild instance;

    private GameManager gameManager;
    private PlotManager plotManager;
    private QueueManager queueManager;
    private RatingManager ratingManager;
    private PenaltyManager penaltyManager;
    private WorldEditRestrictor worldEditRestrictor;

    @Override
    public void onEnable() {
        instance = this;

        // デフォルト config を保存
        saveDefaultConfig();

        // マネージャーを依存順に生成
        plotManager = new PlotManager(this);
        ratingManager = new RatingManager(this);
        penaltyManager = new PenaltyManager();
        gameManager = new GameManager(this);
        queueManager = new QueueManager(this, gameManager);

        // リスナー登録
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(new BuildListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new RatingListener(this), this);
        getServer().getPluginManager().registerEvents(new ServerRuleListener(this), this);

        // WorldEdit の EditSessionEvent にマスク制限を登録
        worldEditRestrictor = new WorldEditRestrictor(this);
        worldEditRestrictor.register();

        getCommand("kb").setExecutor(new KaisabaBuildCommand(this));

        getLogger().info("Hello!!!!!!!!!!!! I'm KaisabaBuild!!!!!!!!!!!");
    }

    @Override
    public void onDisable() {
        if (worldEditRestrictor != null) {
            worldEditRestrictor.unregister();
        }
        getLogger().info("KaisabaBuild desu!!!!!!! Sayonara!!!!!!!!!!!!!!");
    }

    public static KaisabaBuild getInstance() { return instance; }

    public GameManager getGameManager() { return gameManager; }
    public PlotManager getPlotManager() { return plotManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public RatingManager getRatingManager() { return ratingManager; }
    public PenaltyManager getPenaltyManager() { return penaltyManager; }
}
