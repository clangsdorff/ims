//SPDX-License-Identifier: GPL-2.0
package me.phh.mtk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.telephony.Rlog
import android.telephony.SubscriptionManager

// MediaTek modems run the IMS stack themselves: we only hand them the IMS PDN and relay the registration state.
// Transaction codes are those of vendor.mediatek.hardware.mtkradioex.ims V1 (stock MediaTek ImsService stubs)
class MtkImsHandler(private val ctx: Context, private val slotId: Int) {
    companion object {
        private const val TAG = "PHH MtkIms"
        private const val IMS = "vendor.mediatek.hardware.mtkradioex.ims.IMtkRadioExIms"
        private const val IMS_RESPONSE = "vendor.mediatek.hardware.mtkradioex.ims.IMtkRadioExImsResponse"
        private const val IMS_INDICATION = "vendor.mediatek.hardware.mtkradioex.ims.IMtkRadioExImsIndication"
        private const val HASH = "e52459812a302acdde2f0131532472cd88eaad52"

        private const val TX_IMS_BEARER_STATE_CONFIRM = 1
        private const val TX_SET_IMS_BEARER_NOTIFICATION = 2
        private const val TX_SET_IMS_REGISTRATION_REPORT = 12
        private const val TX_SET_IMS_CFG_FEATURE_VALUE = 39
        private const val TX_SET_RESPONSE_FUNCTIONS = 58

        private const val IND_IMS_BEARER_STATE_NOTIFY = 1
        private const val IND_IMS_BEARER_INIT = 2
        private const val IND_IMS_REGISTRATION_INFO = 14

        private const val BEARER_ACTION_RELEASE = 0
        private const val BEARER_ACTION_REQUEST = 1
        private const val BEARER_STATUS_OK = 0
        private const val BEARER_STATUS_FAIL = 1

        // ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE on TelephonyManager.NETWORK_TYPE_LTE
        private const val FEATURE_VOICE_OVER_LTE = 0
        private const val NETWORK_TYPE_LTE = 13

        private fun serviceName(slotId: Int) = "$IMS/slot${slotId + 1}"

        fun isAvailable(slotId: Int): Boolean =
            runCatching { ServiceManager.isDeclared(serviceName(slotId)) }.getOrDefault(false)
    }

    var onRegistered: (() -> Unit)? = null
    var onDeregistered: (() -> Unit)? = null
    @Volatile var isRegistered = false
        private set

    private val thread = HandlerThread("MtkIms$slotId").also { it.start() }
    private val handler = Handler(thread.looper)
    private val connectivityManager = ctx.getSystemService(ConnectivityManager::class.java)
    private var radio: IBinder? = null
    private var serial = 0
    @Volatile private var stopped = false

    private var imsNetworkCallback: ConnectivityManager.NetworkCallback? = null
    // featureId to (network, value), replayed whenever the radio connects
    private val features = HashMap<Int, Pair<Int, Int>>().apply {
        put(FEATURE_VOICE_OVER_LTE, Pair(NETWORK_TYPE_LTE, 1))
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Rlog.w(TAG, "$slotId radio died")
        handler.post {
            radio = null
            releaseImsNetwork()
            setRegistered(false)
            handler.postDelayed({ connect() }, 2_000)
        }
    }

    private open inner class VendorCallback(private val descriptor: String) : Binder(descriptor) {
        init {
            // The vendor side refuses callbacks that are not VINTF stable
            markVintfStability()
        }

        open fun onCall(code: Int, data: Parcel) {}

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> return super.onTransact(code, data, reply, flags)
                0xffffff -> {
                    data.enforceInterface(descriptor)
                    reply?.writeNoException()
                    reply?.writeInt(1)
                }
                0xfffffe -> {
                    data.enforceInterface(descriptor)
                    reply?.writeNoException()
                    reply?.writeString(HASH)
                }
                else -> {
                    data.enforceInterface(descriptor)
                    onCall(code, data)
                }
            }
            return true
        }
    }

    private val response = object : VendorCallback(IMS_RESPONSE) {
        override fun onCall(code: Int, data: Parcel) {
            Rlog.d(TAG, "$slotId response $code")
        }
    }

    private val indication = object : VendorCallback(IMS_INDICATION) {
        override fun onCall(code: Int, data: Parcel) {
            when (code) {
                IND_IMS_BEARER_STATE_NOTIFY -> {
                    data.readInt()
                    val aid = data.readInt()
                    val action = data.readInt()
                    val capability = data.readString()
                    Rlog.d(TAG, "$slotId bearer notify aid=$aid action=$action capability=$capability")
                    handler.post { onBearerNotify(aid, action, capability) }
                }
                IND_IMS_BEARER_INIT -> {
                    Rlog.d(TAG, "$slotId bearer init")
                    handler.post { releaseImsNetwork() }
                }
                IND_IMS_REGISTRATION_INFO -> {
                    data.readInt()
                    val state = data.readInt()
                    val capability = data.readInt()
                    Rlog.d(TAG, "$slotId registration info state=$state capability=$capability")
                    handler.post { setRegistered(state == 1) }
                }
                else -> Rlog.d(TAG, "$slotId indication $code")
            }
        }
    }

    fun start() = handler.post { connect() }

    fun shutdown() {
        stopped = true
        handler.post {
            radio?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
            radio = null
            releaseImsNetwork()
            setRegistered(false)
            thread.quitSafely()
        }
    }

    private fun connect() {
        if (stopped) return
        val binder = ServiceManager.checkService(serviceName(slotId))
        if (binder == null) {
            Rlog.w(TAG, "$slotId ${serviceName(slotId)} not running yet")
            handler.postDelayed({ connect() }, 5_000)
            return
        }
        binder.linkToDeath(deathRecipient, 0)
        radio = binder
        Rlog.d(TAG, "$slotId connected to ${serviceName(slotId)}")
        call(TX_SET_RESPONSE_FUNCTIONS) {
            it.writeStrongBinder(response)
            it.writeStrongBinder(indication)
        }
        call(TX_SET_IMS_REGISTRATION_REPORT) {
            it.writeInt(nextSerial())
            it.writeInt(1)
        }
        call(TX_SET_IMS_BEARER_NOTIFICATION) {
            it.writeInt(nextSerial())
            it.writeInt(1)
            it.writeInt(1)
        }
        sendFeatures()
    }

    // The modem only brings IMS up once it is told the feature is on, as MediaTek's ImsConfig does
    fun setFeature(featureId: Int, network: Int, value: Int) = handler.post {
        if (features[featureId] == Pair(network, value)) return@post
        features[featureId] = Pair(network, value)
        sendFeatures()
    }

    private fun sendFeatures() {
        val list = features.entries.toList()
        list.forEachIndexed { i, (featureId, nv) ->
            Rlog.d(TAG, "$slotId feature $featureId network ${nv.first} value ${nv.second}")
            call(TX_SET_IMS_CFG_FEATURE_VALUE) {
                it.writeInt(nextSerial())
                it.writeInt(featureId)
                it.writeInt(nv.first)
                it.writeInt(nv.second)
                it.writeInt(if (i == list.size - 1) 1 else 0)
                it.writeInt(1)
            }
        }
    }

    private fun nextSerial() = ++serial

    private fun call(code: Int, write: (Parcel) -> Unit) {
        val binder = radio ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(IMS)
            write(data)
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Rlog.w(TAG, "$slotId transaction $code failed", t)
        } finally {
            data.recycle()
        }
    }

    private fun confirmBearer(aid: Int, action: Int, status: Int) {
        Rlog.d(TAG, "$slotId bearer confirm aid=$aid action=$action status=$status")
        call(TX_IMS_BEARER_STATE_CONFIRM) {
            it.writeInt(nextSerial())
            it.writeInt(aid)
            it.writeInt(action)
            it.writeInt(status)
            it.writeInt(1)
        }
    }

    private fun onBearerNotify(aid: Int, action: Int, capability: String?) {
        if (capability != "ims") {
            // Emergency PDNs are left to the platform for now
            confirmBearer(aid, action, if (action == BEARER_ACTION_RELEASE) BEARER_STATUS_OK else BEARER_STATUS_FAIL)
            return
        }
        when (action) {
            BEARER_ACTION_REQUEST -> requestImsNetwork(aid, action)
            BEARER_ACTION_RELEASE -> {
                releaseImsNetwork()
                confirmBearer(aid, action, BEARER_STATUS_OK)
            }
            else -> Rlog.w(TAG, "$slotId unknown bearer action $action")
        }
    }

    private fun requestImsNetwork(aid: Int, action: Int) {
        releaseImsNetwork()
        val subId = ctx.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(slotId)?.subscriptionId
        if (subId == null) {
            confirmBearer(aid, action, BEARER_STATUS_FAIL)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var confirmed = false

            override fun onAvailable(network: Network) {
                Rlog.d(TAG, "$slotId IMS network available $network")
                handler.post {
                    if (imsNetworkCallback !== this || confirmed) return@post
                    confirmed = true
                    confirmBearer(aid, action, BEARER_STATUS_OK)
                }
            }

            override fun onUnavailable() {
                Rlog.w(TAG, "$slotId IMS network unavailable")
                handler.post {
                    if (imsNetworkCallback !== this) return@post
                    imsNetworkCallback = null
                    if (!confirmed) confirmBearer(aid, action, BEARER_STATUS_FAIL)
                }
            }

            override fun onLost(network: Network) {
                Rlog.d(TAG, "$slotId IMS network lost $network")
            }
        }
        imsNetworkCallback = callback
        Rlog.d(TAG, "$slotId requesting IMS network for aid $aid")
        connectivityManager.requestNetwork(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            .build(), callback, handler, 30_000)
    }

    private fun releaseImsNetwork() {
        imsNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        imsNetworkCallback = null
    }

    private fun setRegistered(registered: Boolean) {
        if (isRegistered == registered) return
        isRegistered = registered
        if (registered) onRegistered?.invoke() else onDeregistered?.invoke()
    }
}
