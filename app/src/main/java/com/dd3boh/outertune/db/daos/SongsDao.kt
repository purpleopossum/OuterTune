package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.utils.fixFilePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneOffset

@Dao
interface SongsDao {

    // region Gets
    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId")
    fun song(songId: String?): Flow<Song?>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' AND inLibrary IS NOT NULL LIMIT :previewSize")
    fun searchSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun searchSongsInDb(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' AND isLocal = 1 LIMIT :previewSize")
    fun searchSongsAllLocal(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>


    /**
     * Does not include unavailable songs
     */
    fun searchSongsAllLocalInDir(dir: String, query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>> {
        return _searchSongsAllLocalInDir(fixFilePath(dir), query, previewSize)
    }

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :dir || '%' AND title LIKE '%' || :query || '%'
        LIMIT :previewSize
        """)
    fun _searchSongsAllLocalInDir(dir: String, query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT *
        FROM song
        WHERE id IN (SELECT songId
                     FROM event
                     WHERE timestamp > :fromTimeStamp
                     GROUP BY songId
                     ORDER BY SUM(playTime) DESC
                     LIMIT :limit
                     OFFSET :offset)
    """)
    fun mostPlayedSongs(fromTimeStamp: Long, limit: Int = 6, offset: Int = 0): Flow<List<Song>>

    @Query("SELECT sum(count) from playCount WHERE song = :songId")
    fun getLifetimePlayCount(songId: String?): Flow<Int>

    @Query("SELECT sum(count) from playCount WHERE song = :songId AND year = :year")
    fun getPlayCountByYear(songId: String?, year: Int): Flow<Int>

    @Query("SELECT count from playCount WHERE song = :songId AND year = :year AND month = :month")
    fun getPlayCountByMonth(songId: String?, year: Int, month: Int): Flow<Int>

    @Transaction
    @Query("SELECT * FROM song WHERE liked AND dateDownload IS NULL")
    fun likedSongsNotDownloaded(): Flow<List<Song>>

    // region Songs Sort
    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY rowId")
    fun songsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary")
    fun songsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY date")
    fun songsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY dateModified")
    fun songsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun songsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE inLibrary IS NOT NULL 
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun songsByArtistAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL LIMIT :previewSize")
    fun artistSongsPreview(artistId: String, previewSize: Int = 3): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY totalPlayTime")
    fun songsByPlayTimeAsc(): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("""
        SELECT song.*, (SELECT SUM(playCount.count) 
            FROM playCount 
            WHERE playCount.song = song.id) AS pc 
        FROM song 
        WHERE inLibrary IS NOT NULL 
        ORDER BY pc ASC
    """)
    fun songsByPlayCountAsc(): Flow<List<Song>>

    fun songs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> songsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> songsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> songsByReleaseDateAsc()
            SongSortType.NAME -> songsByNameAsc()
            SongSortType.ARTIST -> songsByArtistAsc()
            SongSortType.PLAY_TIME -> songsByPlayTimeAsc()
            SongSortType.PLAY_COUNT -> songsByPlayCountAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 1 and inLibrary IS NOT NULL")
    fun allLocalSongs(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 1 AND localpath LIKE :filter || '%'
    """)
    fun localSongsInDir(filter: String): Flow<List<Song>>

    /**
     * Does not include unavailable songs
     */
    fun localSongsInDirShallow(filter: String): Flow<List<Song>> {
        return _localSongsInDirShallow(fixFilePath(filter))
    }

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :filter || '%' 
        AND instr(substr(localpath, length(:filter) + 1), '/') = 0
        UNION
        SELECT * FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :filter || '%'
        GROUP BY rtrim(localPath, replace(localPath, '/', ''))
    """)
    fun _localSongsInDirShallow(filter: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT count(*) FROM song WHERE isLocal = 1 and inLibrary IS NOT NULL AND localpath LIKE :path || '%'")
    fun localSongCountInPath(path: String): Flow<Int>

    @Query("""
        SELECT * FROM song
        WHERE localPath IN (
            SELECT localPath
            FROM song
            GROUP BY localPath
            HAVING COUNT(*) > 1
        )
        ORDER BY localPath
    """)
    fun duplicatedLocalSongs(): Flow<List<SongEntity>>
    // endregion

    // region Liked Songs Sort
    @Query("SELECT COUNT(1) FROM song WHERE liked")
    fun likedSongsCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY rowId")
    fun likedSongsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate")
    fun likedSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY date")
    fun likedSongsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY dateModified")
    fun likedSongsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY title COLLATE NOCASE ASC")
    fun likedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE liked 
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun likedSongsByArtistAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY totalPlayTime")
    fun likedSongsByPlayTimeAsc(): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("""
        SELECT song.*, (SELECT SUM(playCount.count) 
            FROM playCount 
            WHERE playCount.song = song.id) AS pc 
        FROM song 
        WHERE liked IS NOT NULL 
        ORDER BY pc ASC
    """)
    fun likedSongsByPlayCountAsc(): Flow<List<Song>>

    fun likedSongs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> likedSongsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> likedSongsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> likedSongsByReleaseDateAsc()
            SongSortType.NAME -> likedSongsByNameAsc()
            SongSortType.ARTIST -> likedSongsByArtistAsc()
            SongSortType.PLAY_TIME -> likedSongsByPlayTimeAsc()
            SongSortType.PLAY_COUNT -> likedSongsByPlayCountAsc()
        }.map { it.reversed(descending) }
    // endregion

    // region downloaded Songs utils
    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL AND dateDownload IS NOT 0")
    fun downloadedSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload = 0")
    fun downloadQueuedSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL")
    fun downloadedOrQueuedSongs(): Flow<List<Song>>

    @Transaction
    @Query("UPDATE song SET dateDownload = :dateDownload, localPath = :localPath WHERE id = :mediaId AND isLocal = 0")
    fun registerDownloadSong(mediaId: String, dateDownload: LocalDateTime, localPath: String)

    @Transaction
    @Query("UPDATE song SET dateDownload = NULL, localPath = NULL WHERE id = :mediaId AND isLocal = 0")
    fun removeDownloadSong(mediaId: String)

    @Transaction
    @Query("UPDATE song SET dateDownload = NULL, localPath = NULL WHERE isLocal = 0")
    fun removeAllDownloadedSongs()
    // endregion

    // region Downloaded Songs Sort
    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY dateDownload")
    fun downloadNoLocalSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY inLibrary")
    fun downloadSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY date")
    fun downloadSongsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY dateModified")
    fun downloadSongsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun downloadSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 0 AND dateDownload IS NOT NULL
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun downloadSongsByArtistAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY totalPlayTime")
    fun downloadSongsByPlayTimeAsc(): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("""
        SELECT song.*, (SELECT SUM(playCount.count) 
            FROM playCount 
            WHERE playCount.song = song.id) AS pc 
        FROM song 
        WHERE isLocal = 0 AND dateDownload IS NOT NULL
        ORDER BY pc ASC
    """)
    fun downloadSongsByPlayCountAsc(): Flow<List<Song>>

    fun downloadSongs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> downloadSongsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> downloadSongsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> downloadSongsByReleaseDateAsc()
            SongSortType.NAME -> downloadSongsByNameAsc()
            SongSortType.ARTIST -> downloadSongsByArtistAsc()
            SongSortType.PLAY_TIME -> downloadSongsByPlayTimeAsc()
            SongSortType.PLAY_COUNT -> downloadSongsByPlayCountAsc()
        }.map { it.reversed(descending) }
    // endregion
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playCountEntity: PlayCountEntity): Long
    // endregion

    // region Updates
    @Update
    fun update(song: SongEntity)

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + :playTime WHERE id = :songId")
    fun incrementTotalPlayTime(songId: String, playTime: Long)

    @Query("UPDATE playCount SET count = count + 1 WHERE song = :songId AND year = :year AND month = :month")
    fun incrementPlayCount(songId: String, year: Int, month: Int)

    /**
     * Increment by one the play count with today's year and month.
     */
    fun incrementPlayCount(songId: String) {
        val time = LocalDateTime.now().atOffset(ZoneOffset.UTC)
        var oldCount: Int
        runBlocking {
            oldCount = getPlayCountByMonth(songId, time.year, time.monthValue).first()
        }

        // add new
        if (oldCount <= 0) {
            insert(PlayCountEntity(songId, time.year, time.monthValue, 0))
        }
        incrementPlayCount(songId, time.year, time.monthValue)
    }

    @Transaction
    fun toggleInLibrary(songId: String, inLibrary: LocalDateTime?) {
        inLibrary(songId, inLibrary)
        if (inLibrary == null) {
            removeLike(songId)
        }
    }

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE id = :songId AND inLibrary IS NULL")
    fun inLibrary(songId: String, inLibrary: LocalDateTime?)

    @Query("UPDATE song SET liked = 0, likedDate = null WHERE id = :songId")
    fun removeLike(songId: String)

    @Query("UPDATE song SET dateDownload = :dateDownload WHERE id = :songId")
    suspend fun updateDownloadStatus(songId: String, dateDownload: LocalDateTime?)

    @Query("UPDATE song SET inLibrary = null WHERE localPath = null")
    fun disableInvalidLocalSongs()

    @Query("UPDATE song SET inLibrary = null, localPath = null WHERE id = :songId")
    fun disableLocalSong(songId: String)

    fun updateLocalSongPath(songId: String, inLibrary: LocalDateTime?, localPath: String?) {
        if (localPath != null) {
            _updateLSP(songId, inLibrary, localPath)
        }
    }

    /**
     * DON'T USE THIS DIRECTLY, USE updateLocalSongPath(...) instead!
     */
    @Query("UPDATE song SET inLibrary = :inLibrary, localPath = :localPath WHERE id = :songId")
    fun _updateLSP(songId: String, inLibrary: LocalDateTime?, localPath: String)
    // endregion

    // region Deletes
    @Delete
    fun delete(song: SongEntity)

    @Transaction
    @Query("DELETE FROM song WHERE isLocal = 1")
    fun nukeLocalSongs()
    // endregion
}