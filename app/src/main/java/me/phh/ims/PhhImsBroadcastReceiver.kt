//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.Rlog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.phh.sip.SipHandler

class PhhImsBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PHH ImsBroadcastReceiver"
    }

    val ALARM_PERIODIC_REGISTER = "com.langsdorff.flossims.ALARM_PERIODIC_REGISTER"

    override fun onReceive(ctxt: Context, intent: Intent) {
        Rlog.d(TAG, "Alarm fired with ${intent.action}")
        if (intent.action == ALARM_PERIODIC_REGISTER) {
            val imsService = PhhImsService.Companion.instance!!
            // rearm alarm
            imsService.armPeriodicRegisterAlarm()
            // stay awake until the REGISTER watchdog can judge the reply
            ctxt.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flossims:register")
                .acquire(SipHandler.REGISTER_REPLY_TIMEOUT_MS + 5_000L)
            // XXX take some lock until this comes back?
            // (not function return, but callback after notify)
            CoroutineScope(Dispatchers.IO).launch {
                imsService.mmTelFeature?.sipHandler?.register()
            }
            return
        }
    }
}
