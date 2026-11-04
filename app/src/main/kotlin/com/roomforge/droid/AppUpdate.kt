/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.droid

import com.roomforge.core.AppVersions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub releases 版本信息。MiruPlay 同链路的最小版：无多渠道、无 manifest、无第三方库。 */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val url: String,
    val size: Long
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val done: Long, val total: Long) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Failed(val msg: String) : UpdateState
}

object AppUpdate {
    const val REPO = "ModerRAS/RoomForgeDroid"
    private const val TIMEOUT_MS = 12_000

    /** 有新版返回 UpdateInfo，已是最新返回 null，网络或解析失败抛异常。 */
    fun check(current: String): UpdateInfo? {
        val json = get("https://api.github.com/repos/$REPO/releases/latest")
        val tag = stringField(json, "tag_name") ?: error("读不到 tag_name")
        if (!AppVersions.isNewer(current, tag)) return null
        val (apkName, apkUrl) = firstApkAsset(json) ?: error("release 里没有 apk")
        val notes = stringField(json, "body").orEmpty()
            .replace("\\r\\n", "\n").replace("\\n", "\n")
            .replace("\\\"", "\"").trim().take(1200)
        return UpdateInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = notes.ifBlank { "（无更新说明）" },
            url = apkUrl,
            size = 0L
        ).also { check(apkName.endsWith(".apk")) }
    }

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = 60_000
            setRequestProperty("User-Agent", "RoomForgeDroid")
            setRequestProperty("Accept", "application/octet-stream")
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val total = conn.contentLengthLong
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            dest.delete()
            error("下载失败（HTTP $code）")
        }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub API 必须带 UA，否则 403。
            setRequestProperty("User-Agent", "RoomForgeDroid")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val code = conn.responseCode
        if (code == 404) error("还没有发布过版本")
        if (code == 403) error("GitHub 限流，稍后再试")
        if (code !in 200..299) error("检查失败（HTTP $code）")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun stringField(json: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return m.groupValues[1]
    }

    /** 按出现顺序配对 name/browser_download_url，取第一个 .apk。 */
    private fun firstApkAsset(json: String): Pair<String, String>? {
        val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val urls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        // release 名和 asset 名都叫 name：只配对 assets 数组里的（url 数量即 asset 数量，取末尾对齐）。
        val offset = names.size - urls.size
        urls.forEachIndexed { i, url ->
            val name = names.getOrNull(offset + i) ?: return@forEachIndexed
            if (name.endsWith(".apk", ignoreCase = true)) return name to url
        }
        return null
    }
}
