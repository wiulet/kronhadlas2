package com.btcsignal.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(signal: SignalEntity): Long

    @Update
    suspend fun update(signal: SignalEntity)

    @Query("SELECT * FROM signals WHERE signalId = :signalId LIMIT 1")
    suspend fun getById(signalId: String): SignalEntity?

    /** Duplicate-signal protection (spec section 9): is there already a locked signal for this candle? */
    @Query("SELECT COUNT(*) FROM signals WHERE candleId = :candleId AND isBacktest = :isBacktest")
    suspend fun countForCandle(candleId: String, isBacktest: Boolean): Int

    @Query("SELECT * FROM signals WHERE isBacktest = 0 ORDER BY signalTimestampMillis DESC")
    fun observeLiveHistory(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE isBacktest = 0 AND signalTimestampMillis >= :sinceMillis ORDER BY signalTimestampMillis DESC")
    suspend fun getLiveSince(sinceMillis: Long): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE isBacktest = 0 AND activeStrategyId = :strategyId AND signalTimestampMillis >= :sinceMillis ORDER BY signalTimestampMillis DESC")
    suspend fun getLiveForStrategySince(strategyId: String, sinceMillis: Long): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE isBacktest = 1 AND candleOpenTimeMillis BETWEEN :fromMillis AND :toMillis ORDER BY candleOpenTimeMillis ASC")
    suspend fun getBacktestRange(fromMillis: Long, toMillis: Long): List<SignalEntity>

    @Query("DELETE FROM signals WHERE isBacktest = 1")
    suspend fun clearBacktestResults()

    /** Clears the Live signal history shown on the History screen (spec: user-triggered
     *  "Clear" action). Leaves backtest rows (isBacktest = 1) untouched. */
    @Query("DELETE FROM signals WHERE isBacktest = 0")
    suspend fun clearLiveHistory()

    @Query("SELECT * FROM signals WHERE isBacktest = 0 AND status = 'ACTIVE' ORDER BY signalTimestampMillis DESC LIMIT 1")
    suspend fun getMostRecentActiveLiveSignal(): SignalEntity?

    @Query("SELECT * FROM signals WHERE candleId = :candleId AND isBacktest = :isBacktest LIMIT 1")
    suspend fun getForCandle(candleId: String, isBacktest: Boolean): SignalEntity?
}
