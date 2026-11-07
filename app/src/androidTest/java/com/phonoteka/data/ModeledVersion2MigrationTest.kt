package com.phonoteka.data
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Current track/candidate definitions used to construct a CONTROLLED v2 fixture.
 * This is not the missing real historic Room schema or a claim about all old installations.
 */
class ModeledVersion2MigrationTest {
    @Test fun knownTwoToThreeMigrationPreservesOwnedRowsAndValidatesCurrentRoomSchema() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "modeled-v2-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, artist TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, filePath TEXT, source TEXT, lastError TEXT, sha256 TEXT, searchQuery TEXT NOT NULL, durationSec INTEGER, candidateArtist TEXT, candidateTitle TEXT, selectedCandidateId TEXT, manual INTEGER NOT NULL, audioValidationJson TEXT, orderIndex INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE candidates (id TEXT NOT NULL PRIMARY KEY, trackId INTEGER NOT NULL, artist TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, durationSec INTEGER NOT NULL, score INTEGER NOT NULL, isExplicit INTEGER NOT NULL, url TEXT NOT NULL, channelName TEXT NOT NULL, blocked INTEGER NOT NULL, version TEXT NOT NULL, thumbnailUrl TEXT, audioStreamUrl TEXT, FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_candidates_trackId ON candidates(trackId)")
                db.execSQL("INSERT INTO tracks (id,artist,title,status,searchQuery,manual,orderIndex) VALUES (42,'fixture','preserved','pending','',0,0)")
                db.execSQL("INSERT INTO candidates (id,trackId,artist,title,source,durationSec,score,isExplicit,url,channelName,blocked,version) VALUES ('owned-candidate',42,'fixture','candidate','local',1,100,0,'https://localhost/owned','',0,'unknown')")
                db.version = 2
            }
            val room = Room.databaseBuilder(context, PhonotekaDatabase::class.java, name).addMigrations(PhonotekaDatabase.MIGRATION_2_3).build()
            try {
                assertEquals("preserved", room.trackDao().getTrackById(42).first()?.title)
                assertEquals("owned-candidate", room.trackDao().getCandidates(42).first().single().id)
                assertTrue(room.catalogPostDao().observePosts("").first().isEmpty())
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
