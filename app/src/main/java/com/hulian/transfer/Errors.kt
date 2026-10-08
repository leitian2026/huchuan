package com.hulian.transfer

/** 异常 → 中文提示 */
fun friendlyError(e: Throwable): String = ErrorText.friendly(e.message, e.javaClass.name)

/** 已保存的错误文字 → 中文提示（旧版本留下的英文错误也能显示成中文） */
fun friendlyError(raw: String): String = ErrorText.friendly(raw, "")

/** 给“详细信息”用：异常类名 + 信息，连同原因链（排查问题时发给开发者） */
fun techDetail(e: Throwable): String {
    val sb = StringBuilder()
    var t: Throwable? = e
    var n = 0
    while (t != null && n < 4) {
        if (n > 0) sb.append("\n← ")
        sb.append(t.javaClass.name)
        if (!t.message.isNullOrEmpty()) sb.append(": ").append(t.message)
        t = t.cause
        n++
    }
    return sb.toString()
}
