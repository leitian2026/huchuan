package com.hulian.transfer

import android.net.Uri
import java.util.UUID

enum class Kind { TEXT, FILE, APP }
enum class MsgState { SENDING, RECEIVING, DONE, FAILED }

data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val online: Boolean = false,
    val lastSeen: Long = 0L
)

data class Msg(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val kind: Kind,
    val time: Long,
    val text: String = "",      // 文字内容
    val name: String = "",      // 文件名 / 应用名
    val file: String = "",      // 实际文件名（应用为 xxx.apk / xxx.apks）
    val size: Long = 0L,
    val done: Long = 0L,
    val state: MsgState = MsgState.DONE,
    val uri: String = "",       // 收到的文件位置 / 待发送文件来源
    val pkg: String = "",
    val ver: String = "",
    val error: String = "",
    val read: Boolean = true    // 收到的消息是否已读（用于未读数）
)

/** 从系统“分享”菜单收到的待发送内容 */
data class Share(val uris: List<Uri>, val text: String?)

fun now() = System.currentTimeMillis()
fun newId() = UUID.randomUUID().toString()
