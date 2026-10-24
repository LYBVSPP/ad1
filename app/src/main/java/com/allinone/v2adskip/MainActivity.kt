package com.allinone.v2adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

// ==================== 1. 极简一屏主界面 ====================
class MainActivity : Activity() {
        private lateinit var statusTv: TextView
            private lateinit var configInput: EditText

                override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                                    val prefs = getSharedPreferences("v2_cfg", Context.MODE_PRIVATE)

                                            val layout = LinearLayout(this).apply {
                                                            orientation = LinearLayout.VERTICAL
                                                                        gravity = Gravity.CENTER_HORIZONTAL
                                                                                    setPadding(48, 64, 48, 48)
                                            }

                                                    statusTv = TextView(this).apply {
                                                                    textSize = 16f
                                                                                setPadding(0, 0, 0, 32)
                                                    }

                                                            val btnAdSkip = Button(this).apply {
                                                                            text = "① 开启/管理【开屏跳广告】无障碍权限"
                                                                                        setOnClickListener {
                                                                                                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                                                                        }
                                                            }

                                                                    configInput = EditText(this).apply {
                                                                                    hint = "粘贴您的 V2Ray/Xray 标准 JSON 配置（从 v2rayNG 导出完整配置）"
                                                                                                minLines = 6
                                                                                                            maxLines = 10
                                                                                                                        textSize = 12f
                                                                                                                                    setText(prefs.getString("json", ""))
                                                                    }

                                                                            val btnVpn = Button(this).apply {
                                                                                            text = "② 启动 / 停止 内置 V2Ray 代理"
                                                                                                        setOnClickListener {
                                                                                                                            prefs.edit().putString("json", configInput.text.toString()).apply()
                                                                                                                                            val intent = VpnService.prepare(this@MainActivity)
                                                                                                                                                            if (intent != null) {
                                                                                                                                                                                    startActivityForResult(intent, 100)
                                                                                                                                                            } else {
                                                                                                                                                                                    onActivityResult(100, RESULT_OK, null)
                                                                                                                                                            }
                                                                                                        }
                                                                            }

                                                                                    layout.addView(statusTv)
                                                                                            layout.addView(btnAdSkip)
                                                                                                    layout.addView(configInput)
                                                                                                            layout.addView(btnVpn)
                                                                                                                    setContentView(layout)
                }

                    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                                if (requestCode == 100 && resultCode == RESULT_OK) {
                                                startService(Intent(this, CoreVpnService::class.java))
                                                            Toast.makeText(this, "已触发内置 V2Ray 代理切换", Toast.LENGTH_SHORT).show()
                                }
                    }

                        override fun onResume() {
                                    super.onResume()
                                            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                                                    val adRunning = enabled.contains(packageName)
                                                            statusTv.text = "跳广告引擎：${if (adRunning) "【运行中】" else "【未开启】"}\nV2Ray 核心：${Libv2ray.checkVersionX()}"
                        }
}

// ==================== 2. 内置无障碍跳广告模块 ====================
class AdSkipService : AccessibilityService() {
        private val keywords = listOf("跳过", "跳过广告", "关闭广告", "Skip")
            private val ignorePkgs = setOf("com.android.systemui", "com.android.settings", "com.hihonor.android.launcher", "com.allinone.v2adskip")
                private var lastClick = 0L

                    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
                                val pkg = event?.packageName?.toString() ?: return
                                        if (ignorePkgs.contains(pkg)) return
                                                if (System.currentTimeMillis() - lastClick < 500) return

                                                        val root = rootInActiveWindow ?: return
                                                                for (kw in keywords) {
                                                                                for (node in root.findAccessibilityNodeInfosByText(kw)) {
                                                                                                    val txt = node.text?.toString()?.trim() ?: ""
                                                                                                                    if (txt.length in 2..8 && clickNode(node)) {
                                                                                                                                            lastClick = System.currentTimeMillis()
                                                                                                                                                                return
                                                                                                                    }
                                                                                }
                                                                }
                    }

                        private fun clickNode(node: AccessibilityNodeInfo): Boolean {
                                    var cur: AccessibilityNodeInfo? = node
                                            var depth = 0
                                                    while (cur != null && depth < 3) {
                                                                    if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                                                                cur = cur.parent
                                                                                            depth++
                                                    }
                                                            val rect = Rect()
                                                                    node.getBoundsInScreen(rect)
                                                                            if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                                                                                            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
                                                                                                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                                                                                                                    return dispatchGesture(gesture, null, null)
                                                                            }
                                                                                    return false
                        }

                            override fun onInterrupt() {}
}

// ==================== 3. 内置 V2Ray/Xray VPN 服务模块 ====================
class CoreVpnService : VpnService(), V2RayVPNServiceSupportsSet {
        private var mInterface: ParcelFileDescriptor? = null
            private val v2rayPoint: V2RayPoint = Libv2ray.newV2RayPoint(this, false)

                override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                            if (v2rayPoint.isRunning) {
                                            stopV2Ray()
                                                        return START_NOT_STICKY
                            }
                                    startForegroundNotification()
                                            val json = getSharedPreferences("v2_cfg", Context.MODE_PRIVATE).getString("json", "") ?: ""
                                                    if (json.isBlank()) {
                                                                    Toast.makeText(this, "请先粘贴节点 JSON 配置", Toast.LENGTH_SHORT).show()
                                                                                stopSelf()
                                                                                            return START_NOT_STICKY
                                                    }
                                                            try {
                                                                            v2rayPoint.configureFileContent = json
                                                                                        v2rayPoint.domainName = "1.1.1.1:443"
                                                                                                    v2rayPoint.runLoop(false)
                                                            } catch (e: Exception) {
                                                                            stopSelf()
                                                            }
                                                                    return START_STICKY
                }

                    override fun setup(parameters: String): Long {
                                val builder = Builder()
                                            .setSession("V2AdSkip")
                                                        .setMtu(1500)
                                                                    .addAddress("10.10.10.1", 24)
                                                                                .addRoute("0.0.0.0", 0)
                                                                                            .addDnsServer("1.1.1.1")
                                                                                                        .addDisallowedApplication(packageName) // 绕过自身防止回环
                                                                                                                mInterface = builder.establish()
                                                                                                                        return mInterface?.fd?.toLong() ?: -1L
                    }

                        override fun prepare(): Long = 0
                            override fun protect(l: Long): Boolean = protect(l.toInt())
                                override fun onEmitStatus(l: Long, s: String?): Long = 0
                                    override fun shutdown(): Long { stopV2Ray(); return 0 }

                                        private fun stopV2Ray() {
                                                    if (v2rayPoint.isRunning) v2rayPoint.stopLoop()
                                                            mInterface?.close()
                                                                    mInterface = null
                                                                            stopForeground(true)
                                                                                    stopSelf()
                                        }

                                            private fun startForegroundNotification() {
                                                        val channelId = "v2_channel"
                                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                                val channel = NotificationChannel(channelId, "VPN与跳广告保活", NotificationManager.IMPORTANCE_LOW)
                                                                                            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
                                                                }
                                                                        val notif = Notification.Builder(this, channelId)
                                                                                    .setContentTitle("V2Ray 与 开屏跳广告 运行中")
                                                                                                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                                                                                                            .build()
                                                                                                                    startForeground(1, notif)
                                            }
}

                                                                                                                                                            }
                                                                                        }
                                            }
                }
}