package app.hoyotray.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hoyotray.R
import app.hoyotray.data.Repo
import app.hoyotray.data.RewardsOutcome
import app.hoyotray.ui.MainActivity
import app.hoyotray.widget.HoyoWidget
import java.util.concurrent.TimeUnit

object Background {
    private const val PERIODIC = "refresh"
    private const val CHANNEL_REWARDS = "rewards"
    private const val CHANNEL_ALERTS = "alerts"
    private const val ID_REWARDS = 1
    private const val ID_LOGIN = 2

    /** Every 30 minutes; no network constraint so the widget's estimates still advance offline. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun redeemNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setInputData(workDataOf("redeem" to true)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("redeem", ExistingWorkPolicy.KEEP, req)
    }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REWARDS, "Daily rewards", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Check-ins, new redemption codes and redeemed codes" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Login problems", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When the HoYoLAB login needs renewing" })
    }

    private fun canNotify(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    fun notifyRewards(ctx: Context, outcome: RewardsOutcome) {
        if (outcome.messages.isEmpty() || !canNotify(ctx)) return
        val text = outcome.messages.joinToString("\n")
        val b = NotificationCompat.Builder(ctx, CHANNEL_REWARDS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HoyoTray")
            .setContentText(outcome.messages.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (outcome.newCodes) {
            val redeem = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, RedeemReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Redeem", redeem)
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_REWARDS, b.build())
    }

    fun notifyLoginExpired(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HoYoLAB login expired")
            .setContentText("Open HoyoTray to log in again.")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_LOGIN, n)
    }

    fun cancelRewardsNotification(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_REWARDS)
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        val redeem = inputData.getBoolean("redeem", false)
        val ok = Repo.refresh()
        if (ok) {
            val outcome = Repo.runRewards(redeemNotifyCodes = redeem)
            if (redeem) Background.cancelRewardsNotification(applicationContext)
            Background.notifyRewards(applicationContext, outcome)
        } else if (Repo.state.value.globalError == Repo.LOGIN_EXPIRED && !Repo.state.value.cache.authNotified) {
            Background.notifyLoginExpired(applicationContext)
            Repo.markAuthNotified()
        }
        HoyoWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/** "Redeem" button on the new-codes notification. */
class RedeemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Background.redeemNow(context)
}
