package com.m57.hermescontrol.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ShareInbox"

/**
 * One-shot handoff for content shared into the app (#1601). [ingest] reads the intent off the
 * main thread and parks the result here; the chat takes it once it is connected, so a cold
 * start, a signed-out detour, or a rotation never drops or duplicates it. Nothing is sent.
 */
object ShareInbox {
    internal var pending: IncomingShare? by mutableStateOf(null)
        private set

    // Outlives the activity: a rotation mid-read must not abandon the share.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun offer(share: IncomingShare) {
        if (share.text == null && share.attachments.isEmpty() && share.failed.isEmpty()) return
        pending = pending?.plus(share) ?: share
    }

    internal fun take(): IncomingShare? = pending.also { pending = null }

    /** Drops anything parked, for tests. */
    internal fun clear() {
        pending = null
    }

    /** Copies [intent]'s payload (a defensive copy; the caller may mutate its own) and parks it. */
    fun ingest(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val appContext = context.applicationContext
        val copy = Intent(intent)
        scope.launch {
            val share =
                runCatching { readIncomingShare(appContext, copy) }
                    .onFailure { Log.w(TAG, "Could not read incoming share", it) }
                    .getOrNull() ?: return@launch
            offer(share)
        }
    }
}

private fun readIncomingShare(
    context: Context,
    intent: Intent,
): IncomingShare? {
    val text =
        composeSharedText(
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
        )
    val uris = LinkedHashSet<String>()
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat
            .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.forEach { uris += it.toString() }
    } else {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it.toString() }
    }
    // Some senders only populate ClipData; the same URI must still appear once.
    intent.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it.toString() }
    }
    if (text == null && uris.isEmpty()) return null

    val resolver = context.contentResolver
    val staged =
        stageShareUris(
            uris = uris.toList(),
            ownAuthority = "${context.packageName}.fileprovider",
            directory = File(context.cacheDir, SHARE_INBOX_DIRECTORY),
            source = { uri -> resolverSource(resolver, Uri.parse(uri), intent.type) },
            publish = { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            },
        )
    return staged.copy(text = text)
}

private fun resolverSource(
    resolver: ContentResolver,
    uri: Uri,
    intentType: String?,
): ShareSource {
    var name: String? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index)
        }
    }
    val type = resolver.getType(uri) ?: intentType?.takeIf { !it.contains('*') }
    return ShareSource(
        displayName = name ?: uri.lastPathSegment,
        mimeType = type,
        open = { resolver.openInputStream(uri) },
    )
}
