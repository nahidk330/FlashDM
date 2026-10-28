package com.flash.dm

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLongArray

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadState(
    val fileName: String,
    val total: Long = -1,
    val downloaded: Long = 0,
    val speedBps: Long = 0,
    val status: Status = Status.QUEUED
)

class DownloadTask(
    val url: String,
    private val dest: File,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val maxConnections: Int = 4
) {
    private val _state = MutableStateFlow(DownloadState(dest.name))
    val state: StateFlow<DownloadState> = _state

    private var job: Job? = null
    private var resumable = false
    private var segStart = LongArray(0)
    private var segEnd = LongArray(0)
    private var segDone = AtomicLongArray(0)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(status = Status.RUNNING) }
                if (segStart.isEmpty()) probe()
                if (!resumable) for (i in 0 until segDone.length()) segDone.set(i, 0)
                coroutineScope {
                    val ticker = launch { trackSpeed() }
                    segStart.indices.map { i -> launch { downloadSegment(i) } }.joinAll()
                    ticker.cancel()
                }
                _state.update { it.copy(status = Status.DONE, speedBps = 0, downloaded = sumDone()) }
            } catch (e: CancellationException) {
                _state.update { it.copy(status = Status.PAUSED, speedBps = 0) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = Status.FAILED, speedBps = 0) }
            }
        }
    }

    fun pause() { job?.cancel() }

    private fun probe() {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            resumable = r.code == 206
            val total = if (resumable)
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
            else r.header("Content-Length")?.toLongOrNull() ?: -1L

            val n = if (resumable && total > 0) maxConnections else 1
            val chunk = if (total > 0) total / n else 0L
            segStart = LongArray(n) { it * chunk }
            segEnd = LongArray(n) { if (total <= 0) -1L else if (it == n - 1) total - 1 else (it + 1) * chunk - 1 }
            segDone = AtomicLongArray(n)
            if (total > 0) RandomAccessFile(dest, "rw").use { it.setLength(total) }
            _state.update { it.copy(total = total) }
        }
    }

    private suspend fun downloadSegment(i: Int) {
        val start = segStart[i] + segDone.get(i)
        val end = segEnd[i]
        if (end >= 0 && start > end) return

        val rb = Request.Builder().url(url)
        if (resumable) rb.header("Range", "bytes=$start-$end")
        val call = client.newCall(rb.build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val src = r.body?.byteStream() ?: throw IOException("Empty body")
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        segDone.addAndGet(i, n.toLong())
                    }
                }
            }
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("paused")
            throw e
        } finally {
            handle.dispose()
        }
    }

    private fun sumDone(): Long = (0 until segDone.length()).sumOf { segDone.get(it) }

    private suspend fun trackSpeed() {
        var last = sumDone()
        while (true) {
            delay(500)
            val now = sumDone()
            _state.update { it.copy(downloaded = now, speedBps = (now - last) * 2) }
            last = now
        }
    }
}
