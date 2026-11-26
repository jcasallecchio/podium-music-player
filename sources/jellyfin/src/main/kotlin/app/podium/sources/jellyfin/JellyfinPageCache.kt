package app.podium.sources.jellyfin

/** Small provider boundary: the Android app supplies app-private persistent storage. */
interface JellyfinPageCache {
    suspend fun read(key: String): String?
    suspend fun write(key: String, json: String)
    suspend fun clear()
}

class MemoryJellyfinPageCache : JellyfinPageCache {
    private val pages = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun read(key: String): String? = pages[key]
    override suspend fun write(key: String, json: String) { pages[key] = json }
    override suspend fun clear() { pages.clear() }
}
