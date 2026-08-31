package com.kail.location.views.sponsor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.kail.location.R
import com.kail.location.auth.AuthManager
import com.kail.location.network.RuoYiClient
import com.kail.location.utils.KailLog
import com.kail.location.views.base.BaseActivity
import com.kail.location.views.theme.locationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Color as AndroidColor

/**
 * 收银台 WebView（Paddle/Stripe）。
 *
 * Stripe 微信支付二维码在 Android WebView 内无法渲染，因此：
 * WebView 仅用于展示结算页/发起支付；支付意图确认后由本类轮询后端 stripe-qrcode
 * 接口拿到二维码图片，用原生 Dialog 展示，并提供"微信内打开"按钮（weixin://）唤起微信。
 */
@OptIn(ExperimentalMaterial3Api::class)
class CheckoutWebViewActivity : BaseActivity() {

    companion object {
        const val EXTRA_URL = "checkout_url"
        const val EXTRA_SESSION_ID = "checkout_session_id"
        private const val TAG = "CheckoutWebView"
    }

    private var qrCodeImage by mutableStateOf<String?>(null)
    private var wechatUrl by mutableStateOf<String?>(null)
    private var sessionId: String = ""
    private var polling = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checkoutUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""

        setContent {
            locationTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(getString(R.string.checkout_title)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.checkout_back_desc))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White
                            )
                        )
                    }
                ) { paddingValues ->
                    AndroidView(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                WebView.setWebContentsDebuggingEnabled(true)

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false

                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
                                        Log.d(TAG, "$sourceID:$lineNumber: $message")
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        Log.d(TAG, "onPageStarted: ${url.take(100)}")
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        super.onPageFinished(view, url)
                                        Log.d(TAG, "onPageFinished: ${url.take(100)}")
                                    }
                                }

                                loadUrl(checkoutUrl)
                            }
                        }
                    )
                }

                // 原生二维码弹窗（可关闭，深色/浅色自适应，二维码固定白底保证可扫）
                qrCodeImage?.let { qrImage ->
                    AlertDialog(
                        onDismissRequest = { qrCodeImage = null },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("微信支付", modifier = Modifier.weight(1f))
                                IconButton(onClick = { qrCodeImage = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                val bitmap = try {
                                    // 优先用 weixin:// 链接本地生成高清二维码（Stripe 返回的 image_data_url 分辨率极低）
                                    val fromWx = wechatUrl?.let { generateQrBitmap(it, 512) }
                                    if (fromWx != null) {
                                        fromWx
                                    } else {
                                        val clean = qrImage.removePrefix("data:image/png;base64,").removePrefix("data:image/jpeg;base64,")
                                        val bytes = Base64.decode(clean, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }
                                } catch (e: Exception) { null }
                                if (bitmap != null) {
                                    // 固定白色背景：深色模式下二维码区仍是白底黑码，保证可识别
                                    Box(
                                        modifier = Modifier
                                            .background(Color.White, RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "微信支付二维码",
                                            modifier = Modifier.size(220.dp)
                                        )
                                    }
                                }
                                Text("请使用微信扫描二维码支付", modifier = Modifier.padding(top = 10.dp))
                            }
                        },
                        confirmButton = {},
                    )
                }
            }
        }

        // 轮询二维码
        if (sessionId.isNotEmpty()) {
            startQrCodePolling(sessionId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionId.isNotEmpty()) {
            startQrCodePolling(sessionId)
        }

    }

    private fun startQrCodePolling(sessionId: String) {
        if (polling) return
        polling = true
        val token = AuthManager.token ?: return
        lifecycleScope.launch {
            try {
                while (isActive) {
                    try {
                        val (qrImage, wxUrl, status) = withContext(Dispatchers.IO) {
                            fetchStripeQrCode(token, sessionId)
                        }
                        when (status) {
                            "requires_action" -> {
                                qrCodeImage = qrImage
                                wechatUrl = wxUrl
                            }
                            "succeeded" -> {
                                Toast.makeText(this@CheckoutWebViewActivity, "支付成功", Toast.LENGTH_SHORT).show()
                                delay(600)
                                finish()
                                return@launch
                            }
                            "canceled", "failed", "processing" -> delay(3000)
                            else -> delay(2500)
                        }
                    } catch (e: Exception) {
                        KailLog.w(null, TAG, "poll qr code error: ${e.message}")
                        delay(3000)
                    }
                }
            } finally {
                polling = false
            }
        }
    }

    /**
     * 用 ZXing 本地生成高清二维码（替代 Stripe 低分辨率 image_data_url）
     */
    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            KailLog.w(null, TAG, "generateQrBitmap failed: ${e.message}")
            null
        }
    }

    private fun fetchStripeQrCode(token: String, sessionId: String): Triple<String?, String?, String> {        val url = "${RuoYiClient.baseUrl}/member/subscription/stripe-qrcode?sessionId=$sessionId"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("tenant-id", "1")
            .build()
        val response = RuoYiClient.okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 0) {
            throw Exception(root.optString("msg", "query failed"))
        }
        val data = root.getJSONObject("data")
        return Triple(
            data.optString("qrCodeImage", "").ifEmpty { null },
            data.optString("wechatUrl", "").ifEmpty { null },
            data.optString("status", "pending")
        )
    }
}
