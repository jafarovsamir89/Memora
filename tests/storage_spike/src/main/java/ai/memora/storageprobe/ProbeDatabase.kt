package ai.memora.storageprobe

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// Build-only synthetic probe. This module is not part of the application and has no user-data access.
@Entity data class ProbeRow(@PrimaryKey val id: Long, val syntheticText: String)
@Dao interface ProbeDao {
    @Insert fun insert(row: ProbeRow)
    @Query("SELECT * FROM ProbeRow WHERE id = :id") fun get(id: Long): ProbeRow?
}
@Database(entities = [ProbeRow::class], version = 1, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun rows(): ProbeDao
    companion object {
        fun build(context: Context, filename: String, syntheticKey: ByteArray): ProbeDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, ProbeDatabase::class.java, filename)
                .openHelperFactory(SupportOpenHelperFactory(syntheticKey)).build()
        }
    }
}
