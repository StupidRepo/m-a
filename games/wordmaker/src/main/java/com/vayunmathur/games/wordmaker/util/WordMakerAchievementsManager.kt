package com.vayunmathur.games.wordmaker.util

import android.content.Context
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WordMakerAchievementsManager(
    context: Context,
    json: String,
    private val levelDataStore: LevelDataStore
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val found = levelDataStore.foundWords.first()
            if (found.isNotEmpty()) onAchievementUnlocked("first_word")
            val currentLevel = levelDataStore.currentLevel.first()
            if (currentLevel > 861) onAchievementUnlocked("manual_levels_done")
            if (currentLevel >= 50) onAchievementUnlocked("level_50")
            if (currentLevel >= 100) onAchievementUnlocked("level_100")
            if (currentLevel >= 500) onAchievementUnlocked("level_500")
            val bonus = levelDataStore.bonusWords.first()
            onProgressUpdated("bonus_hunter", bonus.size)
        }
    }
}
