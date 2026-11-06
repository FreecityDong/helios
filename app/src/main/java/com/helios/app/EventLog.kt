package com.helios.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant

/** Local structured events only; bounded to two files, no audio, video or identifiers. */
class EventLog(context: Context) {
    companion object {
        private val ioLock=Any()
        private val recent=ArrayDeque<String>()
        fun recentLines(limit:Int):List<String> = synchronized(ioLock) {recent.takeLast(limit)}
    }
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }
    private val current = File(directory, "events.jsonl")
    private val previous = File(directory, "events.previous.jsonl")

    @Synchronized fun append(event: String, fields: Map<String, Any> = emptyMap()) = synchronized(ioLock) {
        if (current.length() >= 512 * 1024) {
            check(!previous.exists() || previous.delete()) { "无法清理旧日志" }
            check(current.renameTo(previous)) { "无法轮转日志" }
        }
        val row = JSONObject().put("schemaVersion", 1).put("time", Instant.now().toString())
            .put("event", event).put("fields", JSONObject(fields))
        current.appendText("$row\n", Charsets.UTF_8)
        recent.addLast(row.toString())
        while(recent.size>100) recent.removeFirst()
    }

    @Synchronized fun exportTo(output: OutputStream) = synchronized(ioLock) {
        listOf(previous, current).filter { it.exists() }.forEach { file ->
            file.inputStream().use { it.copyTo(output) }
        }
    }
}
