package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.xiaomanjun.sleepdownschedule.data.guard.GuardDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class GuardDatabaseMigrationTest {
    @Test fun versionOneEventsSurviveStructuredLogMigration() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "guard-upgrade-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE guard_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, time INTEGER NOT NULL, message TEXT NOT NULL)")
            db.execSQL("CREATE TABLE guard_drafts (id TEXT NOT NULL PRIMARY KEY, time INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO guard_events (time, message) VALUES (123, 'old log')")
            db.version = 1
        }
        val db = Room.databaseBuilder(context, GuardDatabase::class.java, name)
            .addMigrations(GuardDatabase.MIGRATION_1_2).build()
        try {
            val event = db.dao().observeEvents().first().single()
            assertEquals("old log", event.message)
            assertEquals("", event.title)
            assertEquals("", event.reason)
            assertEquals("", event.error)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
