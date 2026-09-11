package app.melodrift.music.net

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 歌曲下载：按音质获取播放地址 → 下载到系统「下载」目录（MediaStore）。
 *
 * 失败时抛 [IOException]（携带具体原因），成功返回保存路径。
 * 必须在 IO 线程调用。
 */
object Downloader {

    /**
     * 下载任务专用协程域：与 UI 生命周期解耦。
     * 下载音质弹窗 dismiss / 歌单页退出都会取消 rememberCoroutineScope，
     * 而下载其实已经（或正在）完成 —— 用页面 scope 会出现"提示失败但文件已下载"。
     */
    val taskScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 下载专用客户端：CDN 大文件（无损可能 30MB+）需要更长读超时 */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 从响应 Content-Type 推断扩展名；解析失败按音质回退 */
    private fun extInfo(level: String, contentType: String): Pair<String, String> {
        val ct = contentType.lowercase()
        return when {
            ct.contains("flac") -> "flac" to "audio/flac"
            ct.contains("mpeg") || ct.contains("mp3") -> "mp3" to "audio/mpeg"
            ct.contains("mp4") || ct.contains("m4a") || ct.contains("aac") || ct.contains("audio/mp4") ->
                "m4a" to "audio/mp4"
            ct.contains("wav") -> "wav" to "audio/wav"
            ct.contains("ape") -> "ape" to "audio/ape"
            // 兜底：无损档位按 flac，其余按 mp3
            level == "lossless" || level == "hires" -> "flac" to "audio/flac"
            else -> "mp3" to "audio/mpeg"
        }
    }

    /**
     * 按指定音质下载歌曲到公共下载目录；返回保存路径。
     * 失败原因以 IOException 抛出：
     *  - "no_url" → 歌曲需会员/版权受限，拿不到播放地址
     *  - "http:NNN" → CDN 返回错误状态
     *  - 其他 → 网络/存储失败
     */
    fun downloadSong(context: Context, song: Song, level: String = "standard"): String {
        // 音质拿不到时智能降级
        val url = NcmApi.songUrlSmart(song.id, level)
            ?: throw IOException("no_url")
        return downloadSongUrl(context, song, url, level)
    }

    /**
     * 用已获取的播放地址下载歌曲（批量下载场景复用，避免逐首再请求 URL）。
     * 返回保存路径；失败抛 [IOException]（"http:NNN" / "save failed: ..."）。
     */
    fun downloadSongUrl(context: Context, song: Song, url: String, level: String = "standard"): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", NcmApi.USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .build()
        val resp = downloadClient.newCall(request).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("http:$code")
        }
        val body = resp.body ?: throw IOException("http:empty")
        val contentType = body.contentType()?.toString() ?: ""
        val (fileExt, mime) = extInfo(level, contentType)
        val safeName = "${song.artistNames} - ${song.name}"
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(120)
        val fileName = "$safeName.$fileExt"

        try {
            // 流式写入，大文件（无损可能 30MB+）不整包进内存
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveToMediaStore(context, song, fileName, mime, body.byteStream())
            }
            val bytes = body.bytes()
            return saveToLegacy(context, fileName, bytes)
        } finally {
            body.close()
        }
    }

    private fun saveToMediaStore(
        context: Context,
        song: Song,
        fileName: String,
        mime: String,
        input: java.io.InputStream
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            // 写入基础元数据：系统文件管理器 / 播放器能显示歌名、歌手、专辑
            put(MediaStore.MediaColumns.TITLE, song.name)
            put(MediaStore.MediaColumns.ARTIST, song.artistNames)
            put(MediaStore.MediaColumns.ALBUM, song.album?.name ?: "")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MelodriftMusic")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out, 64 * 1024)
            } ?: throw IOException("openOutputStream failed")
            // 清除 pending 标记：个别机型 update 返回 0 或抛异常，但文件已写入，
            // 不应因此判为下载失败（避免"明明下载成功却提示失败"）
            try {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
            } catch (_: Exception) {
            }
            return uri.toString()
        } catch (e: Exception) {
            // 下载/写入中断：先把残品标记为 pending（对用户隐藏），再尝试删除，
            // 避免"提示下载失败但文件还在"的残留文件
            try {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) },
                    null, null
                )
            } catch (_: Exception) {
            }
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw IOException("save failed: ${e.message}")
        }
    }

    private fun saveToLegacy(context: Context, fileName: String, bytes: ByteArray): String {
        // Android 9 及以下：公共下载目录（需写权限，失败则退回应用私有目录）
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.writeBytes(bytes)
            return f.absolutePath
        } catch (e: Exception) {
            try {
                val f = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                    fileName
                )
                f.writeBytes(bytes)
                return f.absolutePath
            } catch (e2: Exception) {
                throw IOException("storage failed: ${e2.message}")
            }
        }
    }
}