package com.vayunmathur.library.util

import kotlinx.serialization.Serializable

@Serializable
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val targetProgress: Int = 1,
    val isSecret: Boolean = false
)

data class AchievementStatus(
    val achievement: Achievement,
    val progress: Int,
    val isUnlocked: Boolean
)
