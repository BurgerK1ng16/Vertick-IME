package com.weike.ime.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lexicon", primaryKeys = ["term"])
data class LexiconTerm(val term: String, val hint: String = "")

@Entity(tableName = "english_learning", primaryKeys = ["term"])
data class EnglishLearning(
    val term: String,
    val useCount: Int,
    val lastUsedAt: Long
)

/** Local ranking signal for explicitly confirmed Chinese candidates only. */
@Entity(tableName = "pinyin_learning", primaryKeys = ["term"])
data class PinyinLearning(
    val term: String,
    val useCount: Int,
    val lastUsedAt: Long
)

/** A user-owned entry that must rank ahead of general keyboard candidates. */
@Entity(tableName = "typing_dictionary", primaryKeys = ["term"])
data class TypingDictionaryEntry(
    val term: String,
    val hint: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "usage_stats", primaryKeys = ["id"])
data class UsageStats(
    val id: Int = 0,
    val dictationDurationMs: Long = 0L,
    val dictationUnits: Long = 0L
)

@Entity(tableName = "input_history")
data class InputHistory(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: String,
    val content: String,
    val question: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "clipboard_history")
data class ClipboardEntry(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface LexiconDao {
    @Query("SELECT * FROM lexicon ORDER BY term COLLATE NOCASE")
    fun observeAll(): Flow<List<LexiconTerm>>

    @Query("SELECT * FROM lexicon ORDER BY term COLLATE NOCASE")
    suspend fun all(): List<LexiconTerm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: LexiconTerm)

    @Query("DELETE FROM lexicon WHERE term = :term")
    suspend fun delete(term: String)
}

@Dao
interface EnglishLearningDao {
    @Query("SELECT * FROM english_learning")
    suspend fun all(): List<EnglishLearning>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EnglishLearning)
}

@Dao
interface PinyinLearningDao {
    @Query("SELECT * FROM pinyin_learning ORDER BY useCount DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun all(limit: Int = PINYIN_LEARNING_LIMIT): List<PinyinLearning>

    @Query("SELECT * FROM pinyin_learning WHERE term = :term LIMIT 1")
    suspend fun find(term: String): PinyinLearning?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PinyinLearning)

    @Query("DELETE FROM pinyin_learning")
    suspend fun deleteAll()

    @Query("DELETE FROM pinyin_learning WHERE term NOT IN (SELECT term FROM pinyin_learning ORDER BY useCount DESC, lastUsedAt DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int = PINYIN_LEARNING_LIMIT)

    @androidx.room.Transaction
    suspend fun record(term: String, now: Long = System.currentTimeMillis()) {
        val value = term.trim()
        if (value.isBlank()) return
        val existing = find(value)
        upsert(PinyinLearning(value, (existing?.useCount ?: 0) + 1, now))
        trimTo()
    }
}

@Dao
interface TypingDictionaryDao {
    @Query("SELECT * FROM typing_dictionary ORDER BY createdAt DESC, term COLLATE NOCASE")
    fun observeAll(): Flow<List<TypingDictionaryEntry>>

    @Query("SELECT * FROM typing_dictionary ORDER BY createdAt DESC, term COLLATE NOCASE")
    suspend fun all(): List<TypingDictionaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TypingDictionaryEntry)

    @Query("DELETE FROM typing_dictionary WHERE term = :term")
    suspend fun delete(term: String)
}

@Dao
interface UsageStatsDao {
    @Query("SELECT * FROM usage_stats WHERE id = 0")
    fun observe(): Flow<UsageStats?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(stats: UsageStats = UsageStats())

    @Query("UPDATE usage_stats SET dictationDurationMs = dictationDurationMs + :durationMs, dictationUnits = dictationUnits + :units WHERE id = 0")
    suspend fun increment(durationMs: Long, units: Long)

    @androidx.room.Transaction
    suspend fun record(durationMs: Long, units: Long) {
        ensure()
        increment(durationMs.coerceAtLeast(0L), units.coerceAtLeast(0L))
    }
}

@Dao
interface InputHistoryDao {
    @Query("SELECT * FROM input_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<InputHistory>>

    @Insert
    suspend fun insert(entry: InputHistory)

    @Query("DELETE FROM input_history")
    suspend fun deleteAll()

    @Query("DELETE FROM input_history WHERE createdAt < :cutoff")
    suspend fun deleteBefore(cutoff: Long)
}

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_history ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = CLIPBOARD_LIMIT): Flow<List<ClipboardEntry>>

    @Insert
    suspend fun insert(entry: ClipboardEntry)

    @Query("DELETE FROM clipboard_history WHERE content = :content")
    suspend fun deleteByContent(content: String)

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun deleteAll()

    @Query("DELETE FROM clipboard_history WHERE createdAt < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM clipboard_history WHERE id NOT IN (SELECT id FROM clipboard_history ORDER BY createdAt DESC, id DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)

    @androidx.room.Transaction
    suspend fun record(content: String, now: Long = System.currentTimeMillis()) {
        deleteBefore(now - CLIPBOARD_RETENTION_MS)
        deleteByContent(content)
        insert(ClipboardEntry(content = content, createdAt = now))
        trimTo(CLIPBOARD_LIMIT)
    }
}

@Database(
    entities = [
        LexiconTerm::class,
        EnglishLearning::class,
        PinyinLearning::class,
        TypingDictionaryEntry::class,
        UsageStats::class,
        InputHistory::class,
        ClipboardEntry::class
    ],
    version = 8,
    exportSchema = false
)
abstract class LexiconDatabase : RoomDatabase() {
    abstract fun lexiconDao(): LexiconDao
    abstract fun englishLearningDao(): EnglishLearningDao
    abstract fun pinyinLearningDao(): PinyinLearningDao
    abstract fun typingDictionaryDao(): TypingDictionaryDao
    abstract fun usageStatsDao(): UsageStatsDao
    abstract fun inputHistoryDao(): InputHistoryDao
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile private var instance: LexiconDatabase? = null

        fun get(context: Context): LexiconDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, LexiconDatabase::class.java, "weike_lexicon.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { instance = it }
        }
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS user_phrase (phrase TEXT NOT NULL, useCount INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, PRIMARY KEY(phrase))"
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS user_phrase")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS english_learning (term TEXT NOT NULL, useCount INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, PRIMARY KEY(term))"
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS typing_dictionary (term TEXT NOT NULL, hint TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(term))")
        database.execSQL("CREATE TABLE IF NOT EXISTS usage_stats (id INTEGER NOT NULL, dictationDurationMs INTEGER NOT NULL, dictationUnits INTEGER NOT NULL, PRIMARY KEY(id))")
        database.execSQL("CREATE TABLE IF NOT EXISTS input_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, question TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS clipboard_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}

/** Clipboard storage is now opt-in, bounded, and short-lived. Drop legacy unlimited records. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DELETE FROM clipboard_history")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS pinyin_learning (term TEXT NOT NULL, useCount INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, PRIMARY KEY(term))"
        )
    }
}

private const val CLIPBOARD_LIMIT = 20
private const val CLIPBOARD_RETENTION_MS = 24L * 60L * 60L * 1000L
private const val PINYIN_LEARNING_LIMIT = 5_000
