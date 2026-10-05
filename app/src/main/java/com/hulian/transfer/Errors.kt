package com.hulian.transfer

/** 异常 → 中文提示 */
fun friendlyError(e: Throwable): String = ErrorText.friendly(e.message, e.javaClass.name)

/** 已保存的错误文字 → 中文提示（旧版本留下的英文错误也能显示成中文） */
fun friendlyError(raw: String): String = ErrorText.friendly(raw, "")
