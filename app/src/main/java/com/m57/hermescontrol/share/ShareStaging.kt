package com.m57.hermescontrol.share

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.MAX_CHAT_ATTACHMENT_BYTES
import com.m57.hermescontrol.ui.chat.components.MAX_PASTED_IMAGE_BATCH_BYTES
import com.m57.hermescontrol.ui.chat.components.MAX_PASTED_IMAGE_COUNT
import com.m57.hermescontrol.ui.chat.stagePastedImage
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** Cache sub-directory holding files copied out of incoming shares (swept at startup, #1601). */
internal const val SHARE_INBOX_DIRECTORY = "shared_in"

private const val MAX_FAILED_NAMES_SHOWN = 3
private const val FALLBACK_NAME = "file"

/**
 * Incoming intents are untrusted. Only `content://` URIs are readable through a provider
 * grant: `file://` would expose this app's own storage, and our FileProvider (with or without
 * a `userId@` prefix, which the framework strips) would let a caller feed back private files.
 */
internal fun isAcceptableShareUri(
    uri: String,
    ownAuthority: String,
): Boolean {
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd <= 0 || !uri.substring(0, schemeEnd).equals("content", ignoreCase = true)) return false
    val rawAuthority = uri.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
    // The framework matches the percent-decoded authority, so decode before comparing.
    val authority = runCatching { URLDecoder.decode(rawAuthority, "UTF-8") }.getOrNull() ?: return false
    val host = authority.substringAfterLast('@')
    return host.isNotEmpty() && !host.equals(ownAuthority, ignoreCase = true)
}

/** Subject is only useful as a title for shared text (browsers send title + URL). */
internal fun composeSharedText(
    text: String?,
    subject: String?,
): String? {
    val body = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val title = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return body
    return if (body.contains(title)) body else "$title\n$body"
}

/** Provider names reach the gateway as the upload filename, so never let a path through. */
internal fun sanitizeShareName(name: String?): String? =
    name
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

internal fun describeFailedShares(names: List<String>): String {
    val shown = names.take(MAX_FAILED_NAMES_SHOWN).joinToString(", ")
    val extra = names.size - MAX_FAILED_NAMES_SHOWN
    return if (extra > 0) "$shown +$extra" else shown
}

/** What a provider tells us about one shared URI; [open] is invoked off the main thread. */
internal class ShareSource(
    val displayName: String?,
    val mimeType: String?,
    val open: () -> InputStream?,
)

internal data class IncomingShare(
    val text: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Display names of files that could not be attached. */
    val failed: List<String> = emptyList(),
) {
    /** Two shares landing before the chat consumes the first must not drop either. */
    operator fun plus(other: IncomingShare): IncomingShare =
        IncomingShare(
            text = listOfNotNull(text, other.text).joinToString("\n\n").ifEmpty { null },
            attachments = attachments + other.attachments,
            failed = failed + other.failed,
        )
}

/**
 * Copies each accepted URI into private storage within the pasted-image bounds (10 MB per
 * file, 8 files / 20 MB per share) so the grant may expire without losing the attachment.
 * A URI that is rejected, revoked, empty, or over a bound is reported by name, never thrown.
 */
internal fun stageShareUris(
    uris: List<String>,
    ownAuthority: String,
    directory: File,
    source: (String) -> ShareSource,
    publish: (File) -> String,
    maxFiles: Int = MAX_PASTED_IMAGE_COUNT,
    maxTotalBytes: Long = MAX_PASTED_IMAGE_BATCH_BYTES,
): IncomingShare {
    val attachments = mutableListOf<Attachment>()
    val failed = mutableListOf<String>()
    var remainingBytes = maxTotalBytes
    for (uri in uris.distinct()) {
        val label = sanitizeShareName(uri.substringBefore('?')) ?: FALLBACK_NAME
        if (!isAcceptableShareUri(uri, ownAuthority) || attachments.size >= maxFiles) {
            failed += label
            continue
        }
        var name: String? = null
        var staged: File? = null
        try {
            val provided = source(uri)
            name = sanitizeShareName(provided.displayName)
            val input = provided.open() ?: throw IOException("Provider returned no stream")
            val extension =
                name
                    ?.substringAfterLast('.', "")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
                    ?: "bin"
            val file =
                stagePastedImage(
                    input = input,
                    directory = directory,
                    extension = extension,
                    maxBytes = minOf(remainingBytes, MAX_CHAT_ATTACHMENT_BYTES),
                ) ?: throw IOException("Shared file is empty")
            staged = file
            remainingBytes -= file.length()
            attachments +=
                Attachment(
                    uri = publish(file),
                    name = name ?: file.name,
                    mimeType = provided.mimeType ?: "application/octet-stream",
                    size = file.length(),
                )
            staged = null
        } catch (cancelled: CancellationException) {
            staged?.delete()
            throw cancelled
        } catch (_: Exception) {
            // SecurityException (revoked grant), IOException, oversize, empty: all "could not read".
            staged?.delete()
            failed += name ?: label
        }
    }
    return IncomingShare(attachments = attachments, failed = failed)
}
