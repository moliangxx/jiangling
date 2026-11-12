package com.example.jiangling.data.command

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.jiangling.util.M

/**
 * 命令实体：一条命令 = 一条 CMD + 一条 ACK。
 *
 * 【字段说明】
 *  - seq       序列号（协议帧里的 SEQ；CMD_SESSION_INIT 固定为 0）
 *  - role      发起对象，相对手机而言："发送" = 手机发出，"接收" = 手机收到
 *  - kindType  KIND_TYPE（TYPE 的低 6 bit = 分区 + 类型），用来对应"命令表"
 *  - content   二进制解码后的中文含义（M.cmdName()），不是原始字节
 *  - code      二进制码源码：TYPE + PAYLOAD 的十六进制（V1.10 没有 LEN 字段），
 *              例如 "D1 64"（KIND / 分区 / 类型都已经编码在 TYPE 里，长度查表决定）
 *  - reply     回复状态中文（M.ackStatusName()）；空 = 还没收到回复
 */
@Entity(tableName = "command")
data class Command(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 序列号 */
    val seq: Int = 0,

    /** 发起对象（相对手机）：M.ROLE_SEND / M.ROLE_ACCEPT */
    val role: String = M.ROLE_SEND,

    /** KIND_TYPE（TYPE 的低 6 bit） */
    val kindType: Int = 0,

    /** 中文含义（由二进制解码得到） */
    val content: String = "",

    /** 二进制码（TYPE + PAYLOAD 的十六进制，V1.10 没有 LEN） */
    val code: String = "",

    /** 回复状态中文 */
    val reply: String = "",

    val sendTime: Long = 0,
    val replyTime: Long? = null
)
