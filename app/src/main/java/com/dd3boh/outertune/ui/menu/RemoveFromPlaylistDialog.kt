package com.dd3boh.outertune.ui.menu


import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.component.ListDialog
import com.dd3boh.outertune.ui.component.PlaylistListItem
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoveFromPlaylistDialog(
    isVisible: Boolean,
    onGetSong: suspend (Playlist) -> List<String>, // list of song ids. Songs should be inserted to database in this function.
    onDismiss: () -> Unit = {},
    onRemoved: (() -> Unit)? = null,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf(emptyList<Playlist>()) }

    LaunchedEffect(Unit) {
        database.localPlaylistsByCreateDateAsc().collect {
            playlists = it.asReversed()
        }
    }

    if (isVisible) {
        ListDialog(onDismiss = onDismiss) {
            items(playlists) { playlist ->
                PlaylistListItem(
                    playlist = playlist,
                    modifier = Modifier.clickable {
                        coroutineScope.launch(Dispatchers.IO) {
                            val songIds = onGetSong(playlist)
                            database.removeSongsFromPlaylist(playlist, songIds)

                            if (!playlist.playlist.isLocal) {
                                playlist.playlist.browseId?.let { browseId ->
                                    songIds.forEach { songId ->
                                        YouTube.removeFromPlaylist(browseId, songId, browseId)
                                    }
                                }
                            }

                            withContext(Dispatchers.Main) {
                                onDismiss()
                                onRemoved?.invoke()
                            }
                        }
                    }
                )
            }
        }
    }
}