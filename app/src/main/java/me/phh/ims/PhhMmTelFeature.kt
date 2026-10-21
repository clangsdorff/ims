//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.os.Bundle
import android.os.Message
import android.telephony.Rlog
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsCallProfile
import android.telephony.ims.ImsCallSessionListener
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsStreamMediaProfile
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.stub.ImsCallSessionImplBase
import android.telephony.ims.stub.ImsCallSessionImplBase.State
import android.telephony.ims.stub.ImsMultiEndpointImplBase
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import android.telephony.ims.stub.ImsSmsImplBase
import android.telephony.ims.stub.ImsUtImplBase
import me.phh.mtk.MtkImsHandler
import me.phh.sip.SipHandler
import me.phh.sip.randomBytes
import me.phh.sip.toHex

// frameworks/base/telephony/java/android/telephony/ims/feature/MmTelFeature.java
// We extend it through java once because kotlin cannot override
// changeEnabledCapabilities that has a protected (CapabilityCallbackProxy)
// argument. See this stackoverflow link for why we cannot do it directly:
// https://stackoverflow.com/questions/49284094/inheritance-from-java-class-with-a-public-method-accepting-a-protected-class-in/49287402#49287402
class PhhMmTelFeature(val slotId: Int) : PhhMmTelFeatureProtected(slotId) {
    companion object {
        private const val TAG = "PHH MmTelFeature"
    }

    val imsSms = PhhImsSms(slotId)
    @Volatile private var currentMtk: MtkImsHandler? = null
    @Volatile private var currentSip: SipHandler? = null
    val sipHandler: SipHandler get() = currentSip ?: throw IllegalStateException("No SIM in slot $slotId")
    fun sipHandlerOrNull(): SipHandler? = currentSip
    private var sipSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var featureReady = false
    private val subscriptionsListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = syncSip()
    }
    private var callListener: ImsCallSessionListener? = null
    private var outgoingState = State.IDLE

    override fun createCallProfile(callSessionType: Int, callType: Int): ImsCallProfile {
        Rlog.d(TAG, "$slotId createCallProfile $callSessionType $callType")
        // check why not called
        // figure out RilHolder.INSTANCE.getRadios(mSlotId).setImsCfg ? Probably only required
        // if we leave ims to the radio...
        return ImsCallProfile(callSessionType, callType)
    }
    override fun createCallSession(profile: ImsCallProfile): ImsCallSessionImplBase {
        Rlog.d(TAG, "$slotId createCallSession")
        return object: ImsCallSessionImplBase() {
            private val mCallId = randomBytes(12).toHex()
            override fun getCallId(): String {
                return mCallId
            }

            override fun close() {
                Rlog.d(TAG, "Closing call")
            }

            override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                Rlog.d(TAG, "Accepting call with callType $callType profile $profile")
            }

            override fun isInCall(): Boolean {
                return true
            }

            override fun start(callee: String, profile: ImsCallProfile) {
                Rlog.d(TAG, "Starting call with $callee profile $profile")
                outgoingState = State.INITIATED
                useAndroidCallAudio()
                sipHandler.onOutgoingCallProgress = { statusCode ->
                    if (statusCode in 180..189) {
                        callListener?.callSessionProgressing(profile.mediaProfile)
                    } else if (statusCode == 200) {
                        outgoingState = State.ESTABLISHED
                        callListener?.callSessionInitiated(profile)
                    }
                }
                sipHandler.call(callee)
            }

            override fun getState(): Int {
                return outgoingState
            }

            override fun setListener(listener: ImsCallSessionListener) {
                Rlog.d(TAG, "Setting CallListener to $listener")
                callListener = listener
            }

            override fun reject(reason: Int) {
                Rlog.d(TAG, "Rejecting call with reason $reason")
            }

            override fun terminate(reason: Int) {
                Rlog.d(TAG, "Terminating call with reason $reason")
                sipHandler.terminateCall()
            }
        }
    }

    override fun getFeatureState(): Int {
        Rlog.d(TAG, "$slotId getFeatureState")
        // always ready for now... Also STATE_INITIALIZING, STATE_UNAVAILABLE
        return ImsFeature.STATE_READY
    }

    override fun getMultiEndpoint(): ImsMultiEndpointImplBase {
        Rlog.d(TAG, "$slotId getMultiEndpoint")
        return ImsMultiEndpointImplBase()
    }

    override fun getSmsImplementation(): ImsSmsImplBase {
        Rlog.d(TAG, "$slotId getSmsImplementation")
        return imsSms
    }

    override fun getUt(): ImsUtImplBase {
        Rlog.d(TAG, "$slotId getUt")
        return ImsUtImplBase()
    }

    override fun onFeatureReady() {
        Rlog.d(TAG, "$slotId onFeatureReady")
        if (featureReady) return
        featureReady = true
        val imsService = PhhImsService.Companion.instance!!
        imsService.getSystemService(SubscriptionManager::class.java)
            .addOnSubscriptionsChangedListener(imsService.mainExecutor, subscriptionsListener)
        syncSip()
    }

    // SIP follows the SIM in this slot: none without one, a fresh identity when it changes
    @Synchronized
    private fun syncSip() {
        val imsService = PhhImsService.Companion.instance ?: return
        val subId = imsService.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(slotId)?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (subId == sipSubId) return
        Rlog.d(TAG, "$slotId subscription changed from $sipSubId to $subId")
        currentMtk?.let {
            currentMtk = null
            it.shutdown()
            imsService.getRegistration(slotId).onDeregistered(null)
        }
        currentSip?.let {
            currentSip = null
            imsSms.sipHandler = null
            it.shutdown()
            imsService.getRegistration(slotId).onDeregistered(null)
        }
        sipSubId = subId
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        if (MtkImsHandler.isAvailable(slotId)) {
            startMtk(imsService)
            return
        }
        var handler: SipHandler? = null
        try {
            handler = SipHandler(imsService, slotId)
            startSip(imsService, handler)
        } catch (e: Exception) {
            // The caller of onFeatureReady swallows exceptions
            Rlog.w(TAG, "$slotId could not start SIP", e)
            if (currentSip === handler) currentSip = null
            imsSms.sipHandler = null
            handler?.shutdown()
            imsService.getRegistration(slotId).onDeregistered(null)
            sipSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    // The modem registers on its own; calls and SMS stay on CS until they are wired to the modem
    private fun startMtk(imsService: PhhImsService) {
        Rlog.d(TAG, "$slotId using the MediaTek modem IMS")
        capabilityMask = 0
        notifyCapabilitiesStatusChanged(MmTelCapabilities())
        val handler = MtkImsHandler(imsService, slotId)
        handler.onRegistered = {
            if (currentMtk === handler) imsService.getRegistration(slotId).onRegistered(REGISTRATION_TECH_LTE)
        }
        handler.onDeregistered = {
            if (currentMtk === handler) imsService.getRegistration(slotId).onDeregistered(null)
        }
        currentMtk = handler
        imsService.getRegistration(slotId).onRegistering(REGISTRATION_TECH_LTE)
        handler.start()
    }

    private fun startSip(imsService: PhhImsService, handler: SipHandler) {
        // call onRegistering first then
        // register SIP here and call onRegistered after .. register.
        handler.imsFailureCallback = {
            if (currentSip === handler) imsService.getRegistration(slotId).onDeregistered(null)
        }
        handler.imsReadyCallback = {
            if (currentSip === handler) imsService.getRegistration(slotId).onRegistered(REGISTRATION_TECH_LTE)
        }
        handler.onSmsReceived = imsSms::onSmsReceived
        handler.onSmsStatusReportReceived = imsSms::onSmsStatusReportReceived

        handler.onIncomingCall = { handle: Object, from: String, extras: Map<String, String> -> 
            val callProfile = ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE,
                Bundle(),
                ImsStreamMediaProfile(
                    ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB,
                    ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                    ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                    ImsStreamMediaProfile.DIRECTION_INACTIVE,
                    ImsStreamMediaProfile.RTT_MODE_DISABLED,
                ))

            outgoingState = State.IDLE
            callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, from)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_DISPLAY_TEXT, from)
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR,
                if (extras["privacy"] == "true") ImsCallProfile.OIR_PRESENTATION_RESTRICTED
                else ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED)
            notifyIncomingCall(object: ImsCallSessionImplBase() {
                var mState = State.IDLE
                override fun getCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun setListener(listener: ImsCallSessionListener) {
                    Rlog.d(TAG, "Setting CallListener to $listener")
                    callListener = listener
                }

                override fun getCallId(): String {
                    return extras["call-id"]!!
                }

                override fun getLocalCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getRemoteCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getProperty(name: String): String {
                    Rlog.d(TAG, "ImsCallSession.getProperty " + name)
                    return ""
                }

                override fun getState(): Int {
                    return mState
                }

                override fun start(callee: String, profile: ImsCallProfile) {
                    Rlog.d(TAG, "Starting call with $callee")
                }

                override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                    Rlog.d(TAG, "Accepting call with profile $profile")
                    sipHandler.acceptCall()
                    mState = State.ESTABLISHED
                    callListener?.callSessionInitiated(callProfile)
                }

                override fun deflect(deflectNumber: String?) {
                    Rlog.d(TAG, "Deflecting call to $deflectNumber")
                }

                override fun reject(reason: Int) {
                    sipHandler.rejectCall()
                    Rlog.d(TAG, "Rejecting call $reason")
                }

                override fun terminate(reason: Int) {
                    sipHandler.terminateCall()
                    Rlog.d(TAG, "Terminating call")
                }

            }, Bundle())
            useAndroidCallAudio()
        }
        handler.onCancelledCall = cancelled@{ param: Object, s: String, map: Map<String, String> ->
            if (outgoingState == State.TERMINATED) return@cancelled
            val statusCode = map["statusCode"]?.toInt() ?: -1
            val lost = map["lost"] != null
            val reason = when {
                statusCode >= 300 -> ImsReasonInfo(sipStatusToReason(statusCode), statusCode, map["statusString"])
                // CS retry makes telephony redial the number over CS instead of failing it
                lost && outgoingState == State.INITIATED -> ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED, 0, null)
                lost -> ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_NETWORK_NO_SERVICE, 0, null)
                map["local"] != null -> ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, null)
                else -> ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, 0, null)
            }
            Rlog.d(TAG, "Call ended in outgoing state ${State.toString(outgoingState)}: $reason")
            if (outgoingState == State.INITIATED && (statusCode >= 300 || lost)) {
                callListener?.callSessionInitiatingFailed(reason)
            } else {
                callListener?.callSessionTerminated(reason)
            }
            outgoingState = State.TERMINATED
        }

        currentSip = handler
        imsSms.sipHandler = handler
        imsService.getRegistration(slotId).onRegistering(REGISTRATION_TECH_LTE)
        handler.getVolteNetwork()
    }

    // MmTelFeature.setCallAudioHandler(AUDIO_HANDLER_ANDROID), API 34+: Telecom then uses MODE_IN_COMMUNICATION
    private fun useAndroidCallAudio() {
        try {
            MmTelFeature::class.java.getMethod("setCallAudioHandler", Int::class.javaPrimitiveType).invoke(this, 0)
        } catch (e: ReflectiveOperationException) {
            Rlog.w(TAG, "setCallAudioHandler unavailable", e)
        }
    }

    private fun sipStatusToReason(statusCode: Int): Int = when (statusCode) {
        380 -> ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED
        400 -> ImsReasonInfo.CODE_SIP_BAD_REQUEST
        403 -> ImsReasonInfo.CODE_SIP_FORBIDDEN
        404 -> ImsReasonInfo.CODE_SIP_NOT_FOUND
        408 -> ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT
        480 -> ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE
        484 -> ImsReasonInfo.CODE_SIP_BAD_ADDRESS
        486, 600 -> ImsReasonInfo.CODE_SIP_BUSY
        487 -> ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED
        488, 606 -> ImsReasonInfo.CODE_SIP_NOT_ACCEPTABLE
        500 -> ImsReasonInfo.CODE_SIP_SERVER_INTERNAL_ERROR
        503 -> ImsReasonInfo.CODE_SIP_SERVICE_UNAVAILABLE
        504 -> ImsReasonInfo.CODE_SIP_SERVER_TIMEOUT
        603 -> ImsReasonInfo.CODE_SIP_USER_REJECTED
        in 400..499 -> ImsReasonInfo.CODE_SIP_CLIENT_ERROR
        in 500..599 -> ImsReasonInfo.CODE_SIP_SERVER_ERROR
        else -> ImsReasonInfo.CODE_SIP_GLOBAL_ERROR
    }

    override fun onFeatureRemoved() {
        Rlog.d(TAG, "$slotId onFeatureRemoved")
    }

    // ints are @MmTelCapabilities.MmTelCapability and @ImsRegistrationImplBase.ImsRegistrationTech
    override fun queryCapabilityConfiguration(capability: Int, radioTech: Int): Boolean {
        Rlog.d(TAG, "$slotId queryCapabilityConfiguration $capability $radioTech")
        return capability == MmTelCapabilities.CAPABILITY_TYPE_SMS || capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
    }

    override fun setUiTtyMode(mode: Int, onCompleteMessage: Message?) {
        Rlog.d(TAG, "$slotId setUiTtyMode $onCompleteMessage")
    }

    override fun shouldProcessCall(numbers: Array<out String>): Int {
        Rlog.d(TAG, "Should process call? ${numbers.toList()}")
        return if (currentSip?.isRegistered == true) PROCESS_CALL_IMS else PROCESS_CALL_CSFB
    }
}
