package com.cocateail.maya.tessa.gmail

import android.content.Context
import android.util.Log
import androidx.work.*
import com.cocateail.maya.MayaApplication
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.TessaAgent
import com.cocateail.maya.tessa.TessaPermissionType
import com.cocateail.maya.tessa.TessaTask
import com.cocateail.maya.tessa.TessaTaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 24-Hour Gmail Background Monitoring Worker.
 * Periodically and safely synchronizes with Gmail using WorkManager:
 * - Checks network & battery constraints
 * - Verifies OAuth token validity
 * - Filters relevant/actionable messages
 * - Automatically stops when Gmail is disconnected
 */
class TessaGmailMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TessaGmailMonitorWorker"
        const val WORK_NAME = "TESSA_GMAIL_24H_MONITOR"

        fun schedule(context: Context, intervalMinutes: Long = 60L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<TessaGmailMonitorWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWork
            )
            Log.i(TAG, "Scheduled 24-hour Gmail monitoring work every $intervalMinutes minutes.")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled 24-hour Gmail monitoring work.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? MayaApplication
        val repository = app?.repository
        val config = repository?.developerConfig?.value ?: DeveloperConfig()

        if (!config.isGmailConnected() || !config.gmailMonitoringEnabled) {
            Log.i(TAG, "Gmail is not connected or monitoring is disabled. Stopping background worker.")
            return@withContext Result.success()
        }

        if (config.gmailTokenExpiryEpochMs > 0 && System.currentTimeMillis() >= config.gmailTokenExpiryEpochMs) {
            Log.w(TAG, "Gmail OAuth access token is expired. Halting background check until user refreshes token.")
            return@withContext Result.failure()
        }

        val connector = RealGmailConnector(
            context = applicationContext,
            configProvider = { config }
        )

        return@withContext try {
            val listRes = connector.listMessages(query = "is:unread", maxResults = 5)
            val unread = listRes.getOrElse { emptyList() }

            val tessaAgent = repository?.tessaAgent
            val taskStore = tessaAgent?.taskStore

            for (summary in unread) {
                val fullMsgRes = connector.getMessage(summary.id)
                if (fullMsgRes.isSuccess) {
                    val msg = fullMsgRes.getOrThrow()
                    val report = EmailRelevanceFilter.filterAndAnalyze(msg)

                    if (report.isActionable && taskStore != null) {
                        // Check thread awareness to prevent duplicate tasks for same thread
                        val existingTasks = taskStore.getAllTasks()
                        val alreadyTracked = existingTasks.any {
                            it.relatedMessage?.contains(msg.id) == true || it.relatedProject == report.extractedProjectName
                        }

                        if (!alreadyTracked && report.category == EmailRelevanceCategory.PROJECT_RELATED) {
                            val newTask = TessaTask(
                                userRequest = "Process project update for '${report.extractedProjectName}' from ${msg.sender}: \"${msg.subject}\"",
                                taskType = TessaTaskState.TASK,
                                status = TessaTaskState.WAITING_FOR_PERMISSION,
                                requiresPermission = true,
                                requiredPermissions = report.requiredPermissions,
                                relatedProject = report.extractedProjectName,
                                relatedMessage = "email_id:${msg.id} thread_id:${msg.threadId} sender:${msg.sender}"
                            )
                            taskStore.saveTask(newTask)
                            Log.i(TAG, "Created autonomous TessaTask ${newTask.taskId} for incoming actionable email.")
                        }
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during Gmail background sync", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
