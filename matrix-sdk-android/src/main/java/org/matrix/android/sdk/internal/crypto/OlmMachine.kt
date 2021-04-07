/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.rest.UnsignedDeviceInfo
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.sync.model.DeviceListResponse
import org.matrix.android.sdk.internal.session.sync.model.DeviceOneTimeKeysCountSyncResponse
import org.matrix.android.sdk.internal.session.sync.model.ToDeviceSyncResponse
import timber.log.Timber
import uniffi.olm.CryptoStoreErrorException
import uniffi.olm.Device as InnerDevice
import uniffi.olm.DeviceLists
import uniffi.olm.Logger
import uniffi.olm.OlmMachine as InnerMachine
import uniffi.olm.ProgressListener as RustProgressListener
import uniffi.olm.Request
import uniffi.olm.RequestType
import uniffi.olm.Sas as InnerSas
import uniffi.olm.setLogger

class CryptoLogger() : Logger {
    override fun log(logLine: String) {
        Timber.d(logLine)
    }
}

private class CryptoProgressListener(listener: ProgressListener?) : RustProgressListener {
    private val inner: ProgressListener? = listener

    override fun onProgress(progress: Int, total: Int) {
        if (this.inner != null) {
            this.inner.onProgress(progress, total)
        }
    }
}

internal class LiveDevice(
    userIds: List<String>,
    observer: DeviceUpdateObserver
) : MutableLiveData<List<CryptoDeviceInfo>>() {
    var userIds: List<String> = userIds
    private var observer: DeviceUpdateObserver = observer

    private val listener = { devices: List<CryptoDeviceInfo> ->
        value = devices
    }

    override fun onActive() {
        observer.addDeviceUpdateListener(this)
    }

    override fun onInactive() {
        observer.removeDeviceUpdateListener(this)
    }
}

fun setRustLogger() {
    setLogger(CryptoLogger() as Logger)
}

class Device(inner: InnerDevice, machine: InnerMachine) {
    private val machine: InnerMachine = machine
    private val inner: InnerDevice = inner

    fun userId(): String {
        return this.inner.userId
    }

    fun deviceId(): String {
        return this.inner.deviceId
    }

    fun keys(): Map<String, String> {
        return this.inner.keys
    }

    fun startVerification(): InnerSas {
        return this.machine.startVerification(this.inner)
    }

    fun toCryptoDeviceInfo(): CryptoDeviceInfo {
        return CryptoDeviceInfo(
            this.deviceId(),
            this.userId(),
            // TODO pass the algorithms here.
            listOf(),
            this.keys(),
            // TODO pass the signatures here.
            mapOf(),
            // TODO pass the display name here.
            UnsignedDeviceInfo(),
            // TODO pass trust levels here
            DeviceTrustLevel(false, false),
            // TODO is the device blacklisted
            false,
            // TODO
            null
        )
    }
}

internal class DeviceUpdateObserver() {
    internal val listeners = HashMap<LiveDevice, List<String>>()

    fun addDeviceUpdateListener(device: LiveDevice) {
        listeners.set(device, device.userIds)
    }

    fun removeDeviceUpdateListener(device: LiveDevice) {
        listeners.remove(device)
    }
}

internal class OlmMachine(user_id: String, device_id: String, path: File) {
    private val inner: InnerMachine = InnerMachine(user_id, device_id, path.toString())
    private val deviceUpdateObserver = DeviceUpdateObserver()

    fun userId(): String {
        return this.inner.userId()
    }

    fun deviceId(): String {
        return this.inner.deviceId()
    }

    fun identityKeys(): Map<String, String> {
        return this.inner.identityKeys()
    }

    fun ownDevice(): CryptoDeviceInfo {
        return CryptoDeviceInfo(
            this.deviceId(),
            this.userId(),
            // TODO pass the algorithms here.
            listOf(),
            this.identityKeys(),
            mapOf(),
            UnsignedDeviceInfo(),
            DeviceTrustLevel(false, true),
            false,
            null
        )
    }

    suspend fun updateLiveDevices() {
        for ((liveDevice, users) in deviceUpdateObserver.listeners) {
            val devices = getUserDevices(users)
            liveDevice.postValue(devices)
        }
    }

    suspend fun outgoingRequests(): List<Request> = withContext(Dispatchers.IO) {
        inner.outgoingRequests()
    }

    suspend fun encrypt(roomId: String, eventType: String, content: Content): Content = withContext(Dispatchers.IO) {
        val adapter = MoshiProvider.providesMoshi().adapter<Content>(Map::class.java)
        val contentString = adapter.toJson(content)
        val encrypted = inner.encrypt(roomId, eventType, contentString)
        adapter.fromJson(encrypted)!!
    }

    suspend fun shareGroupSession(roomId: String, users: List<String>): List<Request> = withContext(Dispatchers.IO) {
        inner.shareGroupSession(roomId, users)
    }

    suspend fun getMissingSessions(users: List<String>): Request? = withContext(Dispatchers.IO) {
        inner.getMissingSessions(users)
    }

    suspend fun updateTrackedUsers(users: List<String>) = withContext(Dispatchers.IO) {
        inner.updateTrackedUsers(users)
    }

    suspend fun receiveSyncChanges(
        toDevice: ToDeviceSyncResponse?,
        deviceChanges: DeviceListResponse?,
        keyCounts: DeviceOneTimeKeysCountSyncResponse?
    ) = withContext(Dispatchers.IO) {
            var counts: MutableMap<String, Int> = mutableMapOf()

            if (keyCounts?.signedCurve25519 != null) {
                counts.put("signed_curve25519", keyCounts.signedCurve25519)
            }

            val devices = DeviceLists(deviceChanges?.changed ?: listOf(), deviceChanges?.left ?: listOf())
            val adapter = MoshiProvider.providesMoshi().adapter<ToDeviceSyncResponse>(ToDeviceSyncResponse::class.java)
            val events = adapter.toJson(toDevice ?: ToDeviceSyncResponse())!!

            inner.receiveSyncChanges(events, devices, counts)
    }

    suspend fun markRequestAsSent(
        request_id: String,
        request_type: RequestType,
        response_body: String
    ) = withContext(Dispatchers.IO) {
        inner.markRequestAsSent(request_id, request_type, response_body)

        if (request_type == RequestType.KEYS_QUERY) {
            updateLiveDevices()
        }
    }

    suspend fun getDevice(user_id: String, device_id: String): Device? = withContext(Dispatchers.IO) {
        when (val device: InnerDevice? = inner.getDevice(user_id, device_id)) {
            null -> null
            else -> Device(device, inner)
        }
    }

    suspend fun getUserDevices(userId: String): List<CryptoDeviceInfo> {
        return inner.getUserDevices(userId).map { Device(it, inner).toCryptoDeviceInfo() }
    }

    suspend fun getUserDevices(userIds: List<String>): List<CryptoDeviceInfo> {
        val plainDevices: ArrayList<CryptoDeviceInfo> = arrayListOf()

        for (user in userIds) {
            val devices = getUserDevices(user)
            plainDevices.addAll(devices)
        }

        return plainDevices
    }

    suspend fun getLiveDevices(userIds: List<String>): LiveData<List<CryptoDeviceInfo>> {
        val plainDevices = getUserDevices(userIds)
        val devices = LiveDevice(userIds, deviceUpdateObserver)
        devices.setValue(plainDevices)

        return devices
    }

    @Throws(CryptoStoreErrorException::class)
    suspend fun exportKeys(passphrase: String, rounds: Int): ByteArray = withContext(Dispatchers.IO) {
        inner.exportKeys(passphrase, rounds).toByteArray()
    }

    @Throws(CryptoStoreErrorException::class)
    suspend fun importKeys(keys: ByteArray, passphrase: String, listener: ProgressListener?): ImportRoomKeysResult = withContext(Dispatchers.IO) {
        var decodedKeys = keys.toString()

        var rustListener = CryptoProgressListener(listener)

        var result = inner.importKeys(decodedKeys, passphrase, rustListener)

        ImportRoomKeysResult(result.total, result.imported)
    }

    @Throws(MXCryptoError::class)
    suspend fun decryptRoomEvent(event: Event): MXEventDecryptionResult = withContext(Dispatchers.IO) {
        val adapter = MoshiProvider.providesMoshi().adapter<Event>(Event::class.java)
        val serializedEvent = adapter.toJson(event)

        try {
            val decrypted = inner.decryptRoomEvent(serializedEvent, event.roomId!!)

            val deserializationAdapter = MoshiProvider.providesMoshi().adapter<JsonDict>(Map::class.java)
            val clearEvent = deserializationAdapter.fromJson(decrypted.clearEvent)!!

            MXEventDecryptionResult(
                clearEvent,
                decrypted.senderCurve25519Key,
                decrypted.claimedEd25519Key,
                decrypted.forwardingCurve25519Chain
            )
        } catch (throwable: Throwable) {
            val reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, throwable.message, "m.megolm.v1.aes-sha2")
            throw MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_DECRYPT, reason)
        }
    }
}