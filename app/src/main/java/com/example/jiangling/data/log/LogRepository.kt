package com.example.jiangling.data.log

import kotlinx.coroutines.flow.Flow

/**
 * 日志仓库——日志只有一个来源：小车通过 WiFi 回程（TCP 8080）推过来。
 * App 端只做三件事：存下来（add）、按区间查（between）、按区间删（deleteBetween）。
 */
class LogRepository(private val dao: LogDao) {

    /** 某几天有日志（时间戳列表），给"按时间查询"弹窗用 */
    val times: Flow<List<Long>> = dao.observeTimes()

    /**
     * 按时间查询。
     *
     * @param time    基准时间
     * @param isAfter true → 查 time 之后（time >= 基准）；false → 查 time 之前（time <= 基准）
     */
    fun query(time: Long, isAfter: Boolean): Flow<List<Log>> =
        if (isAfter) dao.observeAfter(time) else dao.observeBefore(time)

    /** 存一条小车推来的日志 */
    suspend fun add(log: Log) = dao.insert(log)

    /** 批量存：一个 LOG 帧固定 8 条记录，一次事务写完 */
    suspend fun addAll(logs: List<Log>) = dao.insertAll(logs)

    /** 删除当前列表对应的那一段（规则和 query 完全一致） */
    suspend fun delete(time: Long, isAfter: Boolean) =
        if (isAfter) dao.deleteAfter(time) else dao.deleteBefore(time)
}
