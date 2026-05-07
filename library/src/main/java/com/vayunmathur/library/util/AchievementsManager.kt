package com.vayunmathur.library.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray

abstract class AchievementsManager(val context: Context, jsonContent: String) {
    protected val ds = DataStoreUtils.getInstance(context)
    val achievements: List<Achievement> = parseJson(jsonContent)
    
    private val _newAchievement = MutableStateFlow<Achievement?>(null)
    val newAchievement = _newAchievement.asStateFlow()

    private fun parseJson(json: String): List<Achievement> {
        val list = mutableListOf<Achievement>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Achievement(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    targetProgress = obj.optInt("targetProgress", 1),
                    isSecret = obj.optBoolean("isSecret", false)
                )
            )
        }
        return list
    }

    abstract fun checkExistingAchievements()

    fun onAchievementUnlocked(id: String) {
        val achievement = achievements.find { it.id == id } ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val unlocked = ds.stringSetFlow("achievements_unlocked").first()
            if (!unlocked.contains(id)) {
                ds.addStringToSet("achievements_unlocked", id)
                _newAchievement.value = achievement
            }
        }
    }

    fun onProgressUpdated(id: String, progress: Int) {
        val achievement = achievements.find { it.id == id } ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val currentProgress = ds.getLong("achievement_progress_$id") ?: 0L
            if (progress > currentProgress) {
                ds.setLong("achievement_progress_$id", progress.toLong())
                if (progress >= achievement.targetProgress) {
                    onAchievementUnlocked(id)
                }
            }
        }
    }

    fun getAchievementStatuses(): Flow<List<AchievementStatus>> {
        return ds.stringSetFlow("achievements_unlocked").map { unlocked ->
            achievements.map { achievement ->
                val progress = ds.getLong("achievement_progress_${achievement.id}")?.toInt() ?: 0
                AchievementStatus(
                    achievement = achievement,
                    progress = progress,
                    isUnlocked = unlocked.contains(achievement.id)
                )
            }
        }
    }

    fun dismissNotification() {
        _newAchievement.value = null
    }
}
