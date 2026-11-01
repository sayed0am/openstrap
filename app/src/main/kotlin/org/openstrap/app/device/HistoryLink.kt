package org.openstrap.app.device

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.Notification
import org.openstrap.app.data.settings.SettingsStore
import java.util.UUID

/** What [HistorySync] uses of the GATT link: [GattClient] on the phone, a simulated strap in tests. */
internal interface HistoryGatt {
    val connected: StateFlow<Boolean>
    val notifications: Flow<Notification>

    suspend fun enableNotifications(service: UUID, uuid: UUID)

    suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean)
}

/** Where [HistorySync] keeps each type's cursor, in epoch seconds, by key (`gdsp_<code>`). */
internal interface SyncCursors {
    suspend fun get(key: String): Long?

    suspend fun set(key: String, epochSeconds: Long)
}

internal fun GattClient.asHistoryGatt(): HistoryGatt = object : HistoryGatt {
    override val connected get() = this@asHistoryGatt.connected
    override val notifications get() = this@asHistoryGatt.notifications

    override suspend fun enableNotifications(service: UUID, uuid: UUID) = this@asHistoryGatt.enableNotifications(service, uuid)

    override suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean) =
        this@asHistoryGatt.write(service, uuid, value, withResponse)
}

internal fun SettingsStore.asSyncCursors(): SyncCursors = object : SyncCursors {
    override suspend fun get(key: String) = lastSync(key)

    override suspend fun set(key: String, epochSeconds: Long) = setLastSync(key, epochSeconds)
}
