package com.example.jaecoovoice.engine

import java.util.concurrent.Executor

/** Runs queued work only when a test calls [runAll]. */
class ManualExecutor : Executor {
    private val queue = ArrayDeque<Runnable>()

    val queued get() = queue.size

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}
