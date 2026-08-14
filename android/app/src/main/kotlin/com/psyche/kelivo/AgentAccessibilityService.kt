package com.psyche.kelivo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍服务 - Agent 操控手机的核心能力
 *
 * 提供以下能力：
 * - readScreen: 读取当前屏幕控件树
 * - findAndClick: 查找并点击指定元素
 * - findAndInput: 查找输入框并填入文字
 * - swipe: 模拟滑动
 * - pressButton: 模拟按键 (back/home/recent)
 * - openApp: 打开指定应用
 * - takeScreenshot: 截图
 * - listRunningApps: 列出运行中的应用
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set

        private var methodChannel: MethodChannel? = null

        fun setMethodChannel(channel: MethodChannel) {
            methodChannel = channel
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理事件，仅被动响应工具调用
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    override fun onServiceDisconnected() {
        super.onServiceDisconnected()
        instance = null
    }

    // ── 读取屏幕 ──

    /**
     * 读取当前屏幕的控件树
     */
    fun readScreen(format: String = "tree"): String {
        val root = rootInActiveWindow
            ?: return errorPayload("NO_WINDOW", "无法获取当前窗口信息")

        return try {
            when (format) {
                "flat" -> readScreenFlat(root)
                else -> readScreenTree(root, 0)
            }
        } catch (e: Exception) {
            errorPayload("READ_ERROR", "读取屏幕失败: ${e.message}")
        }
    }

    private fun readScreenTree(node: AccessibilityNodeInfo, depth: Int): String {
        val indent = "  ".repeat(depth)
        val sb = StringBuilder()

        val className = node.className?.toString()?.substringAfterLast('.') ?: "Unknown"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val checkable = node.isCheckable
        val checked = node.isChecked

        sb.append("$indent[$className]")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
        if (id.isNotEmpty()) sb.append(" id=$id")
        sb.append(" bounds=${bounds.toShortString()}")
        if (clickable) sb.append(" clickable")
        if (editable) sb.append(" editable")
        if (scrollable) sb.append(" scrollable")
        if (checkable) sb.append(" checked=$checked")
        sb.appendLine()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(readScreenTree(child, depth + 1))
            child.recycle()
        }

        return sb.toString()
    }

    private fun readScreenFlat(node: AccessibilityNodeInfo): String {
        val items = JSONArray()
        collectNodesFlat(node, items)
        return JSONObject()
            .put("count", items.length())
            .put("nodes", items)
            .toString()
    }

    private fun collectNodesFlat(node: AccessibilityNodeInfo, result: JSONArray) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        // 只记录有意义的节点
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val obj = JSONObject()
                .put("class", node.className?.toString()?.substringAfterLast('.') ?: "Unknown")
                .put("text", text)
                .put("description", desc)
                .put("id", id)
                .put("bounds", bounds.toShortString())
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("scrollable", node.isScrollable)
                .put("enabled", node.isEnabled)
            result.put(obj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesFlat(child, result)
            child.recycle()
        }
    }

    // ── 点击元素 ──

    /**
     * 查找并点击指定元素
     */
    fun findAndClick(params: JSONObject): String {
        val text = params.optString("text").takeIf { it.isNotBlank() }
        val id = params.optString("id").takeIf { it.isNotBlank() }
        val desc = params.optString("description").takeIf { it.isNotBlank() }
        val index = params.optInt("index", 0)

        if (text == null && id == null && desc == null) {
            return errorPayload("MISSING_TARGET", "需要指定 text、id 或 description 中的一个")
        }

        val root = rootInActiveWindow
            ?: return errorPayload("NO_WINDOW", "无法获取当前窗口信息")

        val node = findNode(root, text, id, desc, index)
            ?: return errorPayload("NOT_FOUND", "未找到匹配的元素")

        return try {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) {
                JSONObject().put("success", true).put("action", "click").toString()
            } else {
                // 尝试点击父节点
                val parent = node.parent
                val parentSuccess = parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                parent?.recycle()
                if (parentSuccess) {
                    JSONObject().put("success", true).put("action", "click_parent").toString()
                } else {
                    errorPayload("CLICK_FAILED", "点击操作失败")
                }
            }
        } catch (e: Exception) {
            errorPayload("CLICK_ERROR", "点击失败: ${e.message}")
        } finally {
            node.recycle()
        }
    }

    // ── 输入文字 ──

    /**
     * 查找输入框并填入文字
     */
    fun findAndInput(params: JSONObject): String {
        val text = params.optString("text")
        val targetText = params.optString("target").takeIf { it.isNotBlank() }
        val targetId = params.optString("target_id").takeIf { it.isNotBlank() }

        if (text.isEmpty()) {
            return errorPayload("MISSING_TEXT", "需要指定要输入的文字")
        }

        val root = rootInActiveWindow
            ?: return errorPayload("NO_WINDOW", "无法获取当前窗口信息")

        // 查找目标输入框
        val node = if (targetText != null || targetId != null) {
            findNode(root, targetText, targetId, null, 0)
                ?: return errorPayload("NOT_FOUND", "未找到目标输入框")
        } else {
            // 查找当前焦点所在的输入框
            findFocusedEditableNode(root)
                ?: return errorPayload("NO_FOCUS", "未找到焦点输入框，请先点击输入框")
        }

        return try {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) {
                JSONObject().put("success", true).put("text", text).toString()
            } else {
                errorPayload("INPUT_FAILED", "输入操作失败")
            }
        } catch (e: Exception) {
            errorPayload("INPUT_ERROR", "输入失败: ${e.message}")
        } finally {
            node.recycle()
        }
    }

    // ── 滑动 ──

    /**
     * 模拟屏幕滑动
     */
    fun swipe(params: JSONObject): String {
        val direction = params.optString("direction").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_DIRECTION", "需要指定方向: up/down/left/right")

        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction.lowercase()) {
            "up" -> {
                startX = screenWidth / 2
                startY = screenHeight * 0.7f
                endX = screenWidth / 2
                endY = screenHeight * 0.3f
            }
            "down" -> {
                startX = screenWidth / 2
                startY = screenHeight * 0.3f
                endX = screenWidth / 2
                endY = screenHeight * 0.7f
            }
            "left" -> {
                startX = screenWidth * 0.8f
                startY = screenHeight / 2
                endX = screenWidth * 0.2f
                endY = screenHeight / 2
            }
            "right" -> {
                startX = screenWidth * 0.2f
                startY = screenHeight / 2
                endX = screenWidth * 0.8f
                endY = screenHeight / 2
            }
            else -> return errorPayload("INVALID_DIRECTION", "方向必须是: up/down/left/right")
        }

        return performSwipe(startX, startY, endX, endY, 300)
    }

    private fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): String {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val success = dispatchGesture(gesture, null, null)
        return if (success) {
            JSONObject().put("success", true).put("action", "swipe").toString()
        } else {
            errorPayload("SWIPE_FAILED", "滑动操作失败")
        }
    }

    // ── 按键 ──

    /**
     * 模拟按键操作
     */
    fun pressButton(params: JSONObject): String {
        val button = params.optString("button").takeIf { it.isNotBlank() }
            ?: return errorPayload("MISSING_BUTTON", "需要指定按钮: back/home/recent")

        return try {
            val success = when (button.lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                else -> return errorPayload("INVALID_BUTTON", "按钮必须是: back/home/recent/notifications")
            }
            if (success) {
                JSONObject().put("success", true).put("button", button).toString()
            } else {
                errorPayload("BUTTON_FAILED", "按键操作失败")
            }
        } catch (e: Exception) {
            errorPayload("BUTTON_ERROR", "按键失败: ${e.message}")
        }
    }

    // ── 打开应用 ──

    /**
     * 打开指定应用
     */
    fun openApp(params: JSONObject): String {
        val packageName = params.optString("package_name").takeIf { it.isNotBlank() }
        val appName = params.optString("app_name").takeIf { it.isNotBlank() }

        if (packageName == null && appName == null) {
            return errorPayload("MISSING_TARGET", "需要指定 package_name 或 app_name")
        }

        return try {
            val intent = packageManager.getLaunchIntentForPackage(
                packageName ?: findPackageByAppName(appName!!)
            )
                ?: return errorPayload("APP_NOT_FOUND", "未找到应用: ${packageName ?: appName}")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            JSONObject().put("success", true).put("package", intent.`package`).toString()
        } catch (e: Exception) {
            errorPayload("OPEN_ERROR", "打开应用失败: ${e.message}")
        }
    }

    private fun findPackageByAppName(appName: String): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.contains(appName, ignoreCase = true)) {
                return app.packageName
            }
        }
        throw Exception("未找到名为 '$appName' 的应用")
    }

    // ── 截图 ──

    /**
     * 截取当前屏幕
     */
    fun takeScreenshot(): String {
        // 无障碍服务无法直接截图，需要 MediaProjection
        // 这里返回提示信息
        return errorPayload(
            "NOT_SUPPORTED",
            "截图功能需要 MediaProjection 权限，请使用系统截图功能"
        )
    }

    // ── 辅助方法 ──

    private fun findNode(
        root: AccessibilityNodeInfo,
        text: String?,
        id: String?,
        desc: String?,
        index: Int
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, text, id, desc, candidates)

        return if (index < candidates.size) {
            // 回收其他节点
            for (i in candidates.indices) {
                if (i != index) candidates[i].recycle()
            }
            candidates[index]
        } else {
            candidates.forEach { it.recycle() }
            null
        }
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        text: String?,
        id: String?,
        desc: String?,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val matchText = text == null || (node.text?.toString()?.contains(text, true) == true)
        val matchId = id == null || (node.viewIdResourceName?.contains(id, true) == true)
        val matchDesc = desc == null || (node.contentDescription?.toString()?.contains(desc, true) == true)

        if (matchText && matchId && matchDesc) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatchingNodes(child, text, id, desc, result)
            child.recycle()
        }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && root.isEditable) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun errorPayload(error: String, message: String): String {
        return JSONObject().put("error", error).put("message", message).toString()
    }
}
