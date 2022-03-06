/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.threading

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.util.concurrent.CountDownLatch

internal class ThreadQueue : Thread("RunnableQueue") {
    private var handler: Handler? = null
    private val latch = CountDownLatch(1)

    init {
        start()
    }

    fun postRunnable(runnable: Runnable) {
        try {
            latch.await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler?.post(runnable)
    }

    fun cancelRunnable(runnable: Runnable) {
        try {
            latch.await()
            handler?.removeCallbacks(runnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cleanupQueue() {
        try {
            latch.await()
            handler?.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handleMessage(inputMessage: Message) = Unit

    fun recycleQueue() {
        handler?.looper?.quit()
    }

    @SuppressLint("HandlerLeak")
    override fun run() {
        Looper.prepare()
        handler = object : Handler() {
            override fun handleMessage(message: Message) {
                this@ThreadQueue.handleMessage(message)
            }
        }
        latch.countDown()
        Looper.loop()
    }

}