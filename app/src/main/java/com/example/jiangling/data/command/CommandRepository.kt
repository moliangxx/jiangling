package com.example.jiangling.data.command

import com.example.jiangling.data.command.Command
import com.example.jiangling.util.M
import kotlinx.coroutines.flow.Flow

/**
 * 命令仓库。
 * 命令记录由 App 本地建（一条 CMD + 一条 ACK 才完整），
 * 查询和删除都按时间区间，删除也只保留一个传参方法。
 */
class CommandRepository(private val dao: CommandDao) {

    /**
     * 按时间查询。
     *
     * @param time    基准时间
     * @param isAfter true → 查 time 之后（sendTime >= 基准）；false → 之前（sendTime <= 基准）
     */
    fun query(time: Long, isAfter: Boolean): Flow<List<Command>> =
        if (isAfter) dao.observeAfter(time) else dao.observeBefore(time)

    /** 有命令的日期（时间戳列表），给"按时间查询"弹窗用 */
    val times: Flow<List<Long>> = dao.observeTimes()

    /** 新增一条命令，返回自增 id */
    suspend fun add(command: Command): Long = dao.insert(command)

    /** 按 id 查询单条命令 */
    suspend fun findById(id: Long): Command? = dao.findById(id)

    /** 收到板子 ACK 后更新回复内容和时间 */
    suspend fun updateReply(id: Long, reply: String, replyTime: Long) =
        dao.updateReply(id, reply, replyTime)

    /** 按 SEQ 找还没回复的手机发出命令（ACK 匹配用） */
    suspend fun findUnansweredBySeq(seq: Int): Command? =
        dao.findUnansweredBySeq(seq, M.ROLE_SEND)

    /**
     * 按 SEQ 找「还没终结」的手机发出命令（待回复或只回了受理）。
     *
     * 终结态（已执行 / 无法识别 / 错误 / 超时）不参与匹配：SEQ 会回绕，
     * 这样上一轮循环的历史记录不会被新一轮的回执改写。
     */
    suspend fun findOpenBySeq(seq: Int): Command? =
        dao.findOpenBySeq(seq, M.ROLE_SEND, M.ackStatusName(M.ACK_ST_RECEIVED))

    /** 删除当前列表对应的那一段（规则和 query 完全一致） */
    suspend fun delete(time: Long, isAfter: Boolean) =
        if (isAfter) dao.deleteAfter(time) else dao.deleteBefore(time)
}
