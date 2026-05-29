package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = EmailDatabase.getInstance(applicationContext)
        val dao = db.emailDao()
        // Mutable so we can swap in a refreshed account on auth failure.
        val accounts = dao.getAccounts().toMutableList()

        if (accounts.isEmpty()) {
            Log.d("EmailSync", "No accounts to sync")
            return Result.success()
        }

        val manager = EmailManager()
        var hasErrors = false

        for ((i, original) in accounts.withIndex()) {
            // `account` may be replaced with a refreshed copy below.
            var account = original
            try {
                Log.d("EmailSync", ">>> Starting sync for account: ${account.email}")
                var auth = EmailManager.AuthType.OAuth2(account.accessToken)

                // 1. Sync Folders (with one token-refresh retry on auth failure).
                Log.d("EmailSync", "Fetching folders for ${account.email}...")
                val folders = try {
                    manager.fetchFolders("imap.gmail.com", account.email, auth)
                } catch (e: javax.mail.AuthenticationFailedException) {
                    Log.d("EmailSync", "Auth failed for ${account.email}; refreshing token")
                    val refreshed = TokenRefresher.refresh(applicationContext, account)
                        ?: throw e
                    account = refreshed
                    accounts[i] = refreshed
                    auth = EmailManager.AuthType.OAuth2(refreshed.accessToken)
                    manager.fetchFolders("imap.gmail.com", refreshed.email, auth)
                }
                dao.insertFolders(folders)
                Log.d("EmailSync", "Successfully synced ${folders.size} folders.")

                // 2. Sync Messages for each folder
                for ((index, folder) in folders.withIndex()) {
                    if (!folder.holdsMessages) {
                        Log.d("EmailSync", "Skipping container folder: ${folder.fullName}")
                        continue
                    }
                    
                    Log.d("EmailSync", "[${index + 1}/${folders.size}] Syncing folder: ${folder.fullName}...")
                    try {
                        val (messages, attachments) = manager.fetchMessages(
                            host = "imap.gmail.com",
                            user = account.email,
                            auth = auth,
                            folderName = folder.fullName,
                            limit = 50,
                            offset = 0,
                            fetchBodies = true
                        )
                        dao.insertMessages(messages)
                        dao.insertAttachments(attachments)
                        Log.d("EmailSync", "   -> Synced ${messages.size} messages and ${attachments.size} attachments.")
                    } catch (e: Exception) {
                        Log.e("EmailSync", "   x Failed to sync folder ${folder.fullName}", e)
                    }
                }
                Log.d("EmailSync", "<<< Completed sync for account: ${account.email}")
            } catch (e: Exception) {
                Log.e("EmailSync", "Failed to sync account ${account.email}", e)
                hasErrors = true
            }
        }

        EmailWidget().updateAll(applicationContext)

        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<EmailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun runOneOffSync(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
        
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        }
    }
}
