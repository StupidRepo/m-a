package com.vayunmathur.email

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.OutboxManager
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.email.data.TokenRefresher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = EmailDatabase.getInstance(application).emailDao()
    private val emailManager = EmailManager()
    
    val accounts = dao.getAccountsFlow()

    val outbox: Flow<List<OutboxEntry>> = dao.getOutboxFlow()
    
    private val _selectedAccountEmail = MutableStateFlow<String?>(null)
    val selectedAccountEmail: StateFlow<String?> = _selectedAccountEmail

    val selectedAccount = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(null)
        else accounts.map { list -> list.find { it.email == email } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val folders = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(emptyList())
        else dao.getFoldersFlow(email)
    }
    
    private val _selectedFolderName = MutableStateFlow("INBOX")
    val selectedFolderName: StateFlow<String> = _selectedFolderName

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedMessageUids = MutableStateFlow<Set<Long>>(emptySet())
    val selectedMessageUids: StateFlow<Set<Long>> = _selectedMessageUids

    val messages: Flow<List<EmailMessage>> = combine(
        _selectedAccountEmail,
        _selectedFolderName,
        _searchQuery
    ) { email, folder, query ->
        Triple(email, folder, query)
    }.flatMapLatest { (email, folder, query) ->
        if (email == null) {
            // Unified Inbox
            if (query.isEmpty()) dao.getUnifiedMessagesFlow("INBOX")
            else dao.searchUnifiedMessagesFlow("INBOX", query)
        } else {
            if (query.isEmpty()) {
                dao.getMessagesFlow(email, folder)
            } else {
                dao.searchMessagesFlow(email, folder, query)
            }
        }
    }

    init {
        viewModelScope.launch {
            accounts.first().firstOrNull()?.let {
                _selectedAccountEmail.value = it.email
            }
        }
    }

    fun selectAccount(email: String) {
        _selectedAccountEmail.value = if (email.isEmpty()) null else email
        _selectedFolderName.value = "INBOX"
        _searchQuery.value = ""
    }

    fun selectFolder(folderName: String) {
        _selectedFolderName.value = folderName
        _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleMessageSelection(uid: Long) {
        val current = _selectedMessageUids.value
        if (uid in current) {
            _selectedMessageUids.value = current - uid
        } else {
            _selectedMessageUids.value = current + uid
        }
    }

    fun clearSelection() {
        _selectedMessageUids.value = emptySet()
    }

    fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
        viewModelScope.launch {
            dao.updateReadStatus(accountEmail, folderName, uid, isRead)
        }
    }

    fun bulkMarkAsRead(accountEmail: String, uids: List<Long>, isRead: Boolean) {
        viewModelScope.launch {
            dao.updateBulkReadStatus(accountEmail, uids, isRead)
            clearSelection()
        }
    }

    fun refresh(context: android.content.Context) {
        EmailSyncWorker.runOneOffSync(context)
    }

    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage? {
        return dao.getMessage(accountEmail, folderName, uid)
    }

    fun getThread(accountEmail: String, threadId: String): Flow<List<EmailMessage>> {
        return dao.getThreadFlow(accountEmail, threadId)
    }

    suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> {
        return dao.getAttachments(accountEmail, messageId)
    }

    fun logout(context: android.content.Context) {
        val currentEmail = _selectedAccountEmail.value ?: return
        viewModelScope.launch {
            val account = dao.getAccounts().find { it.email == currentEmail }
            if (account != null) {
                dao.deleteAccount(account)
                dao.clearFolders(currentEmail)
                dao.clearMessages(currentEmail)
            }
            val remaining = dao.getAccounts()
            if (remaining.isEmpty()) {
                _selectedAccountEmail.value = null
                EmailSyncWorker.cancelSync(context)
            } else {
                _selectedAccountEmail.value = remaining.first().email
            }
        }
    }

    fun sendEmailFrom(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        onSuccess: () -> Unit,
        /**
         * Called after the message has been queued to the outbox because the
         * immediate send failed. The supplied string is the underlying error
         * message — the UI typically surfaces it via a Snackbar like
         * "Saved to Outbox: …" and then pops the composer.
         */
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            suspend fun attemptSend(acct: EmailAccount) {
                emailManager.sendMessage(
                    context = getApplication(),
                    host = "smtp.gmail.com",
                    user = acct.email,
                    auth = EmailManager.AuthType.OAuth2(acct.accessToken),
                    to = to,
                    subject = subject,
                    body = body,
                    cc = cc,
                    attachments = attachments,
                    inReplyTo = inReplyTo,
                    references = references,
                )
            }

            try {
                try {
                    attemptSend(account)
                } catch (e: javax.mail.AuthenticationFailedException) {
                    // Token likely expired — refresh + retry once before
                    // giving up and saving to the outbox.
                    val refreshed = TokenRefresher.refresh(getApplication(), account)
                        ?: throw e
                    attemptSend(refreshed)
                }
                onSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "Unknown error"
                // Persist to outbox so it survives app death; the worker will
                // retry every 5 minutes until it lands.
                try {
                    OutboxManager.enqueue(
                        context = getApplication(),
                        accountEmail = account.email,
                        to = to,
                        subject = subject,
                        body = body,
                        cc = cc,
                        attachments = attachments,
                        inReplyTo = inReplyTo,
                        references = references,
                        initialError = msg,
                    )
                } catch (queueError: Exception) {
                    // If even queueing fails, fall through to the error callback so
                    // the user sees *something* instead of silently losing the draft.
                    onError("$msg (and outbox save failed: ${queueError.message})")
                    return@launch
                }
                onError(msg)
            }
        }
    }

    fun deleteOutboxEntry(entry: OutboxEntry) {
        viewModelScope.launch {
            OutboxManager.delete(getApplication(), entry)
        }
    }

    fun sendOutboxNow(context: android.content.Context) {
        OutboxSendWorker.runNow(context)
    }

    fun downloadAttachment(
        attachment: Attachment,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val account = selectedAccount.value ?: return onError("No account selected")
        viewModelScope.launch {
            try {
                val path = emailManager.downloadAttachment(
                    context = getApplication(),
                    host = "imap.gmail.com",
                    user = account.email,
                    auth = EmailManager.AuthType.OAuth2(account.accessToken),
                    folderName = attachment.folderName,
                    uid = attachment.messageId,
                    partId = attachment.partId,
                    fileName = attachment.fileName
                )
                dao.updateAttachmentLocalUri(account.email, attachment.messageId, attachment.partId, path)
                onSuccess(path)
            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
    }
}
