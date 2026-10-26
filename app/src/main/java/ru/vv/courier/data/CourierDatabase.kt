package ru.vv.courier.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import ru.vv.courier.core.routing.DeliveryOrder

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val address: String,
    val windowStart: Long,
    val deadline: Long,
    val serviceSeconds: Long,
    val rewardRub: Double,
    val weightKg: Double,
    val originalPosition: Int,
    val pinnedPosition: Int?,
    val completed: Boolean,
    val latitude: Double?,
    val longitude: Double?,
) {
    fun toOrder() = DeliveryOrder(id, address, windowStart, deadline, serviceSeconds,
        rewardRub, weightKg, originalPosition, pinnedPosition, completed, latitude, longitude)
    companion object {
        fun from(order: DeliveryOrder) = with(order) { OrderEntity(id, address, windowStart,
            deadline, serviceSeconds, rewardRub, weightKg, originalPosition, pinnedPosition,
            completed, latitude, longitude) }
    }
}

@Entity(tableName = "metadata")
data class MetadataEntity(@PrimaryKey val key: String, val value: String)

@Dao
abstract class CourierDao {
    @Query("SELECT * FROM orders ORDER BY originalPosition")
    abstract suspend fun orders(): List<OrderEntity>
    @Query("SELECT * FROM metadata")
    abstract suspend fun metadata(): List<MetadataEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrders(orders: List<OrderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMetadata(metadata: List<MetadataEntity>)
    @Query("DELETE FROM orders")
    abstract suspend fun deleteOrders()
    @Query("DELETE FROM metadata")
    abstract suspend fun deleteMetadata()
    @Transaction
    open suspend fun replace(orders: List<OrderEntity>, metadata: List<MetadataEntity>) {
        deleteOrders()
        deleteMetadata()
        insertOrders(orders)
        insertMetadata(metadata)
    }
}

@Database(entities = [OrderEntity::class, MetadataEntity::class], version = 1, exportSchema = true)
abstract class CourierDatabase : RoomDatabase() {
    abstract fun courierDao(): CourierDao
    companion object {
        @Volatile private var instance: CourierDatabase? = null
        fun get(context: Context): CourierDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CourierDatabase::class.java,
                "vv-courier-private.db").build().also { instance = it }
        }
    }
}
