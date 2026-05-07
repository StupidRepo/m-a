package com.vayunmathur.games.unblockjam.util

import android.content.Context
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.library.util.AchievementsManager

class UnblockJamAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) onAchievementUnlocked("first_level")
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("moves_1000", repository.getTotalMoves())
        onProgressUpdated("undo_master", repository.getUndoCount())
        
        // Check for optimal wins
        // Optimal win is hard to check retroactively without pack data, 
        // but we can check if any existing stat matches optimal.
        // For simplicity, we'll let it trigger on next win.
    }
}
