package app.podium

import android.content.Context
import app.podium.sources.jellyfin.JellyfinPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Jellyfin folder pages live in this app-owned directory; credentials are never part of the cache. */
class JellyfinPageCacheFile(context: Context) : JellyfinPageCache {
    private val directory = File(context.filesDir, "jellyfin-pages")
    private val mutex = Mutex()

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { file(key).takeIf(File::isFile)?.readText(Charsets.UTF_8) }
    }

    override suspend fun write(key: String, json: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.exists()) directory.mkdirs()
            val target = file(key)
            val temporary = File(directory, "${target.name}.tmp")
            temporary.writeText(json, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
            Unit
        }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }
}
