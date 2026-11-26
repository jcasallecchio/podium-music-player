package app.podium.sources.jellyfin

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Availability
import app.podium.core.model.MediaKind
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Jellyfin as an ordinary provider in Podium's existing source graph. No UI or player dependency. */
class JellyfinMusicSource(
    private val credentials: CredentialStore,
    private val transport: JellyfinTransport = OkHttpJellyfinTransport(),
    private val deviceId: String = java.util.UUID.randomUUID().toString(),
    private val pageCache: JellyfinPageCache = MemoryJellyfinPageCache(),
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MusicSource {
    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Jellyfin",
        providerName = "Jellyfin",
        basis = Basis.USER_SERVER,
        termsUrl = "https://jellyfin.org/",
    )

    private var server = credentials.read(SOURCE_ID)?.get(KEY_SERVER)
    private var userId = credentials.read(SOURCE_ID)?.get(KEY_USER_ID)
    private var token = credentials.read(SOURCE_ID)?.get(KEY_TOKEN)
    private var userName = credentials.read(SOURCE_ID)?.get(KEY_USER_NAME)
    private val mutableAuth = MutableStateFlow(
        if (!token.isNullOrBlank() && !userId.isNullOrBlank()) AuthState.SignedIn(userName ?: "Jellyfin")
        else AuthState.SignedOut,
    )
    private val mutableCapabilities = MutableStateFlow(capabilities(mutableAuth.value))
    private val cachedLibrary = MutableStateFlow<List<Track>?>(null)
    private val libraryPathsByView = MutableStateFlow<Map<String, List<Pair<String, String>>>>(emptyMap())
    private val syncMutex = Mutex()
    private val folderSyncMutex = Mutex()
    private val folderSyncScheduled = AtomicBoolean(false)

    override val capabilities: StateFlow<SourceCapabilities> = mutableCapabilities.asStateFlow()
    override val auth: AuthFacet = object : AuthFacet {
        override val state: StateFlow<AuthState> = mutableAuth.asStateFlow()
        override val signInFields = listOf(
            SetupField("server", "Server address", "https://jellyfin.example.com", SetupField.Kind.URL),
            SetupField("username", "Username"),
            SetupField("password", "Password", kind = SetupField.Kind.SECRET),
            SetupField("httpConsent", "For local HTTP, enter YES", kind = SetupField.Kind.TEXT),
        )

        override suspend fun signIn(values: Map<String, String>): SignInResult {
            val address = when (val verdict = NetworkPolicy.check(values["server"].orEmpty())) {
                is NetworkPolicy.Verdict.Secure -> verdict.base
                is NetworkPolicy.Verdict.LocalCleartext -> {
                    if (!values["httpConsent"].equals("YES", ignoreCase = true)) return SignInResult.Refused(SetupProblem.NEEDS_CLEARTEXT_CONSENT)
                    verdict.base
                }
                is NetworkPolicy.Verdict.Refused -> return SignInResult.Refused(verdict.problem)
            }
            val name = values["username"].orEmpty().trim()
            val password = values["password"].orEmpty()
            if (name.isBlank() || password.isBlank()) return SignInResult.Refused(SetupProblem.MISSING_FIELD)
            mutableAuth.value = AuthState.SigningIn
            val beforeServer = server
            return try {
                val publicInfo = transport.get(address, "/System/Info/Public")
                if (publicInfo is Outcome.Failure) return refused(publicInfo.error)
                val identity = (publicInfo as Outcome.Success).value
                val authResult = transport.postJson(
                    address,
                    "/Users/AuthenticateByName",
                    buildJsonObject { put("Username", name); put("Pw", password) },
                    authorization = authorization(deviceId),
                )
                val response = (authResult as? Outcome.Success)?.value ?: return refused((authResult as Outcome.Failure).error)
                val newToken = response.string("AccessToken") ?: return settle(SetupProblem.WRONG_CREDENTIALS)
                val newUser = response.obj("User")
                val newUserId = newUser?.string("Id") ?: return settle(SetupProblem.WRONG_CREDENTIALS)
                val display = newUser.string("Name") ?: name
                val serverId = identity.string("Id") ?: address
                val valuesToStore = mapOf(
                    KEY_SERVER to address,
                    KEY_SERVER_ID to serverId,
                    KEY_USER_ID to newUserId,
                    KEY_TOKEN to newToken,
                    KEY_USER_NAME to display,
                )
                try { credentials.write(SOURCE_ID, valuesToStore) } catch (_: Exception) { return settle(SetupProblem.UNKNOWN) }
                server = address
                userId = newUserId
                token = newToken
                userName = display
                cachedLibrary.value = null
                libraryPathsByView.value = emptyMap()
                mutableAuth.value = AuthState.SignedIn(display, "$serverId:$newUserId")
                updateCapabilities()
                SignInResult.SignedIn
            } catch (cancelled: CancellationException) {
                mutableAuth.value = if (beforeServer != null && token != null) AuthState.SignedIn(userName ?: "Jellyfin") else AuthState.SignedOut
                throw cancelled
            } catch (_: Exception) {
                settle(SetupProblem.UNREACHABLE)
            }
        }

        override suspend fun signOut() {
            runCatching {
                server?.let { s -> userId?.let { id -> transport.postJson(s, "/Sessions/Logout", buildJsonObject {}, authorization = authorization(deviceId, token, userId = id)) } }
            }
            credentials.delete(SOURCE_ID)
            server = null; userId = null; token = null; userName = null
            cachedLibrary.value = null
            libraryPathsByView.value = emptyMap()
            mutableAuth.value = AuthState.SignedOut
            updateCapabilities()
        }
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> = guarded { api ->
            val types = query.kinds.flatMap { kind -> when (kind) {
                SearchKind.TRACKS -> listOf("Audio")
                SearchKind.ALBUMS -> listOf("MusicAlbum")
                SearchKind.ARTISTS -> listOf("MusicArtist")
                SearchKind.PLAYLISTS -> listOf("Playlist")
                SearchKind.VIDEOS -> emptyList()
            } }.distinct()
            val json = api.get("/Users/${api.userId}/Items", mapOf(
                "searchTerm" to query.text.trim(), "IncludeItemTypes" to types.joinToString(","),
                "Recursive" to "true", "StartIndex" to query.offset.toString(), "Limit" to query.limit.coerceIn(1, 100).toString(),
                "Fields" to "Overview,Genres,MediaSources,ParentId,UserData,DateCreated",
            ))
            val items = json.array("Items")
            Outcome.Success(SearchResults(
                tracks = items.filter { it.string("Type") == "Audio" }.mapNotNull(::track),
                albums = items.filter { it.string("Type") == "MusicAlbum" }.map(::album),
                artists = items.filter { it.string("Type") == "MusicArtist" }.map(::artist),
                playlists = items.filter { it.string("Type") == "Playlist" }.map(::playlist),
            ))
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> = guarded { api ->
            val item = api.get("/Users/${api.userId}/Items/${path(ref.providerKey)}", mapOf("Fields" to "MediaSources,Genres,UserData"))
            track(item)?.let { Outcome.Success(it) } ?: Outcome.Failure(PodiumError.NotFound("track"))
        }

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = guarded { api ->
            val key = providerKey(id.value)
            val item = api.get("/Users/${api.userId}/Items/${path(key)}", mapOf("Fields" to "Genres,UserData"))
            val songs = api.get("/Users/${api.userId}/Items", mapOf("ParentId" to key, "IncludeItemTypes" to "Audio", "SortBy" to "ParentIndexNumber,IndexNumber", "SortOrder" to "Ascending", "Recursive" to "false", "Limit" to "500"))
            Outcome.Success(AlbumDetail(album(item), songs.array("Items").mapNotNull(::track)))
        }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = guarded { api ->
            val key = providerKey(id.value)
            val albums = api.get("/Users/${api.userId}/Items", mapOf("ArtistIds" to key, "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "Limit" to "500"))
            val songs = api.get("/Users/${api.userId}/Items", mapOf("ArtistIds" to key, "IncludeItemTypes" to "Audio", "Recursive" to "true", "Limit" to "500"))
            val person = api.get("/Users/${api.userId}/Items/${path(key)}")
            val summary = artist(person).copy(id = id)
            Outcome.Success(ArtistDetail(summary, albums.array("Items").map(::album), songs.array("Items").mapNotNull(::track)))
        }

        override suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> = guarded { api ->
            val key = providerKey(id.value)
            val item = api.get("/Users/${api.userId}/Items/${path(key)}")
            val songs = api.get("/Playlists/${path(key)}/Items", mapOf("UserId" to api.userId.orEmpty(), "Fields" to "Genres,UserData"))
            Outcome.Success(PlaylistDetail(playlist(item), songs.array("Items").mapNotNull(::track)))
        }

        override suspend fun folderContents(parentId: String?, offset: Int, limit: Int): Outcome<MusicFolderPage> = guarded { api ->
            if (parentId == null) {
                val roots = cachedGet(api, "/Users/${api.userId}/Views").array("Items")
                    .filter { it.string("CollectionType") == "music" }
                    .mapNotNull { root -> root.string("Id")?.let { id -> MusicFolderEntry("folder:$id", root.string("Name") ?: "Music", root.int("ChildCount")) } }
                Outcome.Success(MusicFolderPage(folders = roots))
            } else {
                when (val loaded = ensureLibrary()) {
                    is Outcome.Failure -> return@guarded loaded
                    is Outcome.Success -> Unit
                }
                val kind = parentId.substringBefore(':').takeIf { ':' in parentId } ?: "folder"
                val itemId = parentId.substringAfter(':', parentId)
                if (kind == "path") {
                    val viewAndPath = itemId.split('|', limit = 2)
                    val viewId = viewAndPath.getOrNull(0).orEmpty()
                    val directory = viewAndPath.getOrNull(1)?.let(::decodePath).orEmpty()
                    return@guarded Outcome.Success(pathDirectoryPage(viewId, directory, offset, limit))
                }
                val rawId = itemId.substringBefore('|')
                val pageArgs = mapOf(
                    "UserId" to api.userId,
                    "ParentId" to rawId,
                    "Recursive" to "false",
                    "StartIndex" to offset.coerceAtLeast(0).toString(),
                    "Limit" to limit.coerceIn(1, 500).toString(),
                    "Fields" to "Path,ParentId,MediaSources,UserData,ArtistItems,AlbumArtists,DateCreated",
                    "SortBy" to "SortName,IndexNumber",
                    "SortOrder" to "Ascending",
                )
                // ParentId with Recursive=false preserves the immediate hierarchy from Jellyfin.
                // A recursive artist query silently flattens nested directories.
                val page = cachedGet(api, "/Items", pageArgs)
                val items = page.array("Items")
                if (items.none { it.string("Type") == "Folder" } && libraryPathsByView.value[rawId].orEmpty().isNotEmpty()) {
                    return@guarded Outcome.Success(pathDirectoryPage(rawId, null, offset, limit))
                }
                Outcome.Success(MusicFolderPage(
                    folders = items.filter { it.string("Type") in setOf("Folder", "MusicAlbum", "MusicArtist") }.mapNotNull { folder ->
                        val id = folder.string("Id") ?: return@mapNotNull null
                        val prefix = when (folder.string("Type")) {
                            "MusicAlbum" -> "album"
                            "MusicArtist" -> "artist"
                            else -> "folder"
                        }
                        val name = folder.string("Name") ?: "Folder"
                        MusicFolderEntry("$prefix:$id|${java.net.URLEncoder.encode(name, Charsets.UTF_8.name())}", name, folder.int("ChildCount"))
                    },
                    tracks = items.mapNotNull(::track),
                ))
            }
        }
    }

    override val accountLibrary: AccountLibraryFacet = object : AccountLibraryFacet {
        override suspend fun likedSongs(offset: Int, limit: Int): Outcome<List<Track>> =
            Outcome.Failure(PodiumError.Unsupported("Jellyfin favorites"))

        override suspend fun playlists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>> =
            Outcome.Failure(PodiumError.Unsupported("Jellyfin playlists"))

        override suspend fun albums(offset: Int, limit: Int): Outcome<List<AlbumSummary>> = when (val result = ensureLibrary()) {
            is Outcome.Success -> Outcome.Success(albumSummaries(result.value).drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)))
            is Outcome.Failure -> result
        }

        override suspend fun artists(offset: Int, limit: Int): Outcome<List<ArtistSummary>> = when (val result = ensureLibrary()) {
            is Outcome.Success -> Outcome.Success(artistSummaries(result.value).drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)))
            is Outcome.Failure -> result
        }

        override suspend fun history(offset: Int, limit: Int): Outcome<List<HistoryEntry>> =
            Outcome.Failure(PodiumError.Unsupported("Jellyfin history"))
    }

    /**
     * Jellyfin's visible music views are the user's library. Podium's local library cache reads
     * this facet; search alone only feeds ONLINE search and never populates Music or Cover Flow.
     */
    override val library: LibraryFacet = object : LibraryFacet {
        override fun tracks(): Flow<List<Track>> = flow {
            if (cachedLibrary.value == null) ensureLibrary()
            if (folderSyncScheduled.compareAndSet(false, true)) syncScope.launch { primeFolderTree() }
            emitAll(cachedLibrary.filterNotNull().distinctUntilChanged())
        }

        override fun albums(): Flow<List<AlbumSummary>> = tracks().map(::albumSummaries)
        override fun artists(): Flow<List<ArtistSummary>> = tracks().map(::artistSummaries)
    }

    /** Re-fetch the server library; the app's Room sync observes the new StateFlow emission. */
    suspend fun syncNow(): Outcome<Int> = syncMutex.withLock {
        cachedLibrary.value = null
        libraryPathsByView.value = emptyMap()
        pageCache.clear()
        when (val result = loadLibraryTracks()) {
            is Outcome.Success -> {
                cachedLibrary.value = result.value
                folderSyncScheduled.set(true)
                primeFolderTree()
                Outcome.Success(result.value.size)
            }
            is Outcome.Failure -> result
        }
    }

    private suspend fun primeFolderTree() = folderSyncMutex.withLock {
        try {
            val api = apiOrNull() ?: return@withLock
            val views = cachedGet(api, "/Users/${api.userId}/Views").array("Items")
                .filter { it.string("CollectionType") == "music" }
            val visited = hashSetOf<String>()
            var parents = views.mapNotNull { it.string("Id") }
            var depth = 0
            while (parents.isNotEmpty() && depth < 16 && visited.size < MAX_FOLDER_NODES) {
                val next = mutableListOf<String>()
                for (parent in parents) {
                    if (!visited.add(parent)) continue
                    var offset = 0
                    do {
                        val query = mapOf(
                            "UserId" to api.userId,
                            "ParentId" to parent,
                            "Recursive" to "false",
                            "StartIndex" to offset.toString(),
                            "Limit" to FOLDER_PAGE_SIZE.toString(),
                            "Fields" to "Path,ParentId,MediaSources,UserData,ArtistItems,AlbumArtists,DateCreated",
                            "SortBy" to "SortName,IndexNumber",
                            "SortOrder" to "Ascending",
                        )
                        val page = cachedGet(api, "/Items", query)
                        val items = page.array("Items")
                        next += items.filter { it.string("Type") in FOLDER_CONTAINER_TYPES }.mapNotNull { it.string("Id") }
                        val total = page.int("TotalRecordCount") ?: items.size
                        offset += items.size
                    } while (items.size == FOLDER_PAGE_SIZE && offset < total)
                }
                parents = next
                depth++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A partial page cache remains useful; the next explicit sync can retry it.
        }
    }

    private suspend fun cachedGet(api: Session, endpoint: String, query: Map<String, String> = emptyMap()): JsonObject {
        val key = pageCacheKey(api, endpoint, query)
        pageCache.read(key)?.let { cached ->
            runCatching { Json.parseToJsonElement(cached).jsonObject }.getOrNull()?.let { return it }
        }
        val result = api.get(endpoint, query)
        pageCache.write(key, Json.encodeToString(JsonObject.serializer(), result))
        return result
    }

    private fun pageCacheKey(api: Session, endpoint: String, query: Map<String, String>): String = buildString {
        append(api.server).append('|').append(api.userId).append('|').append(endpoint)
        query.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
    }.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }

    private suspend fun ensureLibrary(): Outcome<List<Track>> {
        cachedLibrary.value?.let { return Outcome.Success(it) }
        return syncMutex.withLock {
            cachedLibrary.value?.let { return@withLock Outcome.Success(it) }
            when (val loaded = loadLibraryTracks()) {
                is Outcome.Success -> {
                    cachedLibrary.value = loaded.value
                    loaded
                }
                is Outcome.Failure -> loaded
            }
        }
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)
        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            val base = server ?: return FacetResolution.Failed(HealthOutcome.AUTH_FAILURE)
            val uid = userId ?: return FacetResolution.Failed(HealthOutcome.AUTH_FAILURE)
            val authToken = token ?: return FacetResolution.Failed(HealthOutcome.AUTH_FAILURE)
            val key = track.source.providerKey
            val media = PlayableMedia(
                uri = "$base/Audio/${path(key)}/stream?static=true&UserId=${path(uid)}",
                mimeType = "audio/*", durationMs = track.durationMs,
                headers = mapOf("Authorization" to authorization(deviceId, authToken, userId = uid)),
                sourceId = SOURCE_ID, cacheKey = track.id.value,
            )
            return FacetResolution.Resolved(PlaybackTarget.DirectStream(track.id, media))
        }
    }

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? {
            val api = apiOrNull() ?: return null
            val itemId = ref.key.substringBefore('|')
            val tag = ref.key.substringAfter('|', "")
            val url = "/Items/${path(itemId)}/Images/Primary" + if (tag.isEmpty()) "?maxWidth=${sizePx.coerceIn(64, 1200)}" else "?tag=${path(tag)}&maxWidth=${sizePx.coerceIn(64, 1200)}"
            return (transport.getBytes(api.server, url, authorization(deviceId, api.token, userId = api.userId)) as? Outcome.Success)?.value?.let { ArtworkPayload.Bytes(it, "image/jpeg") }
        }
    }

    private suspend fun <T> guarded(block: suspend (Session) -> Outcome<T>): Outcome<T> {
        val session = apiOrNull() ?: return Outcome.Failure(if (mutableAuth.value is AuthState.Expired) PodiumError.AuthExpired() else PodiumError.AuthRequired())
        val result = try { block(session) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: ApiException) { Outcome.Failure(failure.error) } catch (_: Exception) { Outcome.Failure(PodiumError.Network()) }
        if (result is Outcome.Failure && result.error is PodiumError.AuthExpired) {
            credentials.delete(SOURCE_ID); token = null; mutableAuth.value = AuthState.Expired; updateCapabilities()
        }
        return result
    }

    private fun apiOrNull() = if (server != null && userId != null && token != null) Session(server!!, userId!!, token!!, deviceId, transport) else null

    private fun settle(problem: SetupProblem): SignInResult {
        mutableAuth.value = AuthState.SignedOut
        updateCapabilities()
        return SignInResult.Refused(problem)
    }

    private fun refused(error: PodiumError): SignInResult = settle(when (error) {
        is PodiumError.Network, PodiumError.Offline, is PodiumError.Server -> SetupProblem.UNREACHABLE
        else -> SetupProblem.WRONG_CREDENTIALS
    })

    private fun updateCapabilities() { mutableCapabilities.value = capabilities(mutableAuth.value) }

    private suspend fun loadLibraryTracks(): Outcome<List<Track>> = guarded { api ->
        val views = api.get("/Users/${api.userId}/Views").array("Items")
        val musicViews = views.filter { it.string("CollectionType") == "music" }
        val tracks = mutableListOf<Track>()
        val pathsByView = linkedMapOf<String, MutableList<Pair<String, String>>>()
        for (view in musicViews) {
            val viewId = view.string("Id") ?: continue
            var offset = 0
            var total = Int.MAX_VALUE
            while (offset < total) {
                val page = api.get("/Users/${api.userId}/Items", mapOf(
                    "ParentId" to viewId,
                    "IncludeItemTypes" to "Audio",
                    "Recursive" to "true",
                    "StartIndex" to offset.toString(),
                    "Limit" to LIBRARY_PAGE_SIZE.toString(),
                    "Fields" to "Path,Genres,MediaSources,ParentId,UserData,DateCreated,ArtistItems,AlbumArtists",
                ))
                val items = page.array("Items")
                tracks += items.mapNotNull(::track)
                pathsByView.getOrPut(viewId) { mutableListOf() } += items.mapNotNull { item ->
                    val key = item.string("Id") ?: return@mapNotNull null
                    val path = item.string("Path") ?: return@mapNotNull null
                    key to path
                }
                total = page.int("TotalRecordCount") ?: (offset + items.size)
                if (items.isEmpty()) break
                offset += items.size
            }
        }
        libraryPathsByView.value = pathsByView.mapValues { (_, paths) -> paths.toList() }
        Outcome.Success(tracks.distinctBy { it.id })
    }

    /** Build the original filesystem hierarchy when Jellyfin exposes only virtual artist/album nodes. */
    private fun pathDirectoryPage(viewId: String, directory: String?, offset: Int, limit: Int): MusicFolderPage {
        val paths = libraryPathsByView.value[viewId].orEmpty()
        if (paths.isEmpty()) return MusicFolderPage()
        val entries = paths.mapNotNull { (id, path) ->
            val segments = pathSegments(path)
            if (segments.size < 2) return@mapNotNull null
            val dirs = segments.dropLast(1)
            Triple(id, path, dirs)
        }
        if (entries.isEmpty()) return MusicFolderPage()
        val common = if (directory == null) commonDirectoryPrefix(entries.map { it.third }) else pathSegments(directory)
        val folders = linkedMapOf<String, Pair<String, Int>>()
        val directTracks = mutableListOf<Track>()
        val tracksByProviderId = cachedLibrary.value.orEmpty().associateBy { it.source.providerKey }
        for ((id, _, dirs) in entries) {
            if (!dirs.startsWithSegments(common)) continue
            val relative = dirs.drop(common.size)
            if (relative.isEmpty()) {
                tracksByProviderId[id]?.let(directTracks::add)
            } else {
                val name = relative.first()
                val nextDirectory = (common + name).joinToString("/")
                val key = nextDirectory.lowercase()
                val current = folders[key]
                folders[key] = (current?.first ?: name) to ((current?.second ?: 0) + 1)
            }
        }
        return MusicFolderPage(
            folders = folders.values.map { (name, count) ->
                val directoryPath = (common + name).joinToString("/")
                MusicFolderEntry("path:$viewId|${encodePath(directoryPath)}", name, count)
            }.sortedBy { it.name.lowercase() }.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)),
            tracks = directTracks.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }))
                .drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)),
        )
    }

    private fun pathSegments(path: String): List<String> = path.replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)

    private fun commonDirectoryPrefix(paths: List<List<String>>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val shortest = paths.minOf { it.size }
        var length = 0
        while (length < shortest && paths.all { it[length].equals(paths.first()[length], ignoreCase = true) }) length++
        return paths.first().take(length)
    }

    private fun List<String>.startsWithSegments(prefix: List<String>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it].equals(prefix[it], ignoreCase = true) }

    private fun encodePath(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decodePath(value: String) = java.net.URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun albumSummaries(tracks: List<Track>): List<AlbumSummary> = tracks
        .filter { it.album?.id != null }
        .groupBy { it.album!!.id!! }
        .map { (id, songs) ->
            val first = songs.first()
            val album = first.album!!
            AlbumSummary(
                id = id,
                title = album.title,
                artistDisplay = album.albumArtist ?: first.artistDisplay,
                artwork = first.artwork,
                year = first.releaseDate?.year,
                trackCount = songs.size,
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun artistSummaries(tracks: List<Track>): List<ArtistSummary> = tracks
        .flatMap { it.artists }
        .groupBy { it.id ?: ArtistId.of(SOURCE_ID, it.name) }
        .map { (id, credits) -> ArtistSummary(id, credits.first().name, trackCount = credits.size) }
        .sortedBy { it.name.lowercase() }

    private fun capabilities(auth: AuthState): SourceCapabilities {
        val signedIn = auth is AuthState.SignedIn
        val gated = if (signedIn) CapabilityState.Available else CapabilityState(CapabilityStatus.REQUIRES_SIGN_IN, "Connect a Jellyfin server", CapabilityAction.SignIn("Connect"))
        return SourceCapabilities(mapOf(
        Capability.AUTHENTICATION to CapabilityState.Available,
            Capability.SEARCH to gated, Capability.BROWSE to gated, Capability.LIBRARY to gated, Capability.ACCOUNT_LIBRARY to gated,
            Capability.FOLDER_BROWSING to gated,
            Capability.DIRECT_STREAM to gated, Capability.ARTWORK to gated,
            Capability.LIKES to CapabilityState(CapabilityStatus.UNAVAILABLE, "Jellyfin likes are not wired yet"),
            Capability.PLAYLISTS to CapabilityState(CapabilityStatus.DEGRADED, "Playlists can be browsed and played"),
            Capability.DOWNLOADS to CapabilityState(CapabilityStatus.UNAVAILABLE, "Offline downloads are not wired yet"),
        ))
    }

    private fun track(j: JsonObject): Track? {
        if (j.string("Type") != "Audio") return null
        val key = j.string("Id") ?: return null
        val title = j.string("Name") ?: return null
        val artistItems = j.array("ArtistItems")
        val artists = artistItems.mapNotNull { item ->
            val name = item.string("Name") ?: return@mapNotNull null
            ArtistCredit(name, id = item.string("Id")?.let { ArtistId.of(SOURCE_ID, it) })
        }.ifEmpty {
            (j["Artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .ifEmpty { listOfNotNull(j.string("AlbumArtist")) }
                .map { ArtistCredit(it, id = ArtistId.of(SOURCE_ID, it)) }
        }
        val albumKey = j.string("AlbumId")
        val albumTitle = j.string("Album")
        val artwork = j.string("PrimaryImageTag")?.let { ArtworkRef(SOURCE_ID, "$key|$it") }
        val date = j.int("ProductionYear")?.let(::PartialDate)
        val codec = j.obj("MediaSources")?.array("MediaSources")?.firstOrNull()?.array("MediaStreams")?.firstOrNull { it.string("Type") == "Audio" }?.string("Codec")
        val duration = j.long("RunTimeTicks")?.div(10_000)
        return Track(
            id = TrackId.of(SOURCE_ID, key), source = SourceRef(SOURCE_ID, key), title = title,
            artists = artists, album = albumTitle?.let { AlbumRef(it, albumKey?.let { k -> AlbumId.of(SOURCE_ID, k) }, j.string("AlbumArtist")) },
            durationMs = duration, artwork = artwork, releaseDate = date,
            discNumber = j.int("ParentIndexNumber"), trackNumber = j.int("IndexNumber"),
            availability = Availability.Playable, routes = setOf(PlaybackRoute.DIRECT), kind = MediaKind.SONG,
        )
    }

    private fun album(j: JsonObject): AlbumSummary {
        val id = j.string("Id").orEmpty()
        return AlbumSummary(AlbumId.of(SOURCE_ID, id), j.string("Name").orEmpty(), j.string("AlbumArtist") ?: j.array("Artists").joinToString(", ") { it.jsonPrimitive.content },
            j.string("PrimaryImageTag")?.let { ArtworkRef(SOURCE_ID, "$id|$it") }, j.int("ProductionYear"), j.int("ChildCount"))
    }

    private fun artist(j: JsonObject): ArtistSummary {
        val id = j.string("Id").orEmpty()
        return ArtistSummary(ArtistId.of(SOURCE_ID, id), j.string("Name").orEmpty(), j.string("PrimaryImageTag")?.let { ArtworkRef(SOURCE_ID, "$id|$it") })
    }

    private fun playlist(j: JsonObject): PlaylistSummary {
        val id = j.string("Id").orEmpty()
        return PlaylistSummary(PlaylistId.of(SOURCE_ID, id), j.string("Name").orEmpty(), j.string("Owner" ) ?: "Jellyfin",
            j.string("PrimaryImageTag")?.let { ArtworkRef(SOURCE_ID, "$id|$it") }, j.int("ChildCount"))
    }

    private data class Session(val server: String, val userId: String, val token: String, val deviceId: String, val transport: JellyfinTransport) {
        suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject = when (val r = transportCall(path, query)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> throw ApiException(r.error)
        }
        private suspend fun transportCall(path: String, query: Map<String, String>) = transport.get(server, path, query, authorization(deviceId, token, userId))
    }

    private class ApiException(val error: PodiumError) : IOException()

    companion object {
        val SOURCE_ID = SourceId("jellyfin")
        private const val KEY_SERVER = "server"
        private const val KEY_SERVER_ID = "server-id"
        private const val KEY_USER_ID = "user-id"
        private const val KEY_TOKEN = "access-token"
        private const val KEY_USER_NAME = "user-name"
        private const val LIBRARY_PAGE_SIZE = 200
        private const val FOLDER_PAGE_SIZE = 500
        private const val MAX_FOLDER_NODES = 10_000
        private val FOLDER_CONTAINER_TYPES = setOf("Folder", "MusicAlbum", "MusicArtist")
        internal fun authorization(deviceId: String, token: String? = null, userId: String? = null) = buildString {
            append("MediaBrowser Client=\"Podium\", Device=\"Android\", DeviceId=\"").append(deviceId).append("\", Version=\"0.1.0\"")
            if (userId != null) append(", UserId=\"").append(userId).append('"')
            if (token != null) append(", Token=\"").append(token).append('"')
        }

        private fun path(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        private fun providerKey(id: String): String = id.substringAfter('|', id)
    }
}

interface JellyfinTransport {
    suspend fun get(server: String, path: String, query: Map<String, String> = emptyMap(), authorization: String? = null): Outcome<JsonObject>
    suspend fun postJson(server: String, path: String, body: JsonObject, authorization: String? = null): Outcome<JsonObject>
    suspend fun getBytes(server: String, path: String, authorization: String? = null): Outcome<ByteArray>
}

class OkHttpJellyfinTransport(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(false).build(),
) : JellyfinTransport {
    override suspend fun get(server: String, path: String, query: Map<String, String>, authorization: String?): Outcome<JsonObject> = execute(server, path, query, authorization, null)
    override suspend fun postJson(server: String, path: String, body: JsonObject, authorization: String?): Outcome<JsonObject> = execute(server, path, emptyMap(), authorization, Json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
    override suspend fun getBytes(server: String, path: String, authorization: String?): Outcome<ByteArray> = try { request(server, path, emptyMap(), authorization, null).use { response ->
        if (response.code in 200..299) Outcome.Success(response.body?.bytes() ?: ByteArray(0)) else Outcome.Failure(error(response.code))
    } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Outcome.Failure(PodiumError.Network()) }

    private suspend fun execute(server: String, path: String, query: Map<String, String>, authorization: String?, body: okhttp3.RequestBody?): Outcome<JsonObject> =
        try { request(server, path, query, authorization, body).use { r -> if (r.code in 200..299) {
            val text = r.body?.string().orEmpty()
            Outcome.Success(if (text.isBlank()) buildJsonObject {} else Json.parseToJsonElement(text).jsonObject)
        } else Outcome.Failure(error(r.code)) } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Outcome.Failure(PodiumError.Network()) }

    private suspend fun request(server: String, path: String, query: Map<String, String>, authorization: String?, body: okhttp3.RequestBody?): Response {
        val base = server.toHttpUrl()
        val url = base.newBuilder().addPathSegments(path.removePrefix("/")).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        if (!NetworkPolicy.permits(url.toString())) throw IOException("blocked by network policy")
        val req = Request.Builder().url(url).header("Accept", "application/json").header("Authorization", authorization ?: "MediaBrowser Client=\"Podium\", Device=\"Android\", Version=\"0.1.0\"").apply {
            if (body != null) post(body)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
    }

    private fun error(code: Int): PodiumError = when (code) {
        401, 403 -> PodiumError.AuthExpired()
        404 -> PodiumError.NotFound("Jellyfin item")
        429 -> PodiumError.RateLimited()
        in 500..599 -> PodiumError.Server(code)
        else -> PodiumError.Server(code)
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

private val Json = Json { ignoreUnknownKeys = true; isLenient = true }
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
