package com.example.jiangling.data.command

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.jiangling.data.command.Command
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {

    @Insert
    suspend fun insert(command: Command): Long

    /** 发送时间 >= time 的命令（"之后"） */
    @Query("SELECT * FROM command WHERE sendTime >= :time ORDER BY sendTime DESC, id DESC")
    fun observeAfter(time: Long): Flow<List<Command>>

    /** 发送时间 <= time 的命令（"之前"） */
    @Query("SELECT * FROM command WHERE sendTime <= :time ORDER BY sendTime DESC, id DESC")
    fun observeBefore(time: Long): Flow<List<Command>>

    /** 只取发送时间，用于生成"哪几天有命令"的日期列表 */
    @Query("SELECT sendTime FROM command ORDER BY sendTime DESC")
    fun observeTimes(): Flow<List<Long>>

    /** 删除发送时间 >= time 的命令（"之后"） */
    @Query("DELETE FROM command WHERE sendTime >= :time")
    suspend fun deleteAfter(time: Long)

    /** 删除发送时间 <= time 的命令（"之前"） */
    @Query("DELETE FROM command WHERE sendTime <= :time")
    suspend fun deleteBefore(time: Long)

    @Query("SELECT * FROM command WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Command?

    /**
     * 按 SEQ 找"还没回复的手机发出命令"。
     * 用于 ACK 到达时匹配命令记录（App 不再维护内存待确认列表）。
     */
    @Query(
        "SELECT * FROM command WHERE seq = :seq AND role = :role AND reply = '' " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun findUnansweredBySeq(seq: Int, role: String): Command?

    /**
     * 按 SEQ 找「还没终结」的手机发出命令：待回复（`''`）或只回了受理（`ACK 0`）。
     *
     * 为什么要单独这一条：`0xC8` 这类命令先回 `ACK 0`（受理）再回 `ACK 3`（完成），
     * 第二次回执必须还能命中同一条记录，否则永远停在「收到」。
     *
     * 为什么不直接按 SEQ 取最新一条：SEQ 只有 1~255，会回绕。终结过的记录
     * （已执行 / 无法识别 / 错误 / 超时）一律不参与匹配，避免把上一轮循环的历史记录盖掉。
     */
    @Query(
        "SELECT * FROM command WHERE seq = :seq AND role = :role " +
            "AND (reply = '' OR reply = :accepted) ORDER BY id DESC LIMIT 1"
    )
    suspend fun findOpenBySeq(seq: Int, role: String, accepted: String): Command?

    @Query("UPDATE command SET reply = :reply, replyTime = :replyTime WHERE id = :id")
    suspend fun updateReply(id: Long, reply: String, replyTime: Long)
}