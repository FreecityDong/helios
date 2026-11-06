package com.helios.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Real zero-valued PCM keeps SCO active through recorder/TTS gaps; no sound or recording. */
internal class BluetoothAudioKeeper(device: AudioDeviceInfo) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(3_200,AudioTrack.getMinBufferSize(16_000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    private val worker: Thread
    init {
        try {
            check(track.state==AudioTrack.STATE_INITIALIZED)
            check(track.setPreferredDevice(device))
            track.play()
            worker=Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val silence=ShortArray(320)
                try { while(running.get()) {
                    if(track.write(silence,0,silence.size,AudioTrack.WRITE_BLOCKING)<=0) break
                } } catch (_:IllegalStateException) { /* close can interrupt a blocked write */ }
                finally { running.set(false) }
            },"helios-bt-audio").apply {isDaemon=true;start()}
        } catch(error:Throwable) {running.set(false);track.release();throw error}
    }
    fun ready(deviceId:Int):Boolean = running.get() && runCatching {
        track.routedDevice?.id==deviceId && track.playbackHeadPosition>=1_600
    }.getOrDefault(false)
    val alive get()=running.get()
    override fun close() {
        running.set(false)
        runCatching {track.pause()};runCatching {track.flush()};runCatching {track.stop()}
        // Wake a blocking writer before releasing its native AudioTrack.
        worker.interrupt()
        runCatching {track.release()}
    }
}
