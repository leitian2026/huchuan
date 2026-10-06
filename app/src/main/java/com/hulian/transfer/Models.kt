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
    val lastSeen: Long = 0L,
    val fp: String = "",        // 配对时记下的对方证书指纹（身份）；为空表示尚未配对，不能收发
    val iHost: Boolean? = null  // 首次配对时本机是不是开二维码的一方：true=以后由本机建热点，false=本机去连对方的热点；null=旧数据没记录
) {
    val paired: Boolean get() = fp.isNotEmpty()
}

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

/** 配对确认弹窗的数据：isHost=本机是被扫的一方（要核对验证码并点同意）；否则是扫码的一方（只显示验证码，等对方确认） */
class PairPrompt(
    val peerId: String,
    val peerName: String,
    val code: String,
    val isHost: Boolean,
    val decision: java.util.concurrent.CompletableFuture<Boolean>?
)
