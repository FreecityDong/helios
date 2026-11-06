package com.helios.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class SceneStatus(val message: String = "环境理解未启用", val summary: String = "", val busy: Boolean = false)
object SceneStatusStore {
    private val mutable = MutableStateFlow(SceneStatus())
    val status = mutable.asStateFlow()
    fun update(value: SceneStatus) { mutable.value = value }
}
data class SceneConfig(val url: String, val token: String, val enabled: Boolean, val automatic: Boolean, val testMode: Boolean = false) {
    val direct: Boolean get() = url == SceneProtocol.DIRECT_URL
    companion object {
        private fun validate(context: Context, url: String, token: String) {
            val uri = URI(url)
            val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            require(uri.userInfo == null && uri.fragment == null && uri.query == null && uri.host != null)
            require(uri.scheme == "https" || debug && uri.scheme == "http" && uri.host == "127.0.0.1")
            require(token.length in 24..256 && token.none(Char::isISOControl))
        }
        fun read(context: Context): SceneConfig = runCatching {
            val obj = JSONObject(File(context.filesDir, "scene-config.json").readText())
            val url = obj.getString("url"); val token = obj.getString("token")
            if (obj.optBoolean("enabled", false)) validate(context, url, token)
            SceneConfig(url, token, obj.optBoolean("enabled", false), obj.optBoolean("automatic", false),
                context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && obj.optBoolean("test_mode", false))
        }.getOrDefault(SceneConfig("", "", false, false))
        fun save(context: Context, url: String, token: String, enabled: Boolean, automatic: Boolean, testMode: Boolean = false) {
            val cleanUrl = url.trim(); val cleanToken = token.trim()
            if (enabled) validate(context, cleanUrl, cleanToken)
            File(context.filesDir, "scene-config.json").writeText(JSONObject().put("url", cleanUrl).put("token", cleanToken)
                .put("enabled", enabled).put("automatic", automatic).put("test_mode", testMode).toString())
        }
    }
}

/** Only fulfill explicitly requested snapshots; never cache/reuse preview bitmaps or SDK frames. */
object SceneFrames {
    @Volatile var lastFrameMs: Long = 0
        private set
    @Volatile var pose: DoubleArray? = null
        private set
    private data class Capture(val id: Long, val callback: (Bitmap, Long, SceneEvidence) -> Unit)
    private val pending = AtomicReference<Capture?>()
    val hasPendingCapture: Boolean get() = pending.get() != null
    fun request(id: Long, callback: (Bitmap, Long, SceneEvidence) -> Unit) { pending.set(Capture(id, callback)) }
    fun cancel() { pending.set(null) }
    fun offer(bitmap: Bitmap, capturedMs: Long, orientation: DoubleArray? = null, targets: List<TargetBox> = emptyList(), evidence: SceneEvidence? = null) {
        lastFrameMs = capturedMs
        pose = orientation
        val capture = pending.getAndSet(null) ?: return
        // Copy while the capture thread owns the preview. Compression/network run on their own worker.
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        capture.callback(copy, capturedMs, evidence ?: SceneEvidence(bitmap.width, bitmap.height, targets.toList()))
    }
}

class SceneClient {
    private val worker = Executors.newSingleThreadExecutor()
    private val instanceId = java.util.UUID.randomUUID().toString().take(8)
    private val connection = AtomicReference<HttpURLConnection?>()
    @Volatile private var generation = 0L
    @Volatile var capturedTicket: Long? = null
        private set
    fun cancel() { generation++; connection.getAndSet(null)?.disconnect(); SceneFrames.cancel() }
    fun close() { cancel(); worker.shutdownNow() }

    fun request(config: SceneConfig, ticket: SceneTicket, onMetrics: (Map<String, Any>) -> Unit = {}, findTarget: FindTarget? = null, question: String? = null, canUpload:()->Boolean={true}, onFind: ((FindObservation, Long) -> Unit)? = null, callback: (String?, String?, Long) -> Unit) {
        cancel()
        capturedTicket = null
        val requestGeneration = generation
        val traceId = "$instanceId-${ticket.id}"
        SceneFrames.request(ticket.id) { bitmap, captured, evidence ->
            capturedTicket = ticket.id
            val frameDeliveredAt = SystemClock.elapsedRealtime()
            if (generation != requestGeneration || worker.isShutdown) bitmap.recycle()
            else runCatching {
                worker.execute {
                    var conn: HttpURLConnection? = null
                    val workerStarted = SystemClock.elapsedRealtime()
                    val metrics = linkedMapOf<String, Any>("requestId" to ticket.id, "traceId" to traceId,
                        "mode" to if (findTarget != null) "find" else if(question!=null) "question" else if (ticket.detailed) "detail" else "brief",
                        "capture_wait_ms" to maxOf(0, frameDeliveredAt - ticket.createdMs),
                        "frame_age_at_capture_ms" to maxOf(0, frameDeliveredAt - captured),
                        "frame_id" to "$traceId-$captured", "frame_captured_ms" to captured,
                        "depth_delta_us" to (evidence.depthDeltaUs ?: -1L),
                        "depth_paired" to evidence.pairedDepth,
                        "worker_queue_ms" to workerStarted - frameDeliveredAt)
                    var networkStarted = 0L
                    fun deliver(text: String?, error: String?, time: Long) {
                        metrics["result"] = if (error == null) "success" else error
                        callback(text, error, time)
                    }
                    try {
                        if (generation != requestGeneration) return@execute
                        if (SystemClock.elapsedRealtime() - captured !in 0..1_500) {
                            deliver(null, "画面已过期，请重新观察", captured); return@execute
                        }
                        val compressionStarted = SystemClock.elapsedRealtime()
                        val scale = minOf(1.0, 640.0 / maxOf(bitmap.width, bitmap.height))
                        val scaled = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                        val bytes = try { ByteArrayOutputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 72, it)); it.toByteArray() } }
                            finally { if (scaled !== bitmap) scaled.recycle() }
                        val image = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val payload = if (findTarget != null) { require(config.direct); FindProtocol.request(findTarget, image) }
                            else if (config.direct) SceneProtocol.request(image, ticket.detailed,question)
                            else JSONObject().put("request_id", traceId).put("mode", if(question!=null) "question" else if (ticket.detailed) "detail" else "brief").put("image_base64", image).apply { if(question!=null) put("question",question) }
                        val body = payload.toString().toByteArray(Charsets.UTF_8)
                        metrics["transport"] = if (config.direct) "deepseek_direct" else "proxy"
                        if (config.direct) metrics["model"] = SceneProtocol.MODEL
                        metrics["compression_ms"] = SystemClock.elapsedRealtime() - compressionStarted
                        metrics["image_bytes"] = bytes.size
                        metrics["request_body_bytes"] = body.size
                        if (generation != requestGeneration) return@execute
                        if(!canUpload()) {deliver(null,"请先停稳",captured);return@execute}
                        networkStarted = SystemClock.elapsedRealtime()
                        conn = URI(config.url).toURL().openConnection() as HttpURLConnection
                        connection.set(conn)
                        if (generation != requestGeneration) return@execute
                        conn.requestMethod = "POST"; conn.connectTimeout = 2_500; conn.readTimeout = 22_000
                        conn.instanceFollowRedirects = false; conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Authorization", "Bearer ${config.token}")
                        conn.setFixedLengthStreamingMode(body.size)
                        val connectStarted = SystemClock.elapsedRealtime()
                        conn.connect()
                        metrics["connect_ms"] = SystemClock.elapsedRealtime() - connectStarted
                        if(generation!=requestGeneration) return@execute
                        if(!canUpload()) {deliver(null,"请先停稳",captured);return@execute}
                        val uploadStarted = SystemClock.elapsedRealtime()
                        conn.outputStream.use { it.write(body) }
                        metrics["upload_ms"] = SystemClock.elapsedRealtime() - uploadStarted
                        val waitStarted = SystemClock.elapsedRealtime()
                        val code = conn.responseCode
                        metrics["response_wait_ms"] = SystemClock.elapsedRealtime() - waitStarted
                        metrics["http_status"] = code
                        if (code != 200) {
                            val serverError = runCatching {
                                conn.errorStream?.use { input -> JSONObject(String(readBounded(input, 4_096), Charsets.UTF_8)).optString("error") }
                            }.getOrNull()
                            val message = when {
                                code == 401 || code == 402 || code == 403 || serverError == "provider_configuration" -> "环境服务配置无效"
                                code == 429 || serverError == "upstream_busy" -> "环境服务繁忙，请稍后再试"
                                code == 504 -> "环境分析超时，请稍后再试"
                                serverError in setOf("invalid_response", "unsafe_response") -> "环境服务返回内容无效，请重试"
                                else -> "环境服务暂不可用"
                            }
                            deliver(null, message, captured)
                        } else {
                            val downloadStarted = SystemClock.elapsedRealtime()
                            val raw = conn.inputStream.use { readBounded(it, 32_768) }
                            metrics["download_ms"] = SystemClock.elapsedRealtime() - downloadStarted
                            metrics["response_bytes"] = raw.size
                            val parseStarted = SystemClock.elapsedRealtime()
                            require(raw.size <= 32_768)
                            val json = JSONObject(String(raw, Charsets.UTF_8))
                            if (findTarget != null) {
                                metrics["find_schema"] = FindProtocol.diagnostics(json,findTarget)
                                val observation = FindProtocol.parse(json, findTarget, evidence)
                                metrics["parse_ms"] = SystemClock.elapsedRealtime() - parseStarted
                                metrics["result"] = "success"
                                onFind?.invoke(observation, captured)
                            } else {
                            val scene = if (config.direct) {
                                json.optJSONObject("usage")?.let { usage ->
                                    usage.optInt("completion_tokens", -1).takeIf { it >= 0 }?.let { metrics["output_tokens"] = it }
                                    usage.optInt("prompt_cache_hit_tokens", -1).takeIf { it >= 0 }?.let { metrics["cache_hit_tokens"] = it }
                                }
                                SceneProtocol.directScene(json)
                            } else {
                                require(json.getString("request_id") == traceId)
                                json.optLong("elapsed_ms", -1).takeIf { it >= 0 }?.let { metrics["server_elapsed_ms"] = it }
                                json.getJSONObject("scene")
                            }
                            val text = SceneProtocol.text(scene, ticket.detailed || question!=null)
                            metrics["parse_ms"] = SystemClock.elapsedRealtime() - parseStarted
                            deliver(text, null, captured)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        metrics["error_type"] = "SocketTimeoutException"
                        deliver(null, "环境分析超时，请稍后再试", captured)
                    }
                    catch (_: java.net.ConnectException) {
                        metrics["error_type"] = "ConnectException"
                        deliver(null, if (URI(config.url).host == "127.0.0.1") "环境服务未连接，请检查电脑调试连接" else "环境服务连接失败，请检查网络", captured)
                    }
                    catch (_: org.json.JSONException) { deliver(null, "环境服务返回格式无效", captured) }
                    catch (_: IllegalArgumentException) { deliver(null, "环境服务返回格式无效", captured) }
                    catch (_: ClassCastException) { deliver(null, "环境服务返回格式无效", captured) }
                    catch (error: Exception) { metrics["error_type"] = error.javaClass.simpleName; deliver(null, "网络不可用，环境分析已结束", captured) }
                    finally {
                        val ended = SystemClock.elapsedRealtime()
                        metrics["total_ms"] = ended - ticket.createdMs
                        if (networkStarted > 0) metrics["network_ms"] = ended - networkStarted
                        if (generation != requestGeneration) metrics["result"] = "cancelled"
                        else if (!metrics.containsKey("result")) metrics["result"] = "ended_without_response"
                        onMetrics(metrics.toMap())
                        bitmap.recycle(); connection.compareAndSet(conn, null); conn?.disconnect()
                    }
                }
            }.onFailure { bitmap.recycle() }
        }
    }
}


private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (out.size() <= limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    require(out.size() <= limit)
    return out.toByteArray()
}
