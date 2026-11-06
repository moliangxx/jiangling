package com.example.jiangling.data.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 日志表 DAO。
 *
 * Room 注解说明：
 * - @Dao：告诉 Room 这是数据访问对象，编译期会生成 SQL 实现
 * - @Insert(onConflict = REPLACE) 默认是 REPLACE，这里没写冲突策略（id 自增，不会冲突）
 * - suspend 函数：在 IO 线程执行数据库操作，调用方需要在协程里
 * - 返回 Flow 的函数：Room 监听表变化，有新数据自动发事件
 */
@Dao
interface LogDao {

    /** 新增一条日志记录（日志只能由小车推送，App 端不自己造） */
    @Insert
    suspend fun insert(log: Log): Long

    /** 批量新增（一个 LOG 帧解析出多条记录时，一次事务写完） */
    @Insert
    suspend fun insertAll(logs: List<Log>): List<Long>

    /** 时间 >= time 的日志（"之后"） */
    @Query("SELECT * FROM log WHERE time >= :time ORDER BY time DESC, id DESC")
    fun observeAfter(time: Long): Flow<List<Log>>

    /** 时间 <= time 的日志（"之前"） */
    @Query("SELECT * FROM log WHERE time <= :time ORDER BY time DESC, id DESC")
    fun observeBefore(time: Long): Flow<List<Log>>

    /** 只取时间戳，用于生成"哪几天有日志"的日期列表 */
    @Query("SELECT time FROM log ORDER BY time DESC")
    fun observeTimes(): Flow<List<Long>>

    /** 删除时间 >= time 的日志（"之后"） */
    @Query("DELETE FROM log WHERE time >= :time")
    suspend fun deleteAfter(time: Long)

    /** 删除时间 <= time 的日志（"之前"） */
    @Query("DELETE FROM log WHERE time <= :time")
    suspend fun deleteBefore(time: Long)
}