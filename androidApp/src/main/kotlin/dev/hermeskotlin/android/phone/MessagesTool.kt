package dev.hermeskotlin.android.phone

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets

/** Reads live SMS/MMS provider rows, or hands a draft to the user's messaging app. Never sends a message. */
internal class MessagesTool(private val context: Context) {

    fun run(args: JsonObject): JsonObject = when (args.string("action")) {
        "search" -> search(args)
        "sync" -> sync(args)
        "draft" -> draft(args)
        else -> throw PhoneToolException("action is search, sync or draft.")
    }

    private fun search(args: JsonObject): JsonObject {
        needReadPermission()
        val query = args.string("query")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw PhoneToolException("query is needed.")
        if (query.length > MAX_QUERY_CHARS) throw PhoneToolException("query is at most $MAX_QUERY_CHARS characters.")
        val limit = args.int("limit") ?: DEFAULT_SEARCH_LIMIT
        if (limit !in 1..MAX_SEARCH_LIMIT) throw PhoneToolException("limit is 1 to $MAX_SEARCH_LIMIT.")
        return providerRead {
            val like = likePattern(query)
            val fetch = limit + 1
            val sms = readSms(
                "(body LIKE ? ESCAPE '\\' OR address LIKE ? ESCAPE '\\')",
                arrayOf(like, like),
                fetch,
            )
            val mms = readMmsSearch(query, fetch)
            val combined = (sms + mms.messages).sortedWith(MESSAGE_ORDER)
            response(combined, combined.size > limit || mms.hasMore, limit)
        }
    }

    private fun sync(args: JsonObject): JsonObject {
        needReadPermission()
        val sinceMs = args.long("since_ms")
        val beforeMs = args.long("before_ms")
        val limit = args.int("limit") ?: MAX_SYNC_LIMIT
        if (limit !in 1..MAX_SYNC_LIMIT) throw PhoneToolException("limit is 1 to $MAX_SYNC_LIMIT.")
        val offset = args.int("offset") ?: 0
        if (offset !in 0..MAX_SYNC_OFFSET) throw PhoneToolException("offset is 0 to $MAX_SYNC_OFFSET.")
        val smsDates = dateFilter(sinceMs, beforeMs, mms = false)
        val mmsDates = dateFilter(sinceMs, beforeMs, mms = true)
        val headers = providerRead {
            val smsCursor = queryWithContentClass(
                Telephony.Sms.CONTENT_URI,
                SMS_SYNC_COLUMNS,
                smsDates.selection,
                smsDates.arguments,
                "date DESC, _id DESC",
            )
            var mmsCursor: Cursor? = null
            try {
                mmsCursor = queryWithContentClass(
                    Telephony.Mms.CONTENT_URI,
                    MMS_SYNC_COLUMNS,
                    mmsDates.selection,
                    mmsDates.arguments,
                    "date DESC, _id DESC",
                )
                val sms = HeaderCursor(smsCursor, ::smsHeader)
                val mms = HeaderCursor(mmsCursor, ::mmsHeader)
                val page = ArrayList<MessageHeader>(limit + 1)
                var skipped = 0
                while (page.size <= limit && (sms.current != null || mms.current != null)) {
                    val takeSms = when {
                        sms.current == null -> false
                        mms.current == null -> true
                        else -> MESSAGE_HEADER_ORDER.compare(sms.current!!, mms.current!!) <= 0
                    }
                    val next = if (takeSms) sms.current!! else mms.current!!
                    if (skipped < offset) {
                        skipped++
                    } else {
                        page += next
                    }
                    if (takeSms) sms.advance() else mms.advance()
                }
                page
            } finally {
                smsCursor?.close()
                mmsCursor?.close()
            }
        }
        val page = headers.take(limit)
        val smsBodies = providerRead { readSmsBodies(page.filter { it.provider == "sms" }.map(MessageHeader::rawId)) }
        val messages = page.map { header ->
            val rawBody = if (header.provider == "sms") smsBodies[header.rawId].orEmpty() else mmsBody(header.rawId)
            val (body, bodyTruncated) = cappedBody(rawBody)
            val rawAddress = if (header.provider == "sms") header.address else mmsAddress(header.rawId, header.direction).orEmpty()
            val (address, addressTruncated) = cappedText(rawAddress, MAX_ADDRESS_CHARS)
            Message(
                id = header.id,
                provider = header.provider,
                rawId = header.rawId,
                source = header.source,
                threadId = header.threadId,
                address = address,
                addressTruncated = addressTruncated,
                body = body,
                bodyTruncated = bodyTruncated,
                dateMs = header.dateMs,
                direction = header.direction,
            )
        }
        return response(messages, headers.size > limit, limit)
    }

    private fun draft(args: JsonObject): JsonObject {
        val recipient = args.string("recipient")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw PhoneToolException("recipient is needed.")
        if (recipient.length > MAX_RECIPIENT_CHARS) {
            throw PhoneToolException("recipient is at most $MAX_RECIPIENT_CHARS characters.")
        }
        val text = args.string("text")?.takeIf(String::isNotBlank)
            ?: throw PhoneToolException("text is needed.")
        if (text.codePointCount(0, text.length) > MAX_BODY_CHARS) {
            throw PhoneToolException("text is at most $MAX_BODY_CHARS characters.")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient, null))
            .putExtra("sms_body", text)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw PhoneToolException("No messaging app could open the draft.")
        } catch (_: SecurityException) {
            throw PhoneToolException("Android couldn't open the draft. Open Herald and try again.")
        } catch (_: RuntimeException) {
            throw PhoneToolException("Android couldn't open the draft. Open Herald and try again.")
        }
        return buildJsonObject {
            put("draft_opened", true)
            put("handoff", "Review the draft in your messaging app and tap Send there; Herald does not send it.")
        }
    }

    private data class Message(
        val id: String,
        val provider: String,
        val rawId: Long,
        val source: String,
        val threadId: Long?,
        val address: String,
        val addressTruncated: Boolean,
        val body: String,
        val bodyTruncated: Boolean,
        val dateMs: Long,
        val direction: String,
    )

    private data class MessageHeader(
        val id: String,
        val provider: String,
        val rawId: Long,
        val source: String,
        val threadId: Long?,
        val address: String,
        val dateMs: Long,
        val direction: String,
    )

    private data class DateFilter(val selection: String?, val arguments: Array<String>?)
    private data class MmsSearchResult(val messages: List<Message>, val hasMore: Boolean)

    private class HeaderCursor(
        private val cursor: Cursor?,
        private val readHeader: (Cursor) -> MessageHeader,
    ) {
        var current: MessageHeader? = null
            private set

        init {
            advance()
        }

        fun advance() {
            current = if (cursor?.moveToNext() == true) readHeader(cursor) else null
        }
    }

    private fun readSms(selection: String?, arguments: Array<String>?, fetch: Int): List<Message> =
        queryWithContentClass(
            Telephony.Sms.CONTENT_URI,
            SMS_SEARCH_COLUMNS,
            selection,
            arguments,
            "date DESC, _id DESC LIMIT $fetch",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(smsMessage(cursor))
            }
        }.orEmpty()

    private fun readMms(selection: String?, arguments: Array<String>?, fetch: Int): List<Message> =
        queryWithContentClass(
            Telephony.Mms.CONTENT_URI,
            MMS_SEARCH_COLUMNS,
            selection,
            arguments,
            "date DESC, _id DESC LIMIT $fetch",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(mmsMessage(cursor))
            }
        }.orEmpty()

    /** Search MMS parts/addresses in the provider first; on OEM providers that reject the subquery, scan a fixed cap. */
    private fun readMmsSearch(query: String, fetch: Int): MmsSearchResult {
        val selection = """("_id" IN (SELECT mid FROM part WHERE ct = ? AND INSTR(LOWER(text), LOWER(?)) > 0)
            OR "_id" IN (SELECT msg_id FROM addr WHERE INSTR(LOWER(address), LOWER(?)) > 0))"""
        val arguments = arrayOf("text/plain", query, query)
        return try {
            val rows = readMms(selection, arguments, fetch)
            MmsSearchResult(rows, rows.size >= fetch)
        } catch (_: RuntimeException) {
            val candidates = readMms(null, null, MAX_MMS_SEARCH_SCAN + 1)
            val scanned = candidates.take(MAX_MMS_SEARCH_SCAN)
            val matches = scanned.filter {
                it.body.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true)
            }
            MmsSearchResult(matches, candidates.size > MAX_MMS_SEARCH_SCAN)
        }
    }

    private fun smsMessage(cursor: Cursor): Message {
        val rawBody = cursor.getString(4).orEmpty()
        val (body, bodyTruncated) = cappedBody(rawBody)
        val (address, addressTruncated) = cappedText(cursor.getString(2).orEmpty(), MAX_ADDRESS_CHARS)
        return Message(
            id = "sms:${cursor.getLong(0)}",
            provider = "sms",
            rawId = cursor.getLong(0),
            source = source("sms", contentClass(cursor)),
            threadId = cursor.nullableLong(1),
            address = address,
            addressTruncated = addressTruncated,
            body = body,
            bodyTruncated = bodyTruncated,
            dateMs = cursor.getLong(3),
            direction = direction(cursor.getInt(5)),
        )
    }

    private fun mmsMessage(cursor: Cursor): Message {
        val id = cursor.getLong(0)
        val direction = direction(cursor.getInt(3))
        val (body, bodyTruncated) = cappedBody(mmsBody(id))
        val (address, addressTruncated) = cappedText(mmsAddress(id, direction).orEmpty(), MAX_ADDRESS_CHARS)
        val date = cursor.getLong(2)
        return Message(
            id = "mms:$id",
            provider = "mms",
            rawId = id,
            source = source("mms", contentClass(cursor)),
            threadId = cursor.nullableLong(1),
            address = address,
            addressTruncated = addressTruncated,
            body = body,
            bodyTruncated = bodyTruncated,
            // Telephony.Mms.DATE is seconds; some OEM providers return milliseconds instead.
            dateMs = if (date < MILLIS_THRESHOLD) date * 1_000 else date,
            direction = direction,
        )
    }

    private fun smsHeader(cursor: Cursor): MessageHeader {
        val id = cursor.getLong(0)
        return MessageHeader(
            id = "sms:$id",
            provider = "sms",
            rawId = id,
            source = source("sms", contentClass(cursor)),
            threadId = cursor.nullableLong(1),
            address = cursor.getString(2).orEmpty(),
            dateMs = cursor.getLong(3),
            direction = direction(cursor.getInt(4)),
        )
    }

    private fun mmsHeader(cursor: Cursor): MessageHeader {
        val id = cursor.getLong(0)
        val date = cursor.getLong(2)
        return MessageHeader(
            id = "mms:$id",
            provider = "mms",
            rawId = id,
            source = source("mms", contentClass(cursor)),
            threadId = cursor.nullableLong(1),
            address = "",
            dateMs = if (date < MILLIS_THRESHOLD) date * 1_000 else date,
            direction = direction(cursor.getInt(3)),
        )
    }

    private fun readSmsBodies(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val args = ids.map(Long::toString).toTypedArray()
        val selection = "_id IN (${ids.joinToString(",") { "?" }})"
        return queryWithContentClass(Telephony.Sms.CONTENT_URI, SMS_BODY_COLUMNS, selection, args, null)
            ?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1).orEmpty())
                }
            }.orEmpty()
    }

    private fun mmsBody(id: Long): String = runCatching {
        val uri = childUri(id, "part")
        context.contentResolver.query(
            uri,
            MMS_PART_COLUMNS,
            "ct = ?",
            arrayOf("text/plain"),
            "_id ASC LIMIT $MAX_MMS_PARTS",
        )?.use { cursor ->
            buildString {
                while (cursor.moveToNext() && codePointCount(0, length) < MAX_BODY_CHARS + 1) {
                    val part = cursor.getString(1)?.takeIf(String::isNotBlank) ?: continue
                    if (isNotEmpty()) append('\n')
                    val room = MAX_BODY_CHARS + 1 - codePointCount(0, length)
                    append(takeCodePoints(part, room))
                }
            }
        }.orEmpty()
    }.getOrDefault("")

    private fun mmsAddress(id: Long, direction: String): String? = runCatching {
        val uri = childUri(id, "addr")
        context.contentResolver.query(
            uri,
            MMS_ADDRESS_COLUMNS,
            null,
            null,
            "_id ASC LIMIT $MAX_MMS_ADDRESSES",
        )?.use { cursor ->
            var fallback: String? = null
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                if (fallback == null) fallback = address
                val type = cursor.getInt(1)
                if ((direction == "inbound" && type == MMS_FROM) || (direction == "outbound" && type == MMS_TO)) {
                    return@use address
                }
            }
            fallback
        }
    }.getOrNull()

    /** The ct_cls column is not consistent across Android providers, so retry without it if projection fails. */
    private fun queryWithContentClass(
        uri: Uri,
        columns: Array<String>,
        selection: String?,
        arguments: Array<String>?,
        sortOrder: String?,
    ): Cursor? = try {
        context.contentResolver.query(uri, columns + CONTENT_CLASS, selection, arguments, sortOrder)
    } catch (_: RuntimeException) {
        context.contentResolver.query(uri, columns, selection, arguments, sortOrder)
    }

    private fun contentClass(cursor: Cursor): String? =
        cursor.getColumnIndex(CONTENT_CLASS).takeIf { it >= 0 }?.let(cursor::getString)

    private fun source(fallback: String, contentClass: String?): String =
        if (contentClass?.let { it in RCS_CONTENT_CLASSES } == true) "rcs" else fallback

    private fun direction(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbound"
        Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbound"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        else -> "unknown"
    }

    private fun dateFilter(sinceMs: Long?, beforeMs: Long?, mms: Boolean): DateFilter {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<String>()
        if (sinceMs != null) {
            conditions += "date >= ?"
            values += (if (mms) ceilMillisToSeconds(sinceMs) else sinceMs).toString()
        }
        if (beforeMs != null) {
            conditions += "date < ?"
            values += (if (mms) ceilMillisToSeconds(beforeMs) else beforeMs).toString()
        }
        return DateFilter(
            selection = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            arguments = values.toTypedArray().takeIf { it.isNotEmpty() },
        )
    }

    private fun response(messages: List<Message>, hasMore: Boolean, limit: Int): JsonObject {
        var selected = messages.take(limit)
        val more = hasMore || messages.size > limit
        var result = responseObject(selected, more)
        while (
            result.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE_BYTES &&
            selected.any { it.body.isNotEmpty() || it.address.isNotEmpty() }
        ) {
            val size = result.toString().toByteArray(StandardCharsets.UTF_8).size
            val ratio = MAX_RESPONSE_BYTES.toDouble() / size * 0.9
            selected = selected.map { message ->
                val bodyCount = message.body.codePointCount(0, message.body.length)
                val addressCount = message.address.codePointCount(0, message.address.length)
                val body = takeCodePoints(message.body, (bodyCount * ratio).toInt().coerceAtMost(bodyCount - 1).coerceAtLeast(0))
                val address = takeCodePoints(message.address, (addressCount * ratio).toInt().coerceAtMost(addressCount - 1).coerceAtLeast(0))
                message.copy(
                    body = body,
                    bodyTruncated = message.bodyTruncated || body != message.body,
                    address = address,
                    addressTruncated = message.addressTruncated || address != message.address,
                )
            }
            result = responseObject(selected, more)
        }
        return result
    }

    private fun responseObject(messages: List<Message>, hasMore: Boolean): JsonObject = buildJsonObject {
        putJsonArray("messages") {
            messages.forEach { message ->
                addJsonObject {
                    put("id", message.id)
                    put("source", message.source)
                    if (message.threadId == null) put("thread_id", JsonNull) else put("thread_id", message.threadId)
                    put("address", message.address)
                    put("body", message.body)
                    put("date_ms", message.dateMs)
                    put("direction", message.direction)
                    if (message.addressTruncated) put("address_truncated", true)
                    if (message.bodyTruncated) put("body_truncated", true)
                }
            }
        }
        put("has_more", hasMore)
    }

    private fun needReadPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw PhoneToolException("Herald isn't allowed to read messages. The user can allow it in Herald: Settings → Phone tools.")
        }
    }

    private fun childUri(messageId: Long, child: String): Uri =
        Uri.withAppendedPath(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId), child)

    private inline fun <T> providerRead(block: () -> T): T = try {
        block()
    } catch (_: SecurityException) {
        throw PhoneToolException("Android didn't allow Herald to read the messages.")
    } catch (_: RuntimeException) {
        throw PhoneToolException("Android couldn't read the message database.")
    }

    private fun JsonObject.long(name: String): Long? {
        val value = get(name) ?: return null
        if (value == JsonNull) return null
        if (value !is JsonPrimitive || value.isString) throw PhoneToolException("$name must be a whole number.")
        return value.content.toLongOrNull() ?: throw PhoneToolException("$name must be a whole number.")
    }

    private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun likePattern(value: String): String = "%" + value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"

    private fun cappedBody(value: String): Pair<String, Boolean> = cappedText(value, MAX_BODY_CHARS)

    private fun cappedText(value: String, maxCodePoints: Int): Pair<String, Boolean> {
        val length = value.codePointCount(0, value.length)
        if (length <= maxCodePoints) return value to false
        return takeCodePoints(value, maxCodePoints) to true
    }

    private fun takeCodePoints(value: String, maxCodePoints: Int): String =
        value.substring(0, value.offsetByCodePoints(0, maxCodePoints.coerceAtMost(value.codePointCount(0, value.length))))

    private fun ceilMillisToSeconds(value: Long): Long =
        Math.floorDiv(value, 1_000L) + if (Math.floorMod(value, 1_000L) == 0L) 0 else 1

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 20
        const val MAX_SEARCH_LIMIT = 50
        const val MAX_SYNC_LIMIT = 40
        const val MAX_SYNC_OFFSET = 20_000
        const val MAX_QUERY_CHARS = 100
        const val MAX_BODY_CHARS = 1_000
        const val MAX_ADDRESS_CHARS = 200
        const val MAX_RECIPIENT_CHARS = 200
        const val MAX_MMS_SEARCH_SCAN = 200
        const val MAX_MMS_PARTS = 20
        const val MAX_MMS_ADDRESSES = 20
        const val MAX_RESPONSE_BYTES = 48_000
        const val MILLIS_THRESHOLD = 1_000_000_000_000L
        const val CONTENT_CLASS = "ct_cls"
        const val MMS_FROM = 137
        const val MMS_TO = 151
        val RCS_CONTENT_CLASSES = setOf("130", "132", "135")
        val SMS_SEARCH_COLUMNS = arrayOf("_id", "thread_id", "address", "body", "date", "type")
        val MMS_SEARCH_COLUMNS = arrayOf("_id", "thread_id", "date", "msg_box")
        val SMS_SYNC_COLUMNS = arrayOf("_id", "thread_id", "address", "date", "type")
        val MMS_SYNC_COLUMNS = arrayOf("_id", "thread_id", "date", "msg_box")
        val SMS_BODY_COLUMNS = arrayOf("_id", "body")
        val MMS_PART_COLUMNS = arrayOf("_id", "text")
        val MMS_ADDRESS_COLUMNS = arrayOf("address", "type")
        val MESSAGE_HEADER_ORDER = compareByDescending<MessageHeader> { it.dateMs }
            .thenByDescending { it.rawId }
            .thenBy { it.provider }
        val MESSAGE_ORDER = compareByDescending<Message> { it.dateMs }
            .thenByDescending { it.rawId }
            .thenBy { it.provider }
    }
}
