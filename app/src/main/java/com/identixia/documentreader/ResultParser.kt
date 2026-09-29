package com.identixia.documentreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class FieldRow(val key: String, val value: String, val source: String)

data class SecurityRow(val page: String, val check: String, val status: String)

data class IdentityStats(val type: String, val country: String, val score: String)

data class IdentityLine(
    val title: String,
    val status: String,
    val counts: String,
    val ok: Boolean,
)

data class OverallResult(val kind: String, val result: String)

data class FieldItem(val id: String, val value: String, val score: String)

data class FieldGroup(val source: String, val items: List<FieldItem>)

data class CheckItem(val id: String, val result: String, val extra: String)

data class CheckGroup(val kind: String, val items: List<CheckItem>)

data class ResultImage(val category: String, val source: String, val bitmap: Bitmap)

enum class DocumentSide { FRONT, BACK, UNKNOWN }

/**
 * Maps DocumentReader process JSON into UI fields.
 * Customer body: identity, readings[], tests[], images[], session.
 * Locate overlay still uses score / position / documentName.
 */
object ResultParser {

    private const val LONG_VALUE = 300

    private fun root(raw: String): JSONObject = JSONObject(raw.trim())

    private fun identity(obj: JSONObject): JSONObject =
        obj.optJSONObject("identity") ?: obj.optJSONObject("document") ?: JSONObject()

    private fun session(obj: JSONObject): JSONObject =
        obj.optJSONObject("session") ?: obj.optJSONObject("metadata") ?: JSONObject()

    private fun readings(obj: JSONObject): JSONArray =
        obj.optJSONArray("readings") ?: obj.optJSONArray("fields") ?: JSONArray()

    private fun tests(obj: JSONObject): JSONArray =
        obj.optJSONArray("tests") ?: obj.optJSONArray("checks") ?: JSONArray()

    private fun array(obj: JSONObject, key: String): JSONArray =
        obj.optJSONArray(key) ?: JSONArray()

    private fun pick(obj: JSONObject, vararg keys: String, fallback: String = ""): String {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key) ?: continue
            if (value is String) {
                if (value.isNotBlank()) return value
                continue
            }
            val text = value.toString()
            if (text.isNotBlank() && text != "null") return text
        }
        return fallback
    }

    private fun remapName(value: String): String = when (value) {
        "surname" -> "familyName"
        "givenNames" -> "firstNames"
        "documentNumber" -> "docNumber"
        "hologramIntegrity" -> "foilCheck"
        "portrait" -> "face"
        else -> value
    }

    private fun remapOrigin(value: String): String = when (value.trim().lowercase()) {
        "ocr" -> "visual"
        "mrz" -> "zone"
        "barcode" -> "code"
        "rfid" -> "chip"
        else -> value
    }

    private fun remapGroup(value: String): String = when (value.trim().lowercase()) {
        "verify" -> "validity"
        "quality" -> "capture"
        "security", "liveness" -> "authenticity"
        else -> value
    }

    private fun remapOutcome(value: String): String {
        val s = value.trim().lowercase()
        return if (s == "skip") "hold" else s
    }

    private fun remapDetail(value: String): String = when (value) {
        "ok" -> "ready"
        "processing failed" -> "failed"
        else -> value
    }

    private fun identClass(ident: JSONObject): String = pick(ident, "class", "type")

    private fun fieldName(row: JSONObject): String = remapName(pick(row, "name", "id"))

    private fun originOf(row: JSONObject): String =
        remapOrigin(pick(row, "origin", "source", fallback = "field"))

    private fun groupOf(row: JSONObject): String =
        remapGroup(pick(row, "group", "kind", fallback = "check"))

    private fun outcomeOf(row: JSONObject): String =
        remapOutcome(pick(row, "outcome", "result", fallback = "hold"))

    private fun noteOf(row: JSONObject): String = pick(row, "note", "reason")

    private fun imageName(row: JSONObject): String =
        remapName(pick(row, "name", "id", fallback = "image"))

    private fun imageData(row: JSONObject): String = pick(row, "data", "image")

    private fun statusOf(obj: JSONObject): Int {
        val s = session(obj)
        if (s.has("code") && !s.isNull("code")) return s.optInt("code")
        if (s.has("status") && !s.isNull("status")) return s.optInt("status")
        return obj.optInt("code", obj.optInt("errorCode", 0))
    }

    private fun messageOf(obj: JSONObject): String {
        val fromSession = pick(session(obj), "detail", "message")
        if (fromSession.isNotBlank()) return remapDetail(fromSession)
        val fromRoot = obj.optString("message", "")
        return if (fromRoot.isNotBlank()) remapDetail(fromRoot) else ""
    }

    fun pretty(raw: String): String {
        if (raw.isBlank()) return "(empty response)"
        return try {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("{") ->
                    (sanitizeValue(JSONObject(trimmed)) as JSONObject).toString(2)
                trimmed.startsWith("[") ->
                    (sanitizeValue(JSONArray(trimmed)) as JSONArray).toString(2)
                else -> summarizeLong(trimmed)
            }
        } catch (_: Exception) {
            summarizeLong(raw)
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> {
                val out = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out.put(k, sanitizeValue(value.opt(k)))
                }
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length()) {
                    out.put(sanitizeValue(value.opt(i)))
                }
                out
            }
            is String -> if (value.length > LONG_VALUE) summarizeLong(value) else value
            else -> value
        }
    }

    fun summarizeLong(value: String): String {
        val type = when {
            value.startsWith("/9j/") || value.startsWith("data:image/jpeg") -> "jpeg"
            value.startsWith("iVBOR") || value.startsWith("data:image/png") -> "png"
            value.startsWith("R0lGOD") || value.startsWith("data:image/gif") -> "gif"
            value.startsWith("Qk") && value.length > 100 -> "bmp"
            value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } -> "base64"
            else -> "string"
        }
        return "$type, ${value.length} chars"
    }

    private fun scoreText(value: Any?): String {
        val n = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return ""
        return String.format("%.6f", n)
    }

    fun summary(raw: String): String {
        return try {
            val obj = root(raw)
            if (obj.has("msg")) return obj.optString("msg")
            val ident = identity(obj)
            val code = statusOf(obj)
            val score = ident.opt("score") ?: obj.opt("score")
            buildString {
                append(if (code == 0) "ok" else "failed")
                append(" · status=").append(code)
                val message = messageOf(obj)
                if (message.isNotBlank()) append(" · ").append(message)
                append("\n")
                append(identClass(ident).ifBlank { obj.optString("documentName", "—") }.ifBlank { "—" })
                append(" · ")
                append(ident.optString("country", obj.optString("countryName", "—")))
                append("\nscore: ").append(scoreText(score).ifBlank { "—" })
            }
        } catch (_: Exception) {
            raw.take(200)
        }
    }

    fun identity(raw: String): IdentityStats {
        return try {
            val obj = root(raw)
            val ident = identity(obj)
            val score = scoreText(ident.opt("score")).ifBlank {
                ident.opt("score")?.let { it.toString() } ?: ""
            }
            val short = if (score.contains(".")) score.take(4) else score.ifBlank { "—" }
            IdentityStats(
                identClass(ident).ifBlank { obj.optString("documentName", "—") }.ifBlank { "—" },
                ident.optString("country", obj.optString("countryName", "—")).ifBlank { "—" },
                if (score.isBlank()) "—" else {
                    val n = ident.optDouble("score", Double.NaN)
                    if (!n.isNaN()) String.format("%.2f", n) else short
                },
            )
        } catch (_: Exception) {
            IdentityStats("—", "—", "—")
        }
    }

    /** Same three-line card as the web `ix-ident` block. */
    fun identityLine(raw: String): IdentityLine {
        return try {
            val obj = root(raw)
            val stats = identity(raw)
            val code = statusOf(obj)
            val message = messageOf(obj).trim().ifBlank { "—" }
            val ok = code == 0
            val fields = readings(obj)
            val checks = tests(obj)
            var pass = 0
            var fail = 0
            for (i in 0 until checks.length()) {
                when (outcomeOf(checks.optJSONObject(i) ?: continue)) {
                    "pass" -> pass++
                    "fail" -> fail++
                }
            }
            val skip = (checks.length() - pass - fail).coerceAtLeast(0)
            IdentityLine(
                title = "${stats.type} · ${stats.country} · ${stats.score}",
                status = "${if (ok) "ok" else "failed"} · status=$code · $message",
                counts = "${fields.length()} fields · ${checks.length()} checks · $pass pass · $fail fail · $skip skip",
                ok = ok,
            )
        } catch (_: Exception) {
            IdentityLine("— · — · —", "failed · status=— · —", "0 fields · 0 checks · 0 pass · 0 fail · 0 skip", false)
        }
    }

    private val overallKinds = listOf("validity", "capture", "authenticity")
    private val fieldSourceOrder = listOf("visual", "zone", "code")
    private val checkResultRank = mapOf("fail" to 0, "pass" to 1, "hold" to 2, "skip" to 2)

    fun sourceLabel(source: String): String {
        return when (source.trim().lowercase()) {
            "visual" -> "VISUAL"
            "zone" -> "ZONE"
            "code" -> "CODE"
            "chip" -> "CHIP"
            else -> source.trim().uppercase().ifEmpty { "FIELD" }
        }
    }

    fun kindLabel(kind: String): String {
        return when (kind.trim().lowercase()) {
            "validity", "verify" -> "Validity"
            "capture", "quality" -> "Capture"
            "security", "authenticity", "liveness" -> "Liveness"
            else -> kind.trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Check" }
        }
    }

    /** Kind-level roll-up: any fail → fail, else any pass → pass, else skip. */
    fun overallResults(raw: String): List<OverallResult> {
        val checks = try {
            tests(root(raw))
        } catch (_: Exception) {
            JSONArray()
        }
        return overallKinds.map { kind -> OverallResult(kind, overallResult(checks, kind)) }
    }

    private fun overallResult(checks: JSONArray, kind: String): String {
        var anyPass = false
        for (i in 0 until checks.length()) {
            val row = checks.optJSONObject(i) ?: continue
            if (groupOf(row) != kind) continue
            when (outcomeOf(row)) {
                "fail" -> return "fail"
                "pass" -> anyPass = true
            }
        }
        return if (anyPass) "pass" else "skip"
    }

    fun fieldGroups(raw: String): List<FieldGroup> {
        return try {
            val fields = readings(root(raw))
            val buckets = linkedMapOf<String, MutableList<FieldItem>>()
            val extra = mutableListOf<String>()
            for (i in 0 until fields.length()) {
                val row = fields.optJSONObject(i) ?: continue
                val value = row.optString("value")
                if (value.isBlank() || value == "null") continue
                val source = originOf(row)
                if (source !in buckets) {
                    buckets[source] = mutableListOf()
                    if (source !in fieldSourceOrder) extra += source
                }
                buckets[source]!! += FieldItem(
                    fieldName(row),
                    value,
                    scoreText(row.opt("score")),
                )
            }
            (fieldSourceOrder + extra).mapNotNull { source ->
                val items = buckets[source].orEmpty()
                if (items.isEmpty()) null else FieldGroup(source, items)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun checkGroups(raw: String): List<CheckGroup> {
        return try {
            val checks = tests(root(raw))
            val buckets = linkedMapOf<String, MutableList<CheckItem>>()
            overallKinds.forEach { buckets[it] = mutableListOf() }
            val extra = mutableListOf<String>()
            for (i in 0 until checks.length()) {
                val row = checks.optJSONObject(i) ?: continue
                val kind = groupOf(row)
                val extraBits = mutableListOf<String>()
                val reason = noteOf(row)
                if (reason.isNotBlank()) extraBits += reason
                val score = scoreText(row.opt("score"))
                if (score.isNotBlank()) extraBits += score
                if (kind !in buckets) {
                    buckets[kind] = mutableListOf()
                    extra += kind
                }
                buckets[kind]!! += CheckItem(
                    fieldName(row),
                    outcomeOf(row),
                    extraBits.joinToString(" · "),
                )
            }
            (overallKinds + extra).mapNotNull { kind ->
                val items = buckets[kind].orEmpty()
                if (items.isEmpty()) null
                else CheckGroup(kind, items.sortedBy { checkResultRank[it.result] ?: 9 })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fieldRows(raw: String): List<FieldRow> {
        return try {
            val fields = readings(root(raw))
            val out = mutableListOf<FieldRow>()
            for (i in 0 until fields.length()) {
                val row = fields.optJSONObject(i) ?: continue
                val value = row.optString("value")
                val extra = scoreText(row.opt("score"))
                out += FieldRow(
                    fieldName(row),
                    if (extra.isBlank()) value else "$value · $extra",
                    originOf(row),
                )
            }
            out.filter { it.value.isNotBlank() && it.value != "null" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun checkRows(raw: String): List<SecurityRow> {
        return try {
            val checks = tests(root(raw))
            val out = mutableListOf<SecurityRow>()
            for (i in 0 until checks.length()) {
                val row = checks.optJSONObject(i) ?: continue
                var label = outcomeOf(row)
                val extra = scoreText(row.opt("score"))
                if (extra.isNotBlank()) label = "$label · $extra"
                val reason = noteOf(row)
                if (reason.isNotBlank()) label = "$label — $reason"
                out += SecurityRow(
                    groupOf(row),
                    fieldName(row),
                    label,
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun rows(raw: String): List<FieldRow> {
        return try {
            val obj = root(raw)
            val ident = identity(obj)
            val out = mutableListOf<FieldRow>()
            identClass(ident).takeIf { it.isNotBlank() }?.let {
                out += FieldRow("class", it, "identity")
            }
            ident.optString("country").takeIf { it.isNotBlank() }?.let {
                out += FieldRow("country", it, "identity")
            }
            scoreText(ident.opt("score")).takeIf { it.isNotBlank() }?.let {
                out += FieldRow("score", it, "identity")
            }
            val fields = readings(obj)
            for (i in 0 until fields.length()) {
                val row = fields.optJSONObject(i) ?: continue
                val value = row.optString("value")
                val extra = scoreText(row.opt("score"))
                out += FieldRow(
                    fieldName(row),
                    if (extra.isBlank()) value else "$value · $extra",
                    originOf(row),
                )
            }
            val checks = tests(obj)
            for (i in 0 until checks.length()) {
                val row = checks.optJSONObject(i) ?: continue
                if (groupOf(row) == "authenticity") continue
                var label = outcomeOf(row)
                val extra = scoreText(row.opt("score"))
                if (extra.isNotBlank()) label = "$label · $extra"
                val reason = noteOf(row)
                if (reason.isNotBlank()) label = "$label — $reason"
                out += FieldRow(fieldName(row), label, groupOf(row).ifBlank { "check" })
            }
            out.filter { it.value.isNotBlank() && it.value != "null" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun extractName(obj: JSONObject): String {
        val ident = identity(obj)
        identClass(ident).takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("documentName").takeIf { it.isNotBlank() }?.let { return it }
        val fields = readings(obj)
        var surname = ""
        var given = ""
        for (i in 0 until fields.length()) {
            val row = fields.optJSONObject(i) ?: continue
            when (fieldName(row)) {
                "familyName", "surname" -> surname = row.optString("value")
                "firstNames", "givenNames", "givenName" -> given = row.optString("value")
                "surnameAndGivenNames", "name", "fullName" ->
                    if (row.optString("value").isNotBlank()) return row.optString("value")
            }
        }
        return listOf(surname, given).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun decodePortrait(obj: JSONObject): Bitmap? {
        return images(obj.toString())
            .firstOrNull {
                it.category.contains("face", ignoreCase = true) ||
                    it.category.contains("portrait", ignoreCase = true)
            }
            ?.bitmap
            ?: images(obj.toString()).firstOrNull()?.bitmap
    }

    fun images(raw: String): List<ResultImage> {
        return try {
            val arr = array(root(raw), "images")
            val out = mutableListOf<ResultImage>()
            val seen = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val b64 = imageData(item)
                if (b64.length < 32) continue
                val key = "${b64.length}:${b64.take(48)}"
                if (!seen.add(key)) continue
                val bmp = decodeBase64Bitmap(b64) ?: continue
                val id = imageName(item)
                val page = if (item.has("page")) " (page ${item.optInt("page")})" else ""
                out += ResultImage(id + page, "", bmp)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun decodeBase64Bitmap(b64: String): Bitmap? {
        return try {
            val clean = b64.substringAfter("base64,", b64)
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun documentPercent(raw: String): Int {
        return try {
            val obj = root(raw)
            val s = identity(obj).optDouble("score", obj.optDouble("score", 0.0))
            (if (s <= 1.0) s * 100.0 else s).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    fun documentSide(raw: String): DocumentSide {
        return try {
            val obj = root(raw)
            val name = identClass(identity(obj))
                .ifBlank { obj.optString("documentName", "") }
                .lowercase()
            if (name.isEmpty()) return DocumentSide.UNKNOWN
            when {
                name.contains("back") || name.contains("rear") || name.contains("verso")
                    || name.contains("reverse") -> DocumentSide.BACK
                name.contains("front") || name.contains("recto") || name.contains("obverse") ->
                    DocumentSide.FRONT
                else -> DocumentSide.UNKNOWN
            }
        } catch (_: Exception) {
            DocumentSide.UNKNOWN
        }
    }

    fun documentFillPercent(raw: String): Int {
        return try {
            val pos = root(raw).optJSONObject("position") ?: return 0
            val area = when {
                pos.has("objArea") && !pos.isNull("objArea") -> pos.optDouble("objArea")
                pos.has("ObjArea") && !pos.isNull("ObjArea") -> pos.optDouble("ObjArea")
                else -> return 0
            }
            if (area.isNaN() || area < 0.0) return 0
            (if (area <= 1.0) area * 100.0 else area).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    fun documentCorners(raw: String): List<Pair<Float, Float>>? {
        return try {
            val pos = root(raw).optJSONObject("position") ?: return null
            val arr = pos.optJSONArray("corners") ?: return null
            if (arr.length() < 4) return null
            val out = ArrayList<Pair<Float, Float>>(4)
            for (i in 0 until 4) {
                val p = arr.optJSONObject(i) ?: return null
                out += p.optDouble("x").toFloat() to p.optDouble("y").toFloat()
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun pageSide(page: Int): String = when (page) {
        0 -> "Front"
        1 -> "Back"
        else -> "Page $page"
    }

    fun securitySummary(raw: String): String {
        return try {
            val obj = root(raw)
            val checks = tests(obj)
            var pass = 0
            var fail = 0
            var skip = 0
            var any = false
            for (i in 0 until checks.length()) {
                val row = checks.optJSONObject(i) ?: continue
                if (groupOf(row) != "authenticity") continue
                any = true
                when (outcomeOf(row)) {
                    "pass" -> pass++
                    "fail" -> fail++
                    else -> skip++
                }
            }
            if (!any) {
                return "No liveness checks in this response. If you expected checks, this license may not include liveness."
            }
            val title = identClass(identity(obj)).ifBlank { "Document" }
            "$title\n$pass pass · $fail fail · $skip skip"
        } catch (_: Exception) {
            "No liveness checks in this response."
        }
    }

    fun securityRows(raw: String): List<SecurityRow> {
        return try {
            val checks = tests(root(raw))
            val out = mutableListOf<SecurityRow>()
            for (i in 0 until checks.length()) {
                val row = checks.optJSONObject(i) ?: continue
                if (groupOf(row) != "authenticity") continue
                var label = outcomeOf(row)
                val extra = scoreText(row.opt("score"))
                if (extra.isNotBlank()) label = "$label · $extra"
                out += SecurityRow(pageSide(row.optInt("page", 0)), fieldName(row), label)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
