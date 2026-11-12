package com.example.jiangling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.jiangling.data.command.Command
import com.example.jiangling.data.command.CommandDao
import com.example.jiangling.data.log.LogDao
import com.example.jiangling.data.log.Log
import com.example.jiangling.util.M

/**
 * Room 数据库——全局唯一实例。
 *
 * 两张表：
 *   - log：板子推送的日志、错误、操作记录
 *   - command：发送过的命令 + 板子 ACK 回复
 *
 * version = 5 表示数据库结构版本；改动表结构后需要升级 version 号。
 * 当前用 fallbackToDestructiveMigration：升级时直接清空重建（开发期够用，
 * 正式版需要写 Migration 保留用户数据）。
 *
 * 单例实现用双重检查锁（DCL），@Volatile instance 保证多线程可见性。
 */
@Database(entities = [Log::class, Command::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    /** 日志表 DAO */
    abstract fun logDao(): LogDao

    /** 命令表 DAO */
    abstract fun commandDao(): CommandDao

    companion object {
        /** 单例实例，@Volatile 保证多线程间立即可见 */
        @Volatile
        private var instance: AppDatabase? = null

        /** 首次调用创建数据库，后续全部返回同一个实例 */
        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                M.DB_NAME
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}