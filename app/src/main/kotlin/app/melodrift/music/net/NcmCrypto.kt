package app.melodrift.music.net

import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 weapi 加密（逆向自 music.163.com core.js 的 asrsea 逻辑）。
 *
 * 算法：
 *   encText   = AES-128-CBC( AES-128-CBC(plaintext, PRESET_KEY) , randKey )  // 双层，输出 base64
 *   encSecKey = RSA( randKey )  // charCode 小端打包 + 128字节块 pow
 *
 * eapi 算法（App 端部分接口）：
 *   message = nobody{url}use{text}md5forencrypt
 *   data    = {url}-36cd479b6b5-{text}-36cd479b6b5-{md5}
 *   params  = AES-128-ECB(key=e82ckenh8dichen8).hex大写
 */
object NcmCrypto {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_E = "010001"
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e4" +
        "17629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575" +
        "cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private val random = SecureRandom()
    private val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 随机 16 位密钥（与 core.js 一致：大小写字母+数字） */
    fun randomStr(length: Int = 16): String =
        buildString {
            repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) }
        }

    private fun aesCbcEncrypt(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder()
            .encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 模拟 core.js encryptedString：charCode 小端打包 → pow(e, N) → 128字节 hex */
    private fun rsaEncrypt(text: String): String {
        val modulus = BigInteger(RSA_MODULUS, 16)
        val e = BigInteger(RSA_E, 16)
        val arr = text.map { it.code }.toMutableList()
        while (arr.size % 128 != 0) arr.add(0)

        val sb = StringBuilder()
        for (i in arr.indices step 128) {
            val block = arr.subList(i, i + 128)
            var n = BigInteger.ZERO
            for (b in block.asReversed()) {
                n = n.shiftLeft(8).or(BigInteger.valueOf(b.toLong()))
            }
            val c = n.modPow(e, modulus)
            var hex = c.toString(16)
            while (hex.length < 256) hex = "0$hex"
            sb.append(hex)
        }
        return sb.toString()
    }

    /**
     * weapi 加密入口：返回 body 表单（params / encSecKey）。
     * 注意 csrf_token 需自行放进 [data]（来自 cookie 的 __csrf）。
     */
    fun weapiEncrypt(data: Map<String, Any>): Map<String, String> {
        val randKey = randomStr(16)
        val text = JSONObject(data).toString() // {"k":v,...} 紧凑格式
        val enc1 = aesCbcEncrypt(text, PRESET_KEY)
        val encText = aesCbcEncrypt(enc1, randKey)
        return mapOf(
            "params" to encText,
            "encSecKey" to rsaEncrypt(randKey)
        )
    }

    private fun md5Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * eapi 加密入口：返回加密 params 字符串（hex 大写）。
     * [url] 为完整 API 路径（如 /api/xxx），[data] 为明文参数。
     */
    fun eapiEncrypt(url: String, data: Map<String, Any>): String {
        val text = JSONObject(data).toString()
        val message = "nobody${url}use$text" + "md5forencrypt"
        val md5 = md5Hex(message)
        val payload = "${url}-36cd479b6b5-$text-36cd479b6b5-$md5"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.UTF_8), "AES")
        )
        val enc = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return enc.joinToString("") { "%02X".format(it) }
    }
}