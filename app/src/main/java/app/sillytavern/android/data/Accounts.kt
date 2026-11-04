package app.sillytavern.android.data

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Salted PBKDF2 passcode check. A lock against casual access, not encryption of the data. */
object Passcode {
    private const val ITERATIONS = 120_000

    fun hash(code: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): Pair<String, String> =
        Base64.getEncoder().encodeToString(salt) to Base64.getEncoder().encodeToString(derive(code, salt))

    fun verify(code: String, salt: String, hash: String): Boolean {
        if (salt.isEmpty() || hash.isEmpty()) return false
        val actual = derive(code, Base64.getDecoder().decode(salt))
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(hash))
    }

    private fun derive(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)).encoded
}

@Serializable
data class Account(
    val handle: String,
    val name: String,
    val passSalt: String = "",
    val passHash: String = "",
    val createdAt: Long = 0,
) {
    val locked get() = passHash.isNotEmpty()
}

@Serializable
private data class AccountFile(val accounts: List<Account> = emptyList(), val active: String = AccountRegistry.DEFAULT)

class AccountException(message: String) : Exception(message)

/**
 * Local profiles. `default` uses the original `<base>/data` folder, so an existing install needs no migration;
 * every other user lives in `<base>/users/<handle>/data`, with backups in `<base>/backups/<handle>`.
 */
class AccountRegistry(private val base: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = File(base, "accounts.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Synchronized
    private fun load(): AccountFile {
        val loaded = if (file.isFile) runCatching { json.decodeFromString(AccountFile.serializer(), file.readText()) }.getOrDefault(AccountFile()) else AccountFile()
        val withDefault = if (loaded.accounts.any { it.handle == DEFAULT }) loaded.accounts else listOf(Account(DEFAULT, "Default User")) + loaded.accounts
        val active = loaded.active.takeIf { a -> withDefault.any { it.handle == a } } ?: DEFAULT
        return AccountFile(withDefault, active)
    }

    @Synchronized
    private fun save(f: AccountFile) = file.writeAtomic(json.encodeToString(AccountFile.serializer(), f))

    fun list(): List<Account> = load().accounts
    fun get(handle: String): Account? = load().accounts.firstOrNull { it.handle == handle }
    fun active(): Account = load().let { f -> f.accounts.first { it.handle == f.active } }

    fun setActive(handle: String) {
        val f = load()
        if (f.accounts.none { it.handle == handle }) throw AccountException("No such user")
        save(f.copy(active = handle))
    }

    fun pathsFor(handle: String): StoragePaths =
        StoragePaths(if (handle == DEFAULT) File(base, "data") else File(base, "users/$handle/data"))

    fun backupDir(handle: String) = File(base, "backups/$handle")

    /** Creates a user; the handle is derived from the name (lowercase letters, digits, `-`, `_`), made unique. */
    @Synchronized
    fun create(name: String): Account {
        val clean = name.trim()
        if (clean.isEmpty()) throw AccountException("The name is empty")
        val f = load()
        val stem = clean.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32).ifEmpty { "user" }
        var handle = if (stem == DEFAULT) "default-2" else stem
        var n = 2
        while (f.accounts.any { it.handle == handle }) handle = "$stem-${n++}"
        val account = Account(handle, clean.take(60), createdAt = now())
        save(f.copy(accounts = f.accounts + account))
        pathsFor(handle).ensure()
        return account
    }

    @Synchronized
    fun rename(handle: String, name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) throw AccountException("The name is empty")
        val f = load()
        save(f.copy(accounts = f.accounts.map { if (it.handle == handle) it.copy(name = clean) else it }))
    }

    /** Sets a passcode; [current] must match when one is already set. Returns false on a wrong current code. */
    @Synchronized
    fun setPasscode(handle: String, code: String, current: String? = null): Boolean {
        val f = load()
        val a = f.accounts.firstOrNull { it.handle == handle } ?: throw AccountException("No such user")
        if (a.locked && !(current != null && Passcode.verify(current, a.passSalt, a.passHash))) return false
        val (salt, hash) = if (code.isEmpty()) "" to "" else Passcode.hash(code)
        save(f.copy(accounts = f.accounts.map { if (it.handle == handle) it.copy(passSalt = salt, passHash = hash) else it }))
        return true
    }

    fun verify(handle: String, code: String): Boolean {
        val a = get(handle) ?: return false
        return !a.locked || Passcode.verify(code, a.passSalt, a.passHash)
    }

    /** Deletes a user and all their data. The default user and the active user cannot be deleted. A locked user needs the passcode. */
    @Synchronized
    fun delete(handle: String, code: String = ""): Boolean {
        if (handle == DEFAULT) throw AccountException("The default user cannot be deleted")
        val f = load()
        if (f.active == handle) throw AccountException("Switch to another user first")
        if (f.accounts.none { it.handle == handle }) throw AccountException("No such user")
        if (!verify(handle, code)) return false
        save(f.copy(accounts = f.accounts.filterNot { it.handle == handle }))
        File(base, "users/$handle").deleteRecursively()
        backupDir(handle).deleteRecursively()
        return true
    }

    companion object { const val DEFAULT = "default" }
}
