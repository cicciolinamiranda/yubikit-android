/*
 * Copyright (C) 2019 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.yubikit.demo

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.yubico.yubikit.YubiKitManager
import com.yubico.yubikit.demo.fido.arch.ErrorLiveEvent
import com.yubico.yubikit.demo.fido.arch.SingleLiveEvent
import com.yubico.yubikit.demo.raw.UsbDeviceNotFoundException
import com.yubico.yubikit.exceptions.NfcDisabledException
import com.yubico.yubikit.exceptions.NfcNotFoundException
import com.yubico.yubikit.exceptions.NoPermissionsException
import com.yubico.yubikit.transport.YubiKeySession
import com.yubico.yubikit.transport.nfc.NfcSession
import com.yubico.yubikit.transport.nfc.NfcSessionListener
import com.yubico.yubikit.transport.usb.UsbSession
import com.yubico.yubikit.transport.usb.UsbSessionListener
import com.yubico.yubikit.utils.ILogger
import com.yubico.yubikit.utils.Logger

private const val TAG = "YubikeyViewModel"
open class YubikeyViewModel(private val yubikitManager: YubiKitManager) : ViewModel() {

    /**
     * Keeps received UsbSessions (how many yubikeys with ccid interface were connected) and flag whether it has permissions to connect/send/receive APDU commands
     */
    private val sessions = HashMap<UsbSession, Boolean>()

    /**
     * Keeps active iso7816 session/connection that will be used to send/receive bytes on button click
     */
    private val _sessionUsb = SingleLiveEvent<UsbSession>()
    val sessionUsb : LiveData<UsbSession> = _sessionUsb

    private val _sessionNfc = SingleLiveEvent<NfcSession>()
    val sessionNfc : LiveData<NfcSession> = _sessionNfc

    private val _error = ErrorLiveEvent(TAG)
    protected fun postError(e: Throwable) {
        _error.postValue(e)
    }

    val error : LiveData<Throwable> = _error

    /**
     * Listeners for yubikey discovery (over USB and NFC)
     */
    private val usbListener = object: UsbSessionListener {
        override fun onSessionReceived(session: UsbSession) {
            val hadPermissions = sessions[session] != false
            // latest connection becomes active one
            sessions[session] = true
            _sessionUsb.value = session

            // if user has granted permissions we should execute command as button was clicked already
            if (!hadPermissions) {
                session.executeDemoCommands()
            }
        }

        override fun onSessionRemoved(session: UsbSession) {
            sessions.remove(session)
            // if we have multiple sessions make sure to switch to another if we removed active one
            if (session ==_sessionUsb.value) {
                _sessionUsb.value = if(sessions.isEmpty()) null else sessions.keys.last()
            }
        }

        override fun onError(session: UsbSession, error: Throwable) {
            if (error is NoPermissionsException) {
                sessions[session] = false

                // if there is no other active session use the one that has no permissions
                if (_sessionUsb.value == null) {
                    _sessionUsb.value = session
                }
            }
            _error.value = error
        }
    }

    private var nfcListener = NfcSessionListener { session ->
        // if we've got NFC tag we should execute command immediately otherwise tag will be lost
        _sessionNfc.value = session
        session.executeDemoCommands()
    }

    /**
     * Start usb discovery as soon as we create model
     */
    init {
        yubikitManager.startUsbDiscovery(true, usbListener)

        Logger.getInstance().setLogger(object : ILogger {
            override fun logDebug(message: String?) {
                Log.d(TAG, message ?: "")
            }

            override fun logError(message: String?, throwable: Throwable?) {
                Log.e(TAG, message, throwable)
            }
        })
    }

    /**
     * Stop usb discovery before model has been destoyed
     */
    override fun onCleared() {
        yubikitManager.stopUsbDiscovery()
        super.onCleared()
    }

    /**
     * Start nfc discovery when you activity in foreground
     */
    fun startNfcDiscovery(activity: Activity) {
        try {
            yubikitManager.startNfcDiscovery(false, activity, nfcListener)
        } catch (e: NfcDisabledException) {
            _error.value = e
        } catch (e: NfcNotFoundException) {
            _error.value = e
        }
    }

    /**
     * Stop nfc discovery before your activity goes to background
     */
    fun stopNfcDiscovery(activity: Activity) {
        yubikitManager.stopNfcDiscovery(activity)
    }

    /**
     * Check if user granted permissions to connect to device
     */
    fun hasPermission(session: UsbSession) : Boolean {
        return sessions[session] == true
    }


    /**
     * Handler for Run demo button
     */
    fun executeDemoCommands(session: UsbSession? = _sessionUsb.value) {
        when {
            // if user didn't accept permissions let's prompt him again
            sessions[session] == false -> yubikitManager.startUsbDiscovery(true, usbListener)
            session != null -> session.executeDemoCommands()
            else -> _error.value = UsbDeviceNotFoundException("The key is not plugged in")
        }
    }

    /**
     * Execute sequence for APDU commands for specific connection
     */
    open fun YubiKeySession.executeDemoCommands() {
        // do nothing by default
    }
}