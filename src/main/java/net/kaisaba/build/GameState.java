package net.kaisaba.build;

/**
 * ゲームの状態を表す列挙型。
 * プラグイン全体でこの enum を参照してイベント処理を切り替える。
 */
public enum GameState {
    /** 待機中。キュー受付可能。 */
    IDLE,
    /** カウントダウン中（30秒）。 */
    COUNTDOWN,
    /** 建築フェーズ（20分）。 */
    BUILDING,
    /** 評価フェーズ（5分）。 */
    RATING
}
