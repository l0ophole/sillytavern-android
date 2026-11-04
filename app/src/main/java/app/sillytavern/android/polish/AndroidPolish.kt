package app.sillytavern.android.polish

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sillytavern.android.MainActivity
import app.sillytavern.android.R
import app.sillytavern.android.SillyTavernApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Counts started activities so the notifier knows whether the user is looking at the app. */
object AppForeground : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val isForeground get() = started > 0
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)
    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

object Routes {
    const val EXTRA_CHARACTER = "open_character"
    const val EXTRA_GROUP = "open_group"
    fun openIntent(ctx: Context, character: String?, group: String?): Intent =
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { character?.let { putExtra(EXTRA_CHARACTER, it) }; group?.let { putExtra(EXTRA_GROUP, it) } }

    /** Navigation route for an intent coming from a notification or the widget, or null. */
    fun routeFor(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_GROUP)?.let { return "groupchat/${Uri.encode(it)}" }
        intent.getStringExtra(EXTRA_CHARACTER)?.let { return "chat/${Uri.encode(it)}" }
        return null
    }
}

private const val CH_RUNNING = "generation"
private const val CH_REPLY = "replies"

private fun ensureChannels(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CH_RUNNING, "Generating a reply", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CH_REPLY, "Replies", NotificationManager.IMPORTANCE_DEFAULT))
}

/** Foreground service that exists only to keep the process alive while a reply is being generated. */
class GenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val who = intent?.getStringExtra("name").orEmpty()
        val open = PendingIntent.getActivity(this, 0, Routes.openIntent(this, null, null), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CH_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (who.isBlank()) "Writing a reply" else "$who is writing")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        return START_NOT_STICKY
    }
}

class AndroidGenerationSink(private val ctx: Context) : GenerationSink {
    override fun running(characterName: String) {
        runCatching { ContextCompatStart.start(ctx, Intent(ctx, GenerationService::class.java).putExtra("name", characterName)) }
    }

    override fun idle() {
        ctx.stopService(Intent(ctx, GenerationService::class.java))
        ContinueWidget.refreshAsync(ctx)
    }

    override fun replyReady(characterName: String, preview: String, characterFile: String?, groupId: String?) {
        ensureChannels(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, (characterFile ?: groupId ?: "").hashCode(), Routes.openIntent(ctx, characterFile, groupId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_REPLY)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(characterName).setContentText(preview).setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(2 + (characterFile ?: groupId ?: "").hashCode().and(0xFFFF), n) }
    }
}

private object ContextCompatStart {
    fun start(ctx: Context, i: Intent) = ContextCompat.startForegroundService(ctx, i)
}

fun Context.isPowerSave(): Boolean = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

/** "Continue" home-screen widget: the most recently written chat. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { update(context, manager, ids) } finally { pending.finish() } }
    }

    companion object {
        fun refreshAsync(ctx: Context) {
            val app = ctx.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val m = AppWidgetManager.getInstance(app)
                    val ids = m.getAppWidgetIds(ComponentName(app, ContinueWidget::class.java))
                    if (ids.isNotEmpty()) update(app, m, ids)
                }
            }
        }

        private fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            val app = ctx.applicationContext as SillyTavernApp
            val c = app.container
            val v = RemoteViews(ctx.packageName, R.layout.widget_continue)
            val recent = if (!app.isUnlocked()) null else runCatching { RecentChatFinder.find(c.paths, c.characters, c.chats) }.getOrNull()
            val tap = PendingIntent.getActivity(ctx, 7, Routes.openIntent(ctx, recent?.characterFile, null), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setTextViewText(R.id.widget_title, if (!app.isUnlocked()) "Locked" else recent?.characterName ?: "SillyTavern")
            v.setTextViewText(R.id.widget_line, if (!app.isUnlocked()) "Open the app to unlock" else recent?.lastLine?.ifBlank { null } ?: "No chats yet")
            v.setOnClickPendingIntent(R.id.widget_root, tap)
            ids.forEach { m.updateAppWidget(it, v) }
        }
    }
}
