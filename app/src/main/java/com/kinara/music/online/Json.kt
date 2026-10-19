/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.online

import org.json.JSONArray
import org.json.JSONObject

/** 宽容解析：字段缺失/类型不符回落默认值（平台返回字段不稳，E "HTTP200≠可用" 纪律）。 */
fun JSONObject.optLongSafe(key: String, def: Long = 0L): Long =
    runCatching { when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong() ?: def
        else -> def
    } }.getOrDefault(def)

fun JSONArray.stringListOf(key: String): List<String> =
    (0 until length()).mapNotNull { i -> runCatching { optJSONObject(i)?.optString(key) }.getOrNull() }

fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { i ->
    runCatching { this?.optJSONObject(i) }.getOrNull()
}
