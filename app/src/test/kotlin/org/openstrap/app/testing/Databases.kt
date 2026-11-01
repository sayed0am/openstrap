package org.openstrap.app.testing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.rules.ExternalResource
import org.openstrap.app.data.db.OpenStrapDatabase
import java.io.File

/** An empty database at the current version, in memory. Robolectric only. Close it after the test. */
fun inMemoryDb(context: Context = ApplicationProvider.getApplicationContext()): OpenStrapDatabase =
    Room.inMemoryDatabaseBuilder(context, OpenStrapDatabase::class.java).allowMainThreadQueries().build()

/**
 * Builds a database file at an old version from that version's exported schema, so it is the database that version
 * really made, then opens it through Room so every migration since runs. A JUnit rule: it closes what it opened and
 * deletes its files after each test.
 *
 * A stand-in until `room-testing` is on the test classpath, when this can wrap `MigrationTestHelper` instead.
 */
class MigrationHarness : ExternalResource() {
    private val files = mutableListOf<File>()
    private val opened = mutableListOf<OpenStrapDatabase>()

    /** A database file at [version] with every table and index of that version, then [seed] run on it. */
    fun create(version: Int, seed: SQLiteDatabase.() -> Unit = {}): File {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        files += file
        val schema = JSONObject(File(SourceTree.schemas, "$version.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.seed()
            db.version = version
        }
        return file
    }

    /** [file] opened through Room at the current version, migrating it on the way. */
    fun open(file: File): OpenStrapDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build().also { opened += it }

    override fun after() {
        opened.forEach { it.close() }
        files.forEach { f -> listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() } }
    }
}
