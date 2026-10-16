package com.michele.luce.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/** Esecuzione parallela per bande di righe, con annullamento cooperativo. */
object Parallel {
    private val threads = max(1, min(8, Runtime.getRuntime().availableProcessors()))

    private val pool = Executors.newFixedThreadPool(threads, object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable): Thread =
            Thread(r, "luce-engine-${n.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
    })

    fun forRows(rows: Int, cancel: () -> Boolean = { false }, body: (Int, Int) -> Unit) {
        if (rows <= 0) return
        if (cancel()) throw CancellationException("annullato")
        val chunks = min(rows, threads * 4)
        if (chunks <= 1 || threads == 1) {
            body(0, rows)
            return
        }
        val step = (rows + chunks - 1) / chunks
        val tasks = ArrayList<Callable<Unit>>(chunks)
        var start = 0
        while (start < rows) {
            val s = start
            val e = min(rows, start + step)
            tasks.add(Callable {
                if (!cancel()) body(s, e)
            })
            start = e
        }
        try {
            val futures = pool.invokeAll(tasks)
            for (f in futures) f.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("interrotto")
        }
        if (cancel()) throw CancellationException("annullato")
    }
}
