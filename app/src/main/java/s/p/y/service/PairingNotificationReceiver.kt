package s.p.y.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import s.p.y.R
import s.p.y.adb.AdbManager
import s.p.y.ui.MainActivity

class PairingNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PairingReceiver"
        const val CHANNEL_ID = "spy_notification_pairing"
        const val NOTIF_ID = 3001
        const val ACTION_PAIR = "s.p.y.ACTION_PAIR_INPUT"
        const val ACTION_CANCEL = "s.p.y.ACTION_PAIR_CANCEL"
        const val KEY_PAIRING_CODE = "key_pairing_code"

        fun showPairingNotification(context: Context, pairPort: Int?, connectPort: Int?) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Wireless ADB Quick Pairing",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Enter pairing code directly from the notification shade"
                }
                manager.createNotificationChannel(channel)
            }

            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel("Enter 6-digit pairing code")
                .build()

            val pairIntent = Intent(context, PairingNotificationReceiver::class.java).apply {
                action = ACTION_PAIR
                putExtra("pair_port", pairPort ?: -1)
                putExtra("connect_port", connectPort ?: -1)
            }
            val pairPendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                pairIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            )

            val pairAction = NotificationCompat.Action.Builder(
                R.drawable.ic_wifi_adb,
                "Pair",
                pairPendingIntent
            ).addRemoteInput(remoteInput).build()

            val cancelIntent = Intent(context, PairingNotificationReceiver::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                103,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val portInfo = if (pairPort != null && pairPort > 0) "Port: $pairPort" else "Pairing active"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wifi_adb)
                .setContentTitle("Wireless ADB Pairing Helper ($portInfo)")
                .setContentText("Pull down & type the 6-digit code below without leaving Settings:")
                .addAction(pairAction)
                .addAction(R.drawable.ic_close, "Cancel", cancelPendingIntent)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            manager.notify(NOTIF_ID, notification)
        }

        fun cancelNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (intent.action == ACTION_CANCEL) {
            manager.cancel(NOTIF_ID)
            return
        }

        if (intent.action == ACTION_PAIR) {
            val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
            val code = remoteInputResults?.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim()

            if (code.isNullOrBlank()) {
                Log.w(TAG, "Empty pairing code received")
                return
            }

            var pairPort = intent.getIntExtra("pair_port", -1)
            val connectPort = intent.getIntExtra("connect_port", -1)

            val adbManager = AdbManager.getInstance(context)

            // If pairPort wasn't set, fallback to last known
            if (pairPort <= 0) {
                pairPort = adbManager.getLastPairPort().takeIf { it > 0 } ?: 37123
            }

            // Show pairing in progress notification
            val progressNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wifi_adb)
                .setContentTitle("Pairing with 127.0.0.1:$pairPort...")
                .setContentText("Executing adb pair...")
                .setOngoing(true)
                .build()
            manager.notify(NOTIF_ID, progressNotif)

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                val pairResult = adbManager.pair("127.0.0.1", pairPort, code)
                if (pairResult.isSuccess || pairResult.stdout.contains("Successfully paired", ignoreCase = true)) {
                    val portToConnect = if (connectPort > 0) connectPort else 5555
                    val connectResult = adbManager.connect("127.0.0.1", portToConnect)

                    val openAppIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val openAppPendingIntent = PendingIntent.getActivity(
                        context,
                        104,
                        openAppIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val successNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_wifi_adb)
                        .setContentTitle("✔ Wireless ADB Paired & Connected!")
                        .setContentText("Tap to open Spy terminal.")
                        .setContentIntent(openAppPendingIntent)
                        .setAutoCancel(true)
                        .build()
                    manager.notify(NOTIF_ID, successNotif)
                } else {
                    val failNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_wifi_adb)
                        .setContentTitle("❌ Pairing Failed")
                        .setContentText("Output: ${pairResult.stdout.take(60)}")
                        .setAutoCancel(true)
                        .build()
                    manager.notify(NOTIF_ID, failNotif)
                }
            }
        }
    }
}
