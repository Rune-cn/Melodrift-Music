package app.melodrift.music.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志记录器：捕获未处理异常，写入应用私有目录 crash.log。
 * 崩溃后重新打开 App，在「关于 → 崩溃日志」中可查看/复制堆栈，方便定位根因。
 */
object CrashLog {

    private var installed = false

    /** 安装全局异常处理器（App 启动时调用一次） */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("==== crash @ ").append(ts).append(" ====\n")
                sb.append("thread: ").append(thread.name).append('\n')
                val w = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(w))
                sb.append(w.toString()).append('\n')
                File(app.filesDir, "crash.log").appendText(sb.toString())
            } catch (_: Exception) {
            } finally {
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 读取崩溃日志文本；无则返回 null */
    fun read(context: Context): String? {
        val f = File(context.filesDir, "crash.log")
        return if (f.exists()) f.readText() else null
    }
}