package com.mobyle.nanquim.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "artworks", indices = [Index("stackId")])
data class ArtworkEntity(
    @PrimaryKey val id: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val stackId: String? = null,
)

@Entity(tableName = "stacks")
data class StackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "canvas_presets")
data class CanvasPresetEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val builtInKey: String?,
    val width: Double,
    val height: Double,
    val unit: String,
    val dpi: Double,
    val position: Int,
)

@Dao
interface GalleryDao {
    @Query("SELECT * FROM artworks")
    fun observeArtworks(): Flow<List<ArtworkEntity>>

    @Query("SELECT * FROM stacks")
    fun observeStacks(): Flow<List<StackEntity>>

    @Query("SELECT * FROM artworks")
    suspend fun artworks(): List<ArtworkEntity>

    @Query("SELECT * FROM stacks")
    suspend fun stacks(): List<StackEntity>

    @Query("SELECT * FROM artworks WHERE id = :id")
    suspend fun artwork(id: String): ArtworkEntity?

    @Query("SELECT * FROM stacks WHERE id = :id")
    suspend fun stack(id: String): StackEntity?

    @Upsert
    suspend fun upsertArtwork(artwork: ArtworkEntity)

    @Upsert
    suspend fun upsertStack(stack: StackEntity)

    @Query("DELETE FROM artworks WHERE id IN (:ids)")
    suspend fun deleteArtworks(ids: List<String>)

    @Query("UPDATE artworks SET stackId = :stackId WHERE id IN (:ids)")
    suspend fun setStack(
        ids: List<String>,
        stackId: String?,
    )

    @Query("UPDATE stacks SET name = :name WHERE id = :id")
    suspend fun renameStack(
        id: String,
        name: String,
    )

    @Query("DELETE FROM stacks WHERE id NOT IN (SELECT stackId FROM artworks WHERE stackId IS NOT NULL GROUP BY stackId HAVING COUNT(*) >= 2)")
    suspend fun deleteSmallStacks()

    @Query("UPDATE artworks SET stackId = NULL WHERE stackId IS NOT NULL AND stackId NOT IN (SELECT id FROM stacks)")
    suspend fun clearMissingStacks()

    @Query("SELECT * FROM canvas_presets ORDER BY position")
    fun observePresets(): Flow<List<CanvasPresetEntity>>

    @Query("SELECT COUNT(*) FROM canvas_presets")
    suspend fun presetCount(): Int

    @Upsert
    suspend fun upsertPreset(preset: CanvasPresetEntity)

    @Query("DELETE FROM canvas_presets WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM canvas_presets")
    suspend fun lastPresetPosition(): Int

    @Transaction
    suspend fun tidyStacks() {
        deleteSmallStacks()
        clearMissingStacks()
    }
}

@Database(entities = [ArtworkEntity::class, StackEntity::class, CanvasPresetEntity::class], version = 1, exportSchema = true)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun dao(): GalleryDao

    companion object {
        fun open(context: Context): GalleryDatabase = Room.databaseBuilder(context, GalleryDatabase::class.java, "gallery.db").build()
    }
}
