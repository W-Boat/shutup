package com.xiaomanjun.sleepdownschedule.data.guard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.guardPreferences by preferencesDataStore("snow_guard_v1")

/** A failed/corrupt read propagates to the health screen, never silently erases a user's rules. */
class GuardStore(context: Context) {
    private val app = context.applicationContext
    private val key = stringPreferencesKey("document")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val document = app.guardPreferences.data.map { prefs ->
        prefs[key]?.let { json.decodeFromString<GuardDocument>(it).also(GuardDocument::validate) } ?: GuardDocument()
    }
    suspend fun update(transform: (GuardDocument) -> GuardDocument) {
        app.guardPreferences.edit { prefs ->
            val previous = prefs[key]?.let { json.decodeFromString<GuardDocument>(it) } ?: GuardDocument()
            val next = transform(previous)
            next.validate()
            prefs[key] = json.encodeToString(next)
        }
    }
    fun runtime(): GuardRuntime = app.getSharedPreferences("snow_guard_runtime", Context.MODE_PRIVATE)
        .getString("snapshot", null)?.let { json.decodeFromString<GuardRuntime>(it) } ?: GuardRuntime()
    fun saveRuntime(runtime: GuardRuntime) {
        val preferences = app.getSharedPreferences("snow_guard_runtime", Context.MODE_PRIVATE)
        val encoded = json.encodeToString(runtime)
        if (preferences.getString("snapshot", null) == encoded) return
        check(preferences.edit().putString("snapshot", encoded).commit()) { "无法保存声音恢复快照，已停止本次操作" }
    }
}

@Entity(tableName = "guard_events")
data class GuardEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val time: Long, val message: String)
@Entity(tableName = "guard_drafts")
data class GuardDraft(@PrimaryKey val id: String, val time: Long, val payload: String)

@Dao interface GuardDao {
    @Query("SELECT * FROM guard_events ORDER BY id DESC LIMIT 100")
    fun observeEvents(): kotlinx.coroutines.flow.Flow<List<GuardEvent>>
    @Insert suspend fun insert(event: GuardEvent)
    @Query("DELETE FROM guard_events WHERE id NOT IN (SELECT id FROM guard_events ORDER BY id DESC LIMIT 300)")
    suspend fun trimEvents()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(draft: GuardDraft)
    @Query("SELECT * FROM guard_drafts WHERE id = :id") suspend fun draft(id: String): GuardDraft?
    @Query("DELETE FROM guard_drafts WHERE id = :id") suspend fun deleteDraft(id: String)
}

@Database(entities = [GuardEvent::class, GuardDraft::class], version = 1, exportSchema = false)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun dao(): GuardDao
    companion object {
        @Volatile private var instance: GuardDatabase? = null
        fun get(context: Context): GuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GuardDatabase::class.java, "snow-guard.db")
                .build().also { instance = it }
        }
    }
}
