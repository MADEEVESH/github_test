package dev.offlinechat.offlinechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.offlinechat.offlinechat.mesh.MeshRuntime
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingStart: MethodChannel.Result? = null
    private var events: EventChannel.EventSink? = null
    private val runtime get() = MeshRuntime.get(applicationContext)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "offlinechat/mesh")
            .setMethodCallHandler { call, result ->
                if (call.method == "start") startWithPermissions(result)
                else dispatch(call, result)
            }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "offlinechat/mesh_events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    events = sink
                    runtime.observe { value -> events?.success(value) }
                }
                override fun onCancel(arguments: Any?) {
                    events = null
                    runtime.observe(null)
                }
            })
    }

    private fun dispatch(call: MethodCall, result: MethodChannel.Result) {
        runtime.call(call.method, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()) { value, error ->
            when (error) {
                null -> result.success(value)
                is UnsupportedOperationException -> result.notImplemented()
                else -> result.error("mesh_error", error.message ?: "Mesh operation failed.", null)
            }
        }
    }

    private fun startWithPermissions(result: MethodChannel.Result) {
        if (pendingStart != null) {
            result.error("permission_pending", "A Nearby devices permission request is already open.", null)
            return
        }
        val permissions = MeshRuntime.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            dispatch(MethodCall("start", null), result)
        } else {
            pendingStart = result
            requestPermissions(permissions.toTypedArray(), MESH_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MESH_PERMISSION_REQUEST) return
        val result = pendingStart ?: return
        pendingStart = null
        if (MeshRuntime.requiredPermissions().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            result.error("permission_denied", "Nearby devices permission is required for Bluetooth mesh. On Android 10/11, grant location permission and enable Location services for BLE discovery.", null)
        } else {
            // Notification permission is optional; Android still shows the foreground task.
            dispatch(MethodCall("start", null), result)
        }
    }

    override fun onDestroy() {
        pendingStart?.error("activity_closed", "The activity closed during the permission request. Start mesh again when the app is visible.", null)
        pendingStart = null
        events = null
        runtime.observe(null)
        super.onDestroy()
    }

    companion object { private const val MESH_PERMISSION_REQUEST = 2402 }
}
