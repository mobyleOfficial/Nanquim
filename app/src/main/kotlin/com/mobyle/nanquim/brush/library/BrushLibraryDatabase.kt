package com.mobyle.nanquim.brush.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "brush_groups")
data class BrushGroupEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val builtInKey: String?,
    val position: Int,
)

@Entity(tableName = "brush_entries", indices = [Index("groupId")])
data class BrushEntryEntity(
    @PrimaryKey val brushId: String,
    val groupId: String,
    val position: Int,
    val name: String,
    val builtInPath: String?,
    val hasOverride: Boolean = false,
    val pinned: Boolean = false,
    val lastUsedAt: Long? = null,
)

@Entity(tableName = "brush_tool_state", primaryKeys = ["brushId", "tool"])
data class BrushToolStateEntity(
    val brushId: String,
    val tool: String,
    val sizeSlider: Float,
    val opacitySlider: Float,
)

@Entity(tableName = "slider_presets", primaryKeys = ["brushId", "tool", "slider", "position"])
data class SliderPresetEntity(
    val brushId: String,
    val tool: String,
    val slider: String,
    val position: Int,
    val value: Float,
)

@Entity(tableName = "tool_brushes")
data class ToolBrushEntity(
    @PrimaryKey val tool: String,
    val brushId: String,
)

@Entity(tableName = "seeded_built_in_brushes")
data class SeededBuiltInBrushEntity(
    @PrimaryKey val brushId: String,
)

@Dao
interface BrushLibraryDao {
    @Query("SELECT * FROM brush_groups ORDER BY position")
    fun observeGroups(): Flow<List<BrushGroupEntity>>

    @Query("SELECT * FROM brush_entries ORDER BY position")
    fun observeEntries(): Flow<List<BrushEntryEntity>>

    @Query("SELECT * FROM brush_groups ORDER BY position")
    suspend fun groups(): List<BrushGroupEntity>

    @Query("SELECT * FROM brush_groups WHERE id = :groupId")
    suspend fun group(groupId: String): BrushGroupEntity?

    @Query("SELECT * FROM brush_groups WHERE builtInKey = :key")
    suspend fun groupByKey(key: String): BrushGroupEntity?

    @Query("SELECT * FROM brush_entries WHERE groupId = :groupId ORDER BY position")
    suspend fun entriesOf(groupId: String): List<BrushEntryEntity>

    @Query("SELECT * FROM brush_entries WHERE brushId = :brushId")
    suspend fun entry(brushId: String): BrushEntryEntity?

    @Query("SELECT COUNT(*) FROM brush_entries")
    suspend fun entryCount(): Int

    @Upsert
    suspend fun upsertGroup(group: BrushGroupEntity)

    @Upsert
    suspend fun upsertGroups(groups: List<BrushGroupEntity>)

    @Upsert
    suspend fun upsertEntry(entry: BrushEntryEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<BrushEntryEntity>)

    @Query("DELETE FROM brush_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM brush_entries WHERE brushId = :brushId")
    suspend fun deleteEntry(brushId: String)

    @Query("DELETE FROM brush_tool_state WHERE brushId = :brushId")
    suspend fun deleteToolStates(brushId: String)

    @Query("DELETE FROM slider_presets WHERE brushId = :brushId")
    suspend fun deleteSliderPresets(brushId: String)

    @Query("SELECT * FROM brush_tool_state WHERE brushId = :brushId AND tool = :tool")
    suspend fun toolState(
        brushId: String,
        tool: String,
    ): BrushToolStateEntity?

    @Upsert
    suspend fun upsertToolState(state: BrushToolStateEntity)

    @Query("SELECT * FROM slider_presets WHERE brushId = :brushId AND tool = :tool AND slider = :slider ORDER BY position")
    suspend fun sliderPresets(
        brushId: String,
        tool: String,
        slider: String,
    ): List<SliderPresetEntity>

    @Query("DELETE FROM slider_presets WHERE brushId = :brushId AND tool = :tool AND slider = :slider")
    suspend fun clearSliderPresets(
        brushId: String,
        tool: String,
        slider: String,
    )

    @Insert
    suspend fun insertSliderPresets(presets: List<SliderPresetEntity>)

    @Query("SELECT * FROM tool_brushes")
    fun observeToolBrushes(): Flow<List<ToolBrushEntity>>

    @Query("SELECT brushId FROM tool_brushes WHERE tool = :tool")
    suspend fun toolBrush(tool: String): String?

    @Upsert
    suspend fun upsertToolBrush(toolBrush: ToolBrushEntity)

    @Query("DELETE FROM tool_brushes WHERE brushId = :brushId")
    suspend fun clearToolBrush(brushId: String)

    @Query("SELECT brushId FROM seeded_built_in_brushes")
    suspend fun seededBuiltInBrushes(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeeded(brushes: List<SeededBuiltInBrushEntity>)

    @Transaction
    suspend fun replaceSliderPresets(
        brushId: String,
        tool: String,
        slider: String,
        presets: List<SliderPresetEntity>,
    ) {
        clearSliderPresets(brushId, tool, slider)
        insertSliderPresets(presets)
    }
}

@Database(
    entities = [
        BrushGroupEntity::class,
        BrushEntryEntity::class,
        BrushToolStateEntity::class,
        SliderPresetEntity::class,
        ToolBrushEntity::class,
        SeededBuiltInBrushEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BrushLibraryDatabase : RoomDatabase() {
    abstract fun dao(): BrushLibraryDao

    companion object {
        const val FILE_NAME = "brush-library.db"

        fun open(context: Context): BrushLibraryDatabase =
            Room.databaseBuilder(context, BrushLibraryDatabase::class.java, FILE_NAME).build()

        fun inMemory(context: Context): BrushLibraryDatabase =
            Room.inMemoryDatabaseBuilder(context, BrushLibraryDatabase::class.java).build()
    }
}
