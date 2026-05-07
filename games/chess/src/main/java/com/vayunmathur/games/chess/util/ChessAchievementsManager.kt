package com.vayunmathur.games.chess.util

import android.content.Context
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils

class ChessAchievementsManager(
    context: Context,
    json: String
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val ds = DataStoreUtils.getInstance(context)
        val wins = ds.getLong("chess_wins_count") ?: 0L
        if (wins > 0) onAchievementUnlocked("first_mate")
        onProgressUpdated("win_10", wins.toInt())
    }
}
