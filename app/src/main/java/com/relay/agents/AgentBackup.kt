package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** فشل قراءة نسخة احتياطية: الملف تالف، أو مشفّر ويحتاج كلمة مرور، أو كلمة المرور خاطئة. */
class BackupException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { INVALID, NEED_PASSPHRASE, WRONG_PASSPHRASE }
}

/**
 * نسخة احتياطية للوكلاء (مع مفاتيحهم) في ملف JSON.
 * بدون كلمة مرور: نص عادي. معها: AES-256-GCM بمفتاح مشتق بـ PBKDF2-HMAC-SHA256.
 * منطق خالص بلا اعتماد على أندرويد (قابل للاختبار على JVM).
 */
object AgentBackup {
    const val FILE_NAME = "claude-relay-agents.json"
    private const val VERSION = 1
    private const val ITERATIONS = 120_000
    private const val KDF = "PBKDF2WithHmacSHA256"

    fun encode(agents: List<AgentConfig>, passphrase: String, now: Long = System.currentTimeMillis()): String {
        val plain = JSONObject()
            .put("version", VERSION).put("savedAt", now)
            .put("agents", JSONArray(agents.map { it.toJson() }))
            .toString()
        if (passphrase.isEmpty()) return plain

        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("version", VERSION).put("encrypted", true)
            .put("kdf", KDF).put("iterations", ITERATIONS).put("savedAt", now)
            .put("salt", b64.encodeToString(salt))
            .put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(data))
            .toString()
    }

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text).optBoolean("encrypted", false) }.getOrDefault(false)

    /** يفك النسخة ويرجع الوكلاء الصالحين فقط (العناصر التالفة تُتجاوز). */
    fun decode(text: String, passphrase: String): List<AgentConfig> {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw BackupException(BackupException.Kind.INVALID, "الملف ليس نسخة احتياطية صالحة") }

        val body: JSONObject = if (root.optBoolean("encrypted", false)) {
            if (passphrase.isEmpty()) {
                throw BackupException(BackupException.Kind.NEED_PASSPHRASE, "النسخة مشفّرة وتحتاج كلمة مرور")
            }
            val plainText = try {
                val d = Base64.getDecoder()
                val salt = d.decode(root.getString("salt"))
                val iv = d.decode(root.getString("iv"))
                val data = d.decode(root.getString("data"))
                val iterations = root.optInt("iterations", ITERATIONS).coerceIn(10_000, 2_000_000)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
                String(cipher.doFinal(data), Charsets.UTF_8)
            } catch (e: BadPaddingException) {
                throw BackupException(BackupException.Kind.WRONG_PASSPHRASE, "كلمة المرور غير صحيحة")
            } catch (e: Exception) {
                throw BackupException(BackupException.Kind.INVALID, "الملف ليس نسخة احتياطية صالحة")
            }
            runCatching { JSONObject(plainText) }
                .getOrElse { throw BackupException(BackupException.Kind.INVALID, "الملف ليس نسخة احتياطية صالحة") }
        } else {
            root
        }

        val arr = body.optJSONArray("agents")
            ?: throw BackupException(BackupException.Kind.INVALID, "الملف لا يحتوي وكلاء")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { AgentConfig.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /** يضيف الوكلاء المستعادين غير الموجودين (بالمعرّف أو بنفس المزوّد والاسم والنموذج والمفتاح) دون المساس بالموجودين. */
    fun merge(existing: List<AgentConfig>, restored: List<AgentConfig>): List<AgentConfig> {
        fun sig(a: AgentConfig) = listOf(a.provider.name, a.name, a.model, a.apiKey).joinToString("\u0000")
        val ids = existing.map { it.id }.toMutableSet()
        val sigs = existing.map { sig(it) }.toMutableSet()
        val out = existing.toMutableList()
        for (a in restored) {
            if (a.id in ids || sig(a) in sigs) continue
            out += a
            ids += a.id
            sigs += sig(a)
        }
        return out
    }

    private fun key(pass: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

/**
 * كتابة/قراءة ملف النسخة داخل مجلد يختاره المستخدم (Storage Access Framework).
 * الإذن الدائم للمجلد يبقى عند تحديث التطبيق، ويضيع عند إلغاء التثبيت، وعندها تُستعاد النسخة يدوياً من ملف.
 */
class AgentBackupStore(private val ctx: Context, private val storage: Storage) {
    private val resolver get() = ctx.contentResolver

    /** مجلد النسخة إن كان محدداً وإذنه الدائم ما زال صالحاً. */
    fun treeUri(): Uri? {
        val saved = storage.backupTreeUri() ?: return null
        val uri = Uri.parse(saved)
        val ok = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (ok) uri else null
    }

    fun chooseTree(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        storage.setBackupTreeUri(uri.toString())
    }

    fun clearTree() {
        val uri = storage.backupTreeUri()?.let { Uri.parse(it) }
        if (uri != null) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        storage.setBackupTreeUri(null)
    }

    private fun findFile(tree: Uri): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == AgentBackup.FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    /** نص ملف النسخة في المجلد، أو null إن لم يوجد أو تعذّرت القراءة. */
    fun read(): String? {
        val tree = treeUri() ?: return null
        val file = findFile(tree) ?: return null
        return runCatching {
            resolver.openInputStream(file)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    fun write(text: String): Boolean {
        return try {
            val tree = treeUri() ?: return false
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val target = findFile(tree)
                ?: DocumentsContract.createDocument(resolver, parent, "application/json", AgentBackup.FILE_NAME)
                ?: return false
            val out = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
                ?: resolver.openOutputStream(target, "w")
                ?: return false
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
