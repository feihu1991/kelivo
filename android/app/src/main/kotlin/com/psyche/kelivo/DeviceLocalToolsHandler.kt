package com.psyche.kelivo

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * Native backend for the AI assistant's device-local tools:
 * screen time (usage stats), calendar query and calendar event creation.
 *
 * All methods receive the tool arguments as a JSON string and return a JSON
 * string payload. Errors that the LLM should see (missing permission, bad
 * arguments) are returned as JSON payloads with an "error" field instead of
 * platform errors, so the model can relay them to the user.
 */
class DeviceLocalToolsHandler(private val activity: Activity) {
    companion object {
        const val CHANNEL_NAME = "app.device_tools"
        const val CALENDAR_PERMISSION_REQUEST_CODE = 4201
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCalendarPermissionCallback: ((Boolean) -> Unit)? = null

    fun configure(messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, CHANNEL_NAME)
        channel.setMethodCallHandler { call, result ->
            val argsJson = call.arguments as? String ?: "{}"
            when (call.method) {
                "hasUsageStatsPermission" -> result.success(hasUsageStatsPermission())
                "openUsageAccessSettings" -> {
                    openUsageAccessSettings()
                    result.success(null)
                }
                "hasCalendarPermission" -> result.success(hasCalendarPermission())
                "requestCalendarPermission" -> requestCalendarPermission(result)
                "getScreenTime" -> handleScreenTime(argsJson, result)
                "queryCalendar" -> withCalendarPermission(
                    arrayOf(Manifest.permission.READ_CALENDAR),
                    result,
                ) { runAsync(result) { queryCalendar(JSONObject(argsJson)) } }
                "createCalendarEvent" -> withCalendarPermission(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    result,
                ) { runAsync(result) { createCalendarEvent(JSONObject(argsJson)) } }
                // Phase 1: 文件读写 + API 调用
                "readFile" -> runAsync(result) { readFile(JSONObject(argsJson)) }
                "writeFile" -> runAsync(result) { writeFile(JSONObject(argsJson)) }
                "listFiles" -> runAsync(result) { listFiles(JSONObject(argsJson)) }
                "apiCall" -> runAsync(result) { apiCall(JSONObject(argsJson)) }
                else -> result.notImplemented()
            }
        }
    }

    /** Forwarded from the Activity. Returns true when the request was ours. */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != CALENDAR_PERMISSION_REQUEST_CODE) return false
        val callback = pendingCalendarPermissionCallback ?: return true
        pendingCalendarPermissionCallback = null
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        callback(granted)
        return true
    }

    // ---------------------------------------------------------------------
    // Permission helpers
    // ---------------------------------------------------------------------

    private fun calendarPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private fun hasCalendarPermission(): Boolean {
        return calendarPermissions().all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Used by the assistant settings toggle — returns a boolean grant result. */
    private fun requestCalendarPermission(result: MethodChannel.Result) {
        val missing = calendarPermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            result.success(true)
            return
        }
        if (pendingCalendarPermissionCallback != null) {
            result.success(false)
            return
        }
        pendingCalendarPermissionCallback = { granted -> result.success(granted) }
        ActivityCompat.requestPermissions(
            activity,
            missing.toTypedArray(),
            CALENDAR_PERMISSION_REQUEST_CODE,
        )
    }

    private fun withCalendarPermission(
        permissions: Array<String>,
        result: MethodChannel.Result,
        action: () -> Unit,
    ) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        if (pendingCalendarPermissionCallback != null) {
            result.success(
                errorPayload(
                    "PERMISSION_REQUEST_IN_PROGRESS",
                    "Another permission request is already in progress. Please try again.",
                ),
            )
            return
        }
        pendingCalendarPermissionCallback = { granted ->
            if (granted) {
                action()
            } else {
                result.success(
                    errorPayload(
                        "NO_PERMISSION",
                        "Calendar permission is not granted. Please ask the user to grant the " +
                            "calendar permission to this app and try again.",
                    ),
                )
            }
        }
        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), CALENDAR_PERMISSION_REQUEST_CODE)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                activity.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                activity.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                // Settings page unavailable; the error payload still informs the model.
            }
        }
    }

    // ---------------------------------------------------------------------
    // Async plumbing
    // ---------------------------------------------------------------------

    private fun runAsync(result: MethodChannel.Result, block: () -> String) {
        executor.execute {
            val payload = try {
                block()
            } catch (e: Exception) {
                errorPayload("EXECUTION_ERROR", e.message ?: "Tool execution failed.")
            }
            mainHandler.post { result.success(payload) }
        }
    }

    private fun errorPayload(error: String, message: String): String {
        return JSONObject().put("error", error).put("message", message).toString()
    }

    // ---------------------------------------------------------------------
    // Screen time
    // ---------------------------------------------------------------------

    private fun handleScreenTime(argsJson: String, result: MethodChannel.Result) {
        if (!hasUsageStatsPermission()) {
            openUsageAccessSettings()
            result.success(
                errorPayload(
                    "NO_PERMISSION",
                    "Usage access permission is not granted. The system settings page has been " +
                        "opened; please ask the user to enable 'Usage access' for this app and try again.",
                ),
            )
            return
        }
        runAsync(result) { computeScreenTime(JSONObject(argsJson)) }
    }

    private fun computeScreenTime(params: JSONObject): String {
        val top = params.optString("top").toIntOrNull()?.coerceIn(1, 50)
            ?: params.optInt("top", 10).coerceIn(1, 50)

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params.optString("begin").takeIf { it.isNotBlank() }
        val endRaw = params.optString("end").takeIf { it.isNotBlank() }
        val rangePreset = params.optString("range").takeIf { it.isNotBlank() } ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { parseTime(it, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.minusDays(7)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format for begin/end.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "begin must be earlier than end.")
        }

        val isCustom = beginRaw != null || endRaw != null
        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val usageStatsManager =
            activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = activity.packageManager

        val launcherPackages = resolveLauncherPackages(pm)
        val foregroundMs = computeForegroundTime(usageStatsManager, startMs, endMs, launcherPackages)

        val sorted = foregroundMs.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
        val totalMs = sorted.sumOf { it.value }

        val apps = JSONArray()
        sorted.take(top).forEach { entry ->
            apps.put(
                JSONObject()
                    .put("package", entry.key)
                    .put("app_name", resolveAppName(pm, entry.key))
                    .put("total_ms", entry.value)
                    .put("total_minutes", entry.value / 60000),
            )
        }

        return JSONObject()
            .put("range", if (isCustom) "custom" else rangePreset)
            .put("start", startTime.withNano(0).toString())
            .put("end", endTime.withNano(0).toString())
            .put("total_ms", totalMs)
            .put("total_minutes", totalMs / 60000)
            .put("apps", apps)
            .toString()
    }

    // 计算屏幕时间时向前回看的窗口(12h), 用于还原区间开始时刻已在前台的 App.
    private val lookbackMs = 12L * 60 * 60 * 1000

    /**
     * 用"全局单一前台"模型计算 [startMs, endMs) 区间内每个 App 的前台时长(毫秒).
     * 任意时刻只有一个 App 计时: 新 App 进前台先结算上一个, 息屏停止计时;
     * 区间起点向前回看以补回"开始前已在前台"的使用段, 结算时裁剪回区间内.
     */
    @Suppress("DEPRECATION")
    private fun computeForegroundTime(
        usageStatsManager: UsageStatsManager,
        startMs: Long,
        endMs: Long,
        excludedPackages: Set<String>,
    ): Map<String, Long> {
        val foregroundMs = HashMap<String, Long>()
        val events = usageStatsManager.queryEvents(startMs - lookbackMs, endMs)
        val event = UsageEvents.Event()

        var currentPkg: String? = null
        var currentStart = 0L

        fun settle(until: Long) {
            val pkg = currentPkg
            currentPkg = null
            if (pkg == null || pkg in excludedPackages) return
            val from = maxOf(currentStart, startMs)
            val duration = until - from
            if (duration > 0) {
                foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (event.packageName != currentPkg) {
                        settle(event.timeStamp)
                        currentPkg = event.packageName
                        currentStart = event.timeStamp
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (event.packageName == currentPkg) {
                        settle(event.timeStamp)
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    settle(event.timeStamp)
                }
            }
        }
        settle(endMs)
        return foregroundMs
    }

    private fun resolveLauncherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun resolveAppName(pm: PackageManager, packageName: String): String {
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    // ---------------------------------------------------------------------
    // Calendar query
    // ---------------------------------------------------------------------

    private fun queryCalendar(params: JSONObject): String {
        val limit = params.optString("limit").toIntOrNull()?.coerceIn(1, 100)
            ?: params.optInt("limit", 20).coerceIn(1, 100)
        val query = params.optString("query").takeIf { it.isNotBlank() }

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params.optString("begin").takeIf { it.isNotBlank() }
        val endRaw = params.optString("end").takeIf { it.isNotBlank() }
        val rangePreset = params.optString("range").takeIf { it.isNotBlank() } ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (beginRaw != null) {
                // Custom interval: 'range' is ignored per the tool contract, and
                // the end defaults to now (matches iOS).
                now
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format for begin/end.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "begin must be earlier than end.")
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        // Escape LIKE wildcards so the keyword is matched literally as a
        // substring (e.g. searching "100%" must not act as a wildcard).
        val selection = if (query != null) "${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'" else null
        val selectionArgs = if (query != null) {
            val escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            arrayOf("%$escaped%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = JSONArray()
        activity.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val dtStart = cursor.getLong(4)
                val dtEnd = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1
                val obj = JSONObject()
                    .put("id", cursor.getLong(0))
                    .put("title", cursor.getString(1) ?: "")
                    .put("description", cursor.getString(2) ?: "")
                    .put("location", cursor.getString(3) ?: "")
                if (allDay) {
                    obj.put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    obj.put(
                        "end",
                        if (dtEnd > 0) Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString() else "",
                    )
                } else {
                    obj.put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                    obj.put(
                        "end",
                        if (dtEnd > 0) Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString() else "",
                    )
                }
                obj.put("all_day", allDay)
                obj.put("calendar", cursor.getString(7) ?: "")
                events.put(obj)
                count++
            }
        }

        return JSONObject()
            .put("range_start", startTime.withNano(0).toString())
            .put("range_end", endTime.withNano(0).toString())
            .put("count", events.length())
            .put("events", events)
            .toString()
    }

    // ---------------------------------------------------------------------
    // Calendar create
    // ---------------------------------------------------------------------

    private fun createCalendarEvent(params: JSONObject): String {
        val title = params.optString("title").takeIf { it.isNotBlank() }
        val startRaw = params.optString("start").takeIf { it.isNotBlank() }
        val endRaw = params.optString("end").takeIf { it.isNotBlank() }
        val allDay = params.optBoolean("all_day", false)

        if (title == null || startRaw == null) {
            return errorPayload("MISSING_REQUIRED", "Both 'title' and 'start' are required.")
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "end must be later than start.")
        }

        val description = params.optString("description")
        val location = params.optString("location")

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                return errorPayload("INVALID_RANGE", "all-day event end date must be later than start date.")
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId()
            ?: return errorPayload(
                "NO_CALENDAR",
                "No calendar account found on this device. Please add a calendar account first.",
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.DTEND, eventEndMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
        }

        val uri = activity.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return errorPayload("INSERT_FAILED", "Failed to insert calendar event.")

        val eventId = ContentUris.parseId(uri)
        return JSONObject()
            .put("success", true)
            .put("event_id", eventId)
            .put("title", title)
            .put("start", startTime.withNano(0).toString())
            .put("end", endTime.withNano(0).toString())
            .put("all_day", allDay)
            .put("location", location)
            .toString()
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val writableSelection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        activity.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
            writableArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        activity.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            writableSelection,
            writableArgs,
            "${CalendarContract.Calendars.VISIBLE} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Time parsing
    // ---------------------------------------------------------------------

    /**
     * 依次尝试: epoch 毫秒 -> 带偏移日期时间 -> Instant -> 本地日期时间 -> 本地日期(当天 0 点).
     */
    private fun parseTime(raw: String, zone: ZoneId): ZonedDateTime {
        val text = raw.trim()
        text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
        runCatching { return Instant.parse(text).atZone(zone) }
        runCatching { return LocalDateTime.parse(text).atZone(zone) }
        runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
        error("Invalid time format: '$text'. Use ISO-8601 date/date-time or epoch milliseconds.")
    }

    // ---------------------------------------------------------------------
    // File Read/Write (Phase 1)
    // ---------------------------------------------------------------------

    /**
     * 读取文件内容。
     * 支持应用私有目录和外部存储。
     */
    private fun readFile(params: JSONObject): String {
        val path = params.optString("path").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_REQUIRED", "'path' is required.")
        val encoding = params.optString("encoding").takeIf { it.isNotBlank() } ?: "utf8"

        val file = resolveFile(path)
        if (!file.exists()) {
            return errorPayload("FILE_NOT_FOUND", "File not found: $path")
        }
        if (file.isDirectory) {
            return errorPayload("IS_DIRECTORY", "Path is a directory, not a file: $path")
        }

        return try {
            val content = if (encoding == "base64") {
                android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } else {
                file.readText(Charsets.UTF_8)
            }
            JSONObject()
                .put("path", path)
                .put("size", file.length())
                .put("encoding", encoding)
                .put("content", content)
                .toString()
        } catch (e: Exception) {
            errorPayload("READ_ERROR", "Failed to read file: ${e.message}")
        }
    }

    /**
     * 写入文件内容。
     * 支持 overwrite 和 append 两种模式。
     */
    private fun writeFile(params: JSONObject): String {
        val path = params.optString("path").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_REQUIRED", "'path' is required.")
        val content = params.optString("content").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_REQUIRED", "'content' is required.")
        val mode = params.optString("mode").takeIf { it.isNotBlank() } ?: "overwrite"
        val encoding = params.optString("encoding").takeIf { it.isNotBlank() } ?: "utf8"

        val file = resolveFile(path)

        return try {
            // 确保父目录存在
            file.parentFile?.mkdirs()

            if (encoding == "base64") {
                val bytes = android.util.Base64.decode(content, android.util.Base64.NO_WRAP)
                if (mode == "append") {
                    file.appendBytes(bytes)
                } else {
                    file.writeBytes(bytes)
                }
            } else {
                if (mode == "append") {
                    file.appendText(content, Charsets.UTF_8)
                } else {
                    file.writeText(content, Charsets.UTF_8)
                }
            }

            JSONObject()
                .put("success", true)
                .put("path", path)
                .put("size", file.length())
                .put("mode", mode)
                .toString()
        } catch (e: Exception) {
            errorPayload("WRITE_ERROR", "Failed to write file: ${e.message}")
        }
    }

    /**
     * 列出目录下的文件和子目录。
     */
    private fun listFiles(params: JSONObject): String {
        val path = params.optString("path").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_REQUIRED", "'path' is required.")
        val recursive = params.optBoolean("recursive", false)
        val maxDepth = params.optInt("max_depth", 3).coerceIn(1, 10)

        val dir = resolveFile(path)
        if (!dir.exists()) {
            return errorPayload("PATH_NOT_FOUND", "Path not found: $path")
        }
        if (!dir.isDirectory) {
            return errorPayload("NOT_DIRECTORY", "Path is not a directory: $path")
        }

        return try {
            val files = JSONArray()
            listFilesRecursive(dir, files, recursive, maxDepth, 0)
            JSONObject()
                .put("path", path)
                .put("count", files.length())
                .put("files", files)
                .toString()
        } catch (e: Exception) {
            errorPayload("LIST_ERROR", "Failed to list files: ${e.message}")
        }
    }

    private fun listFilesRecursive(
        dir: java.io.File,
        result: JSONArray,
        recursive: Boolean,
        maxDepth: Int,
        currentDepth: Int
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val obj = JSONObject()
                .put("name", file.name)
                .put("path", file.absolutePath)
                .put("is_directory", file.isDirectory)
                .put("size", if (file.isFile) file.length() else 0)
                .put("last_modified", file.lastModified())
            result.put(obj)

            if (recursive && file.isDirectory && currentDepth < maxDepth) {
                listFilesRecursive(file, result, true, maxDepth, currentDepth + 1)
            }
        }
    }

    /**
     * 解析文件路径。
     * - 以 / 开头的视为绝对路径
     * - 其他视为应用私有目录下的相对路径
     */
    private fun resolveFile(path: String): java.io.File {
        return if (path.startsWith("/")) {
            java.io.File(path)
        } else {
            java.io.File(activity.filesDir, path)
        }
    }

    // ---------------------------------------------------------------------
    // API Call (Phase 1)
    // ---------------------------------------------------------------------

    /**
     * 调用 HTTP API。
     */
    private fun apiCall(params: JSONObject): String {
        val url = params.optString("url").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_REQUIRED", "'url' is required.")
        val method = params.optString("method").takeIf { it.isNotBlank() } ?: "GET"
        val headersJson = params.optJSONObject("headers")
        val body = params.optString("body").takeIf { it.isNotBlank() }
        val timeoutMs = params.optInt("timeout_ms", 30000).coerceIn(1000, 120000)

        return try {
            val client = java.net.HttpURLConnection(java.net.URL(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method.uppercase()
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Kelivo/1.0")

                // 设置请求头
                headersJson?.keys()?.forEach { key ->
                    setRequestProperty(key, headersJson.getString(key))
                }

                // 如果有 body
                if (body != null && method.uppercase() in listOf("POST", "PUT", "PATCH")) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val responseCode = client.responseCode
            val responseBody = try {
                client.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                client.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            }

            // 尝试解析 JSON 响应
            val parsedBody = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                responseBody
            }

            JSONObject()
                .put("status", responseCode)
                .put("url", url)
                .put("method", method)
                .put("body", parsedBody)
                .toString()
        } catch (e: Exception) {
            errorPayload("API_ERROR", "API call failed: ${e.message}")
        }
    }
}
