package com.quietstack.voicetrade.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quietstack.voicetrade.domain.repository.AlertsRepository
import com.quietstack.voicetrade.domain.repository.ResearchRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerDeps {
    fun alerts(): AlertsRepository
    fun research(): ResearchRepository
}

private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, WorkerDeps::class.java)

/** Every 15 minutes: has any of the user's price alerts fired? If so, notify and mark it delivered. */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = deps(applicationContext).alerts()
        alerts.pending().onSuccess { fired ->
            fired.forEach { Notifications.showAlert(applicationContext, it) }
            if (fired.isNotEmpty()) alerts.ack(fired.map { it.id })
        }
        return Result.success()
    }
}

/** Once a day at 8:30 am India time: fetch the market wrap and notify. Schedules itself again for tomorrow. */
class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Scheduler.briefingEnabled(applicationContext)) {
            val lang = if (Scheduler.isHindi(applicationContext)) "hi" else "en"
            deps(applicationContext).research().briefing(lang).onSuccess { b ->
                Notifications.showBriefing(applicationContext, b.title, b.text)
            }
            Scheduler.scheduleBriefing(applicationContext)
        }
        return Result.success()
    }
}

object Scheduler {
    private const val PREFS = "notifications"
    private const val ALERTS = "alerts_poll"
    private const val BRIEFING = "morning_briefing"

    fun briefingEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("briefing", true)

    fun setBriefingEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("briefing", on).apply()
        if (on) scheduleBriefing(context) else WorkManager.getInstance(context).cancelUniqueWork(BRIEFING)
    }

    fun isHindi(context: Context) = com.quietstack.voicetrade.core.i18n.AppLocale.effective(context) == "hi"

    /** Call on every app start: both jobs survive restarts, so this only makes sure they exist. */
    fun start(context: Context) {
        Notifications.createChannels(context)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ALERTS, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertsWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        if (briefingEnabled(context)) scheduleBriefing(context)
    }

    fun scheduleBriefing(context: Context) {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.now(zone)
        var next = now.withHour(8).withMinute(30).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BRIEFING, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BriefingWorker>().setInitialDelay(Duration.between(now, next)).setConstraints(online).build(),
        )
    }
}
