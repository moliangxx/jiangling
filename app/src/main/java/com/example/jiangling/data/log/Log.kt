package com.example.jiangling.data.log

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日志实体。
 * time 由手机端生成，level 使用 M.LOG_LEVEL_DEBUG / INFO / WARNING / ERROR。
 */
@Entity(tableName = "log")
data class Log(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val time: Long = 0,
    val content: String = "",
    val level: String = "D"
)