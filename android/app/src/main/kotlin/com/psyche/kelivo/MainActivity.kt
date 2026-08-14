package com.psyche.kelivo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class MainActivity : FlutterActivity() {
    private companion object {
        const val CREATE_DOCUMENT_REQUEST_CODE = 4107
    }

    private val processTextChannelName = "app.process_text"
    private val fileSaveChannelName = "app.file_save"
    private var processTextChannel: MethodChannel? = null
    private var fileSaveChannel: MethodChannel? = null
    private var pendingProcessText: String? = null
     private var pendingSaveResult: MethodChannel.Result? = null
     private var pendingSaveSourcePath: String? = null
     private var deviceLocalToolsHandler: DeviceLocalToolsHandler? = null
    private var accessibilityChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
         super.configureFlutterEngine(flutterEngine)
         McpOAuthHandler.configure(this, flutterEngine.dartExecutor.binaryMessenger)
         deviceLocalToolsHandler = DeviceLocalToolsHandler(this).also {
             it.configure(flutterEngine.dartExecutor.binaryMessenger)
         }
         // Phase 2: 无障碍服务 MethodChannel
         accessibilityChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "app.accessibility")
         accessibilityChannel?.setMethodCallHandler { call, result ->
             val argsJson = call.arguments as? String ?: "{}"
             val service = AgentAccessibilityService.instance
             if (service == null) {
                 result.success(JSONObject().put("error", "NOT_ENABLED").put("message", "无障碍服务未开启，请在系统设置中开启").toString())
                 return@setMethodCallHandler
             }
             when (call.method) {
                 "readScreen" -> {
                     val format = JSONObject(argsJson).optString("format", "tree")
                     result.success(service.readScreen(format))
                 }
                 "findAndClick" -> result.success(service.findAndClick(JSONObject(argsJson)))
                 "findAndInput" -> result.success(service.findAndInput(JSONObject(argsJson)))
                 "swipe" -> result.success(service.swipe(JSONObject(argsJson)))
                 "pressButton" -> result.success(service.pressButton(JSONObject(argsJson)))
                 "openApp" -> result.success(service.openApp(JSONObject(argsJson)))
                 "takeScreenshot" -> result.success(service.takeScreenshot())
                 "isEnabled" -> result.success(service != null)
                 else -> result.notImplemented()
             }
         }
        processTextChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, processTextChannelName)
        processTextChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialText" -> {
                    val text = pendingProcessText ?: extractProcessText(intent)
                    pendingProcessText = null
                    result.success(text)
                }
                else -> result.notImplemented()
            }
        }
        fileSaveChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, fileSaveChannelName)
        fileSaveChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "saveFileFromPath" -> handleSaveFileFromPath(call.arguments, result)
                else -> result.notImplemented()
            }
        }
        pendingProcessText = extractProcessText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val text = extractProcessText(intent) ?: return
        val ch = processTextChannel
        if (ch != null) {
            ch.invokeMethod("onProcessText", text)
        } else {
            pendingProcessText = text
        }
    }
 
     override fun onRequestPermissionsResult(
         requestCode: Int,
         permissions: Array<out String>,
         grantResults: IntArray,
     ) {
         if (deviceLocalToolsHandler?.onRequestPermissionsResult(requestCode, grantResults) == true) {
             return
         }
         super.onRequestPermissionsResult(requestCode, permissions, grantResults)
     }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CREATE_DOCUMENT_REQUEST_CODE) {
            return
        }

        val destUri = if (resultCode == Activity.RESULT_OK) data?.data else null
        handleSaveDestination(destUri)
    }

    private fun extractProcessText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun handleSaveFileFromPath(arguments: Any?, result: MethodChannel.Result) {
        if (pendingSaveResult != null) {
            result.error("busy", "Another save operation is already in progress.", null)
            return
        }

        val args = arguments as? Map<*, *>
        val rawSourcePath = args?.get("sourcePath")?.toString()?.trim().orEmpty()
        if (rawSourcePath.isEmpty()) {
            result.error("invalid_args", "Missing sourcePath.", null)
            return
        }

        val sourceFile = File(rawSourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            result.error("not_found", "Source file does not exist.", null)
            return
        }

        val suggestedFileName = args?.get("fileName")?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sourceFile.name

        pendingSaveResult = result
        pendingSaveSourcePath = sourceFile.absolutePath

        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, suggestedFileName)
            }
            startActivityForResult(intent, CREATE_DOCUMENT_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            pendingSaveResult = null
            pendingSaveSourcePath = null
            result.error("launch_failed", e.message, null)
        }
    }

    private fun handleSaveDestination(destUri: Uri?) {
        val result = pendingSaveResult ?: return
        val sourcePath = pendingSaveSourcePath

        if (destUri == null || sourcePath.isNullOrBlank()) {
            pendingSaveResult = null
            pendingSaveSourcePath = null
            result.success(false)
            return
        }

        Thread {
            try {
                contentResolver.openOutputStream(destUri)?.use { outputStream ->
                    FileInputStream(File(sourcePath)).use { inputStream ->
                        inputStream.copyTo(outputStream, DEFAULT_BUFFER_SIZE)
                    }
                } ?: throw IllegalStateException("Unable to open destination stream.")

                runOnUiThread {
                    pendingSaveResult = null
                    pendingSaveSourcePath = null
                    result.success(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pendingSaveResult = null
                    pendingSaveSourcePath = null
                    result.error("save_failed", e.message, null)
                }
            }
        }.start()
    }
}
