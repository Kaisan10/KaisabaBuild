package net.kaisaba.build;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * freecam 対策 - チャンクパケットフィルター
 *
 * - enabled=true（建築フェーズ）: 自分プロット以外のチャンクパケットをキャンセル
 * - enabled=false（評価フェーズ）: キャンセルを停止し、全プロットのチャンクを refreshChunk で再送
 *   ※ 0.5ブロックずらしテレポートはチャンク境界を越えないため再送が発生しない → refreshChunk を使用
 */
public class AntiFreecam extends PacketListenerAbstract {

    // PlotManager と同じ定数
    private static final int PLOT_SIZE  = 64;
    private static final int WALL_WIDTH = 2;
    private static final int PITCH      = 66;
    private static final int COLS       = 4;

    private final PlotManager plotManager;

    private volatile boolean enabled = true;

    public AntiFreecam(KaisabaBuild plugin, PlotManager plotManager) {
        super(PacketListenerPriority.NORMAL);
        this.plotManager = plotManager;
    }

    /**
     * enabled を切り替える。
     *
     * false にする場合（評価フェーズ開始時）:
     *   建築中にキャンセルされて届いていないチャンクを再送するため、
     *   全プロットのチャンクを refreshChunk で再送する。
     *   refreshChunk はそのチャンクを現在見ている全プレイヤーに再送するため、
     *   特定プレイヤーへの絞り込みは不要。
     *
     * @param enabled         有効/無効
     * @param activePlayers   対象プレイヤー UUID リスト（false にする場合に使用）
     */
    public void setEnabled(boolean enabled, List<UUID> activePlayers) {
        this.enabled = enabled;

        if (!enabled && activePlayers != null) {
            // 評価フェーズ開始: enabled を false にした直後に全プロットのチャンクを再送
            // 0.5ブロックずらしテレポートはチャンク境界を越えないため機能しない。
            // refreshChunk はそのチャンクを見ている全プレイヤーへ再送する。
            Player sample = null;
            for (UUID uuid : activePlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) { sample = p; break; }
            }
            if (sample == null) return;
            org.bukkit.World world = sample.getWorld();

            // 全16プロット分のチャンクを再送（壁幅込みの範囲）
            int totalCols = COLS;
            int totalRows = COLS; // 4×4
            for (int row = 0; row < totalRows; row++) {
                for (int col = 0; col < totalCols; col++) {
                    int originX = col * PITCH;
                    int originZ = row * PITCH;
                    // プロット + 壁幅を含むブロック範囲をチャンク単位に変換
                    int minChunkX = (originX - WALL_WIDTH) >> 4;
                    int maxChunkX = (originX + PLOT_SIZE + WALL_WIDTH - 1) >> 4;
                    int minChunkZ = (originZ - WALL_WIDTH) >> 4;
                    int maxChunkZ = (originZ + PLOT_SIZE + WALL_WIDTH - 1) >> 4;
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            world.refreshChunk(cx, cz);
                        }
                    }
                }
            }
        }
    }

    /** 互換性のためシグネチャなし版も残す（enabledをtrueに戻す場合など） */
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, null);
    }

    // ─────────────────────────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) return;
        if (!enabled) return;

        // getPlayer() は CHUNK_DATA で null になることがある → getUser().getUUID() 経由
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;

        // プロット未割り当てはスルー
        int plotIndex = plotManager.getPlotIndex(player);
        if (plotIndex == -1) return;

        // チャンク座標を取得
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        int chunkX = wrapper.getColumn().getX();
        int chunkZ = wrapper.getColumn().getZ();

        // チャンクのブロック範囲 (inclusive)
        int chunkBlockMinX = chunkX << 4;
        int chunkBlockMaxX = chunkBlockMinX + 15;
        int chunkBlockMinZ = chunkZ << 4;
        int chunkBlockMaxZ = chunkBlockMinZ + 15;

        // プレイヤーのプロットの許可ブロック範囲（壁幅のみ含む）
        int col       = plotIndex % COLS;
        int row       = plotIndex / COLS;
        int allowMinX = col * PITCH - WALL_WIDTH;
        int allowMaxX = col * PITCH + PLOT_SIZE + WALL_WIDTH - 1;
        int allowMinZ = row * PITCH - WALL_WIDTH;
        int allowMaxZ = row * PITCH + PLOT_SIZE + WALL_WIDTH - 1;

        // チャンクが許可範囲と重なるか
        boolean overlapsX = chunkBlockMaxX >= allowMinX && chunkBlockMinX <= allowMaxX;
        boolean overlapsZ = chunkBlockMaxZ >= allowMinZ && chunkBlockMinZ <= allowMaxZ;

        if (!overlapsX || !overlapsZ) {
            event.setCancelled(true);
        }
    }
}