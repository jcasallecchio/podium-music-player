package app.podium.sources.jellyfin

import app.podium.core.common.Outcome
import app.podium.core.model.AlbumId
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.sources.api.AuthState
import app.podium.sources.api.CredentialStore
import app.podium.sources.api.InMemoryCredentialStore
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinMusicSourceTest {
    @Test
    fun `valid credentials create a server-scoped session and are stored through credential store`() = runTest {
        val transport = FakeTransport()
        val credentials = InMemoryCredentialStore()
        val source = JellyfinMusicSource(credentials, transport, "device-fixed")

        val result = source.auth!!.signIn(mapOf(
            "server" to "https://music.example.test/jellyfin/",
            "username" to "listener",
            "password" to "not-logged",
        ))

        assertEquals(SignInResult.SignedIn, result)
        assertEquals(AuthState.SignedIn("Listener", "server-42:user-7"), source.auth!!.state.value)
        assertEquals("https://music.example.test/jellyfin", credentials.read(JellyfinMusicSource.SOURCE_ID)?.get("server"))
        assertEquals("session-token", credentials.read(JellyfinMusicSource.SOURCE_ID)?.get("access-token"))
        assertNull(credentials.read(JellyfinMusicSource.SOURCE_ID)?.get("password"))
        assertTrue(transport.authHeader.orEmpty().contains("DeviceId=\"device-fixed\""))
    }

    @Test
    fun `public cleartext server address is refused before any request`() = runTest {
        val transport = FakeTransport()
        val source = JellyfinMusicSource(InMemoryCredentialStore(), transport)

        val result = source.auth!!.signIn(mapOf(
            "server" to "http://music.example.test:8096", "username" to "listener", "password" to "pw",
        ))

        assertEquals(SignInResult.Refused(SetupProblem.INSECURE_ADDRESS), result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `catalog search asks Jellyfin for one page and maps audio album ids and art`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport().apply { searchResponse = Json.parseToJsonElement(SEARCH_PAGE).jsonObject }
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val result = assertIs<Outcome.Success<app.podium.sources.api.SearchResults>>(
            source.catalog!!.search(SearchQuery("night drive", offset = 50, limit = 25, kinds = setOf(SearchKind.TRACKS, SearchKind.ALBUMS))),
        ).value

        assertEquals(50, transport.lastQuery["StartIndex"]?.toInt())
        assertEquals("Audio,MusicAlbum", transport.lastQuery["IncludeItemTypes"])
        assertEquals(1, result.tracks.size)
        val track = result.tracks.single()
        assertEquals(TrackId.of(SourceId("jellyfin"), "track-1"), track.id)
        assertEquals("Midnight", track.title)
        assertEquals("Northbound", track.artistDisplay)
        assertEquals("Record One", track.album?.title)
        assertEquals(2, track.discNumber)
        assertEquals(4, track.trackNumber)
        assertEquals(AlbumId.of(SourceId("jellyfin"), "album-1"), track.album?.id)
        assertNotNull(track.artwork)
        assertEquals(1, result.albums.size)
    }

    @Test
    fun `library enumerates only the users music views and paginates audio`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport()
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val tracks = source.library!!.tracks().first()

        assertEquals(listOf("music-view"), transport.libraryViewIds)
        assertEquals(1, tracks.size)
        assertEquals("track-1", tracks.single().source.providerKey)
    }

    @Test
    fun `folder album loads its direct Jellyfin children`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport()
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val page = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(
            source.catalog!!.folderContents("album:album-1"),
        ).value

        assertEquals(listOf("Midnight"), page.tracks.map { it.title })
        assertTrue(page.folders.isEmpty())
        assertEquals("/Items", transport.lastPath)
        assertEquals("album-1", transport.lastQuery["ParentId"])
        assertEquals("false", transport.lastQuery["Recursive"])
        assertTrue("Path" in transport.lastQuery["Fields"].orEmpty())
        val callsAfterFirstBrowse = transport.calls
        source.catalog!!.folderContents("album:album-1")
        assertEquals(callsAfterFirstBrowse, transport.calls, "the second visit comes from the page cache")
    }

    @Test
    fun `account albums and artists come from the synced Jellyfin library`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport()
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val albums = assertIs<Outcome.Success<List<app.podium.sources.api.AlbumSummary>>>(source.accountLibrary!!.albums(0, 10)).value
        val artists = assertIs<Outcome.Success<List<app.podium.sources.api.ArtistSummary>>>(source.accountLibrary!!.artists(0, 10)).value

        assertEquals(listOf("Record One"), albums.map { it.title })
        assertEquals(listOf("Northbound"), artists.map { it.name })
    }

    @Test
    fun `folder artist preserves album and track children from Jellyfin`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport()
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val page = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(
            source.catalog!!.folderContents("artist:artist-1|Northbound"),
        ).value

        assertEquals(listOf("Record One"), page.folders.map { it.name })
        assertEquals(listOf("Midnight"), page.tracks.map { it.title })
        assertEquals("/Items", transport.lastPath)
        assertEquals("artist-1", transport.lastQuery["ParentId"])
        assertEquals("false", transport.lastQuery["Recursive"])
        assertTrue("Path" in transport.lastQuery["Fields"].orEmpty())
    }

    @Test
    fun `folder view falls back to physical directories from track paths`() = runTest {
        val store: CredentialStore = InMemoryCredentialStore().also {
            it.write(JellyfinMusicSource.SOURCE_ID, mapOf(
                "server" to "https://music.example.test", "user-id" to "user-7", "access-token" to "token", "user-name" to "Listener",
            ))
        }
        val transport = FakeTransport().apply { libraryResponse = PATHS_PAGE }
        val source = JellyfinMusicSource(store, transport, "device-fixed")

        val root = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(source.catalog!!.folderContents(null)).value
        val library = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(
            source.catalog!!.folderContents(root.folders.single().id),
        ).value
        assertEquals(listOf("Northbound", "Westward"), library.folders.map { it.name })

        val artist = library.folders.first()
        val artistPage = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(
            source.catalog!!.folderContents(artist.id),
        ).value
        assertEquals(listOf("Record One"), artistPage.folders.map { it.name })
        assertTrue("Path" in transport.lastQuery["Fields"].orEmpty())

        val album = artistPage.folders.single()
        val albumPage = assertIs<Outcome.Success<app.podium.sources.api.MusicFolderPage>>(
            source.catalog!!.folderContents(album.id),
        ).value
        assertEquals(listOf("Midnight"), albumPage.tracks.map { it.title })
    }

    private class FakeTransport : JellyfinTransport {
        var calls = 0
        var authHeader: String? = null
        var lastPath: String? = null
        var lastQuery = emptyMap<String, String>()
        var searchResponse: JsonObject? = null
        var libraryResponse: String? = null
        var libraryViewIds = emptyList<String>()

        override suspend fun get(server: String, path: String, query: Map<String, String>, authorization: String?): Outcome<JsonObject> {
            calls++
            authHeader = authorization
            lastPath = path
            lastQuery = query
            return when (path) {
                "/System/Info/Public" -> Outcome.Success(Json.parseToJsonElement("""{"Id":"server-42","Version":"10.11.0"}""").jsonObject)
                "/Users/user-7/Views" -> Outcome.Success(Json.parseToJsonElement("""{"Items":[{"Id":"music-view","Name":"Music","CollectionType":"music"},{"Id":"playlist-view","Name":"Playlists","CollectionType":"playlists"}]}""").jsonObject)
                "/Users/user-7/Items", "/Items" -> {
                    query["ParentId"]?.let { libraryViewIds = libraryViewIds + it }
                    if (query["ParentId"] == "album-1") Outcome.Success(Json.parseToJsonElement(ALBUM_CHILDREN).jsonObject)
                    else if (query["ParentId"] == "artist-1") Outcome.Success(Json.parseToJsonElement(SEARCH_PAGE).jsonObject)
                    else if (query["ParentId"] == "music-view") Outcome.Success(Json.parseToJsonElement(libraryResponse ?: SEARCH_PAGE).jsonObject)
                    else Outcome.Success(searchResponse ?: Json.parseToJsonElement("""{"Items":[]}""").jsonObject)
                }
                else -> Outcome.Success(Json.parseToJsonElement("{}" ).jsonObject)
            }
        }

        override suspend fun postJson(server: String, path: String, body: JsonObject, authorization: String?): Outcome<JsonObject> {
            calls++
            authHeader = authorization
            return Outcome.Success(Json.parseToJsonElement("""{"AccessToken":"session-token","User":{"Id":"user-7","Name":"Listener"}}""").jsonObject)
        }

        override suspend fun getBytes(server: String, path: String, authorization: String?): Outcome<ByteArray> = Outcome.Success(ByteArray(0))
    }

    private companion object {
        const val SEARCH_PAGE = """{
          "Items": [
            {"Id":"track-1","Type":"Audio","Name":"Midnight","Artists":["Northbound"],"Album":"Record One","AlbumId":"album-1","RunTimeTicks":2430000000,"ParentIndexNumber":2,"IndexNumber":4,"PrimaryImageTag":"img-1","ProductionYear":2024},
            {"Id":"album-1","Type":"MusicAlbum","Name":"Record One","AlbumArtist":"Northbound","ProductionYear":2024,"ChildCount":10,"PrimaryImageTag":"art-1"}
          ], "TotalRecordCount": 2
        }"""
        const val ALBUM_CHILDREN = """{
          "Items": [
            {"Id":"track-1","Type":"Audio","Name":"Midnight","Artists":["Northbound"],"Album":"Record One","AlbumId":"album-1","RunTimeTicks":2430000000}
          ], "TotalRecordCount": 1
        }"""
        const val PATHS_PAGE = """{
          "Items": [
            {"Id":"track-1","Type":"Audio","Name":"Midnight","Artists":["Northbound"],"Album":"Record One","AlbumId":"album-1","Path":"\\\\192.168.15.83\\orville\\Musicas\\Northbound\\Record One\\Midnight.flac","RunTimeTicks":2430000000},
            {"Id":"track-2","Type":"Audio","Name":"Daybreak","Artists":["Westward"],"Album":"Record Two","AlbumId":"album-2","Path":"\\\\192.168.15.83\\orville\\Musicas\\Westward\\Record Two\\Daybreak.flac","RunTimeTicks":2430000000}
          ], "TotalRecordCount": 2
        }"""
    }
}
