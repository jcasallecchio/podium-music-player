package app.podium.feature.online

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.podium.core.common.Outcome
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Track
import app.podium.sources.api.MusicFolderEntry

@Composable
internal fun OnlineFoldersScreen(
    parentId: String?,
    title: String,
    repository: OnlineRepository,
    actions: OnlineActions,
    navigate: (OnlinePlace) -> Unit,
) {
    val page = rememberRemote(parentId) { repository.folderContents(parentId) }
    when (page) {
        Remote.Loading -> LoadingScreen("Loading folders")
        is Remote.Failed -> OnlineMessage(page.error)
        is Remote.Ready -> {
            val rows = buildList {
                page.value.folders.forEach { add(FolderRow.Directory(it)) }
                page.value.tracks.forEach { add(FolderRow.Song(it)) }
            }
            if (rows.isEmpty()) {
                CenteredMessage(app.podium.core.designsystem.symbol.PodiumSymbol.Library, "No music here", "This folder has no music or subfolders.")
                return
            }
            val focus = rememberFocusListState("jellyfin-folders:${parentId ?: "root"}")
            val overlayHost = overlay()
            val scope = rememberCoroutineScope()
            val activate: (Int) -> Unit = { index ->
                when (val row = rows.getOrNull(index)) {
                    is FolderRow.Directory -> navigate(OnlinePlace.Folder(row.folder.id, row.folder.name))
                    is FolderRow.Song -> {
                        val songs = rows.filterIsInstance<FolderRow.Song>().map { it.track }
                        val start = songs.indexOfFirst { it.id == row.track.id }.coerceAtLeast(0)
                        actions.play(songs, start, title)
                    }
                    null -> Unit
                }
            }
            val longPress: (Int) -> Unit = { index ->
                when (val row = rows.getOrNull(index)) {
                    is FolderRow.Song -> showTrackMenu(overlayHost, row.track, repository, actions, scope, navigate)
                    // Directories are navigation entries; holding Center opens the focused folder.
                    else -> activate(index)
                }
            }
            ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
            FocusList(
                items = rows,
                state = focus,
                key = { row -> when (row) {
                    is FolderRow.Directory -> "folder:${row.folder.id}"
                    is FolderRow.Song -> "track:${row.track.id.value}"
                } },
                contentPadding = LocalScreenInsets.current.listPadding(),
                onActivate = activate,
                onLongPress = longPress,
                preview = { row -> when (row) {
                    is FolderRow.Directory -> MenuPreview.Instrument
                    is FolderRow.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
                } },
                modifier = Modifier.fillMaxSize(),
            ) { row, _, focused -> when (row) {
                is FolderRow.Directory -> MenuRow(row.folder.name, focused, value = row.folder.itemCount?.toString())
                is FolderRow.Song -> OnlineTrackRow(row.track, focused)
            } }
        }
    }
}

private sealed interface FolderRow {
    data class Directory(val folder: MusicFolderEntry) : FolderRow
    data class Song(val track: Track) : FolderRow
}
