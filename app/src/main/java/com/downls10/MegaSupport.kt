package com.downls10

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * دعم روابط ملفات Mega. ملفات Mega مشفّرة (AES-CTR) والمفتاح موجود في الرابط نفسه بعد #،
 * فيطلب التطبيق رابط التنزيل المؤقت من واجهة Mega ثم يفك التشفير أثناء التنزيل.
 * روابط المجلدات غير مدعومة.
 */
object MegaSupport {

    fun isMegaHost(host: String): Boolean =
        host == "mega.nz" || host.endsWith(".mega.nz") ||
            host == "mega.co.nz" || host.endsWith(".mega.co.nz") || host == "mega.io"

    /** رابط ملف Mega (الصيغة الجديدة /file/ID#KEY أو القديمة #!ID!KEY). */
    fun isMegaFileLink(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            if (!isMegaHost(host)) return false
            val path = uri.path ?: ""
            val fragment = uri.fragment ?: ""
            path.startsWith("/file/") || (fragment.startsWith("!") && !fragment.startsWith("!!"))
        } catch (e: Exception) {
            false
        }
    }

    private fun b64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private class Parsed(val id: String, val key: ByteArray)

    private fun parse(url: String): Parsed {
        val uri = Uri.parse(url)
        val path = uri.path ?: ""
        val fragment = uri.fragment ?: ""
        if (path.startsWith("/folder/") || fragment.startsWith("F!")) {
            throw NonRetryableDownloadException("روابط مجلدات Mega غير مدعومة، استخدم رابط ملف واحد")
        }
        val id: String
        val keyText: String
        if (path.startsWith("/file/")) {
            id = path.removePrefix("/file/").substringBefore('/')
            keyText = fragment
        } else if (fragment.startsWith("!")) {
            val parts = fragment.split('!')
            id = parts.getOrNull(1) ?: ""
            keyText = parts.getOrNull(2) ?: ""
        } else {
            throw NonRetryableDownloadException("رابط Mega غير صالح")
        }
        val cleanKey = keyText.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (id.isBlank() || cleanKey.isBlank()) {
            throw NonRetryableDownloadException("رابط Mega ناقص (لا يحتوي مفتاح فك التشفير بعد #)")
        }
        val raw = try { b64UrlDecode(cleanKey) } catch (e: Exception) { ByteArray(0) }
        if (raw.size < 32) throw NonRetryableDownloadException("مفتاح رابط Mega غير صالح")
        return Parsed(id, raw.copyOf(32))
    }

    /** مفتاح AES للملف: النصف الأول XOR النصف الثاني من المفتاح الكامل. */
    private fun aesKeyOf(key32: ByteArray): ByteArray =
        ByteArray(16) { i -> (key32[i].toInt() xor key32[i + 16].toInt()).toByte() }

    private fun apiRequest(body: String): String {
        val conn = URL("https://g.api.mega.co.nz/cs?id=" + (System.currentTimeMillis() % 1000000L)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun errorFor(code: Int): IOException = when (code) {
        -9, -8 -> NonRetryableDownloadException("الملف غير موجود في Mega أو حُذف")
        -16 -> NonRetryableDownloadException("Mega حظر هذا الملف")
        -17 -> NonRetryableDownloadException("تجاوزت حد النقل المسموح في Mega، حاول لاحقاً")
        -3, -4, -6 -> IOException("خطأ مؤقت في Mega ($code)")
        else -> NonRetryableDownloadException("خطأ Mega: $code")
    }

    private fun decryptName(at: String, aesKey: ByteArray): String? {
        return try {
            val data = b64UrlDecode(at)
            val padded = if (data.size % 16 == 0) data else data.copyOf(data.size + 16 - data.size % 16)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val text = String(cipher.doFinal(padded), Charsets.UTF_8).trimEnd('\u0000')
            if (text.startsWith("MEGA")) {
                JSONObject(text.substring(4)).optString("n").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** يطلب من Mega رابط التنزيل المؤقت واسم الملف. يُستدعى من خيط الخلفية. */
    fun resolve(url: String): RemoteLink {
        val parsed = parse(url)
        val body = "[{\"a\":\"g\",\"g\":1,\"ssl\":1,\"p\":" + JSONObject.quote(parsed.id) + "}]"
        val text = apiRequest(body).trim()
        if (text.startsWith("-")) throw errorFor(text.toIntOrNull() ?: -1)
        val arr = try { JSONArray(text) } catch (e: Exception) { throw IOException("رد Mega غير مفهوم") }
        val first = arr.opt(0)
        if (first is Number) throw errorFor(first.toInt())
        val obj = first as? JSONObject ?: throw IOException("رد Mega غير مفهوم")
        val direct = obj.optString("g")
        if (direct.isBlank()) throw NonRetryableDownloadException("لا يوجد رابط تنزيل لهذا الملف في Mega")
        val name = decryptName(obj.optString("at"), aesKeyOf(parsed.key))
        return RemoteLink(direct, name, parsed.key)
    }
}

/**
 * يفك تشفير ملف Mega أثناء القراءة (AES-CTR).
 * offset هو موضع أول بايت نقرؤه من الملف (للاستئناف من منتصف الملف).
 */
class MegaDecryptInputStream(
    private val source: InputStream,
    key32: ByteArray,
    offset: Long
) : InputStream() {

    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    init {
        val aesKey = ByteArray(16) { i -> (key32[i].toInt() xor key32[i + 16].toInt()).toByte() }
        val iv = ByteArray(16)
        System.arraycopy(key32, 16, iv, 0, 8)            // nonce (8 بايت) ثم عدّاد الكتل (8 بايت)
        val counter = offset / 16L
        for (i in 0 until 8) iv[8 + i] = ((counter ushr (56 - 8 * i)) and 0xFFL).toByte()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val skip = (offset % 16L).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))     // نقدّم مجرى المفتاح للبايت المطلوب
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n <= 0) return n
        cipher.update(b, off, n, b, off)
        return n
    }

    override fun close() {
        source.close()
    }
}
