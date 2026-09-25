package app.melodrift.music.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 IO 线程执行网络调用并统一兜底：
 * 失败返回 null（UI 层自行决定降级 / 重试），但**协程取消时重抛 [CancellationException]**——
 * 避免 `catch (Exception)` 把取消信号吞掉，导致已取消的协程继续往下写状态
 * （典型竞态：切排序 / 翻页 / 快速切歌时，旧请求的结果覆盖新请求）。
 */
suspend inline fun <T> ioNet(crossinline block: () -> T): T? =
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }