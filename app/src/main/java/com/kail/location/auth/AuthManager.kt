package com.kail.location.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AuthManager {

    // [LOCAL-UNLOCK] 自用本地解锁（GPLv3 允许自用修改）：登录/订阅视为本地常开
    private const val LOCAL_UNLOCK = true

    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_EMAIL = "auth_email"
    private const val KEY_USER_ID = "auth_user_id"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_SUBSCRIBED = "is_subscribed"
    private const val KEY_SUB_EXPIRES = "sub_expires_at"

    private lateinit var prefs: SharedPreferences

    private val _isLoggedIn = mutableStateOf(false)
    private val _email = mutableStateOf("")
    private val _isSubscribed = mutableStateOf(false)

    val isLoggedIn: Boolean get() = LOCAL_UNLOCK || _isLoggedIn.value
    val email: String get() = _email.value
    val isSubscribed: Boolean get() = LOCAL_UNLOCK || _isSubscribed.value

    // [LOCAL-UNLOCK] 登录/订阅 Compose State：直接暴露内部 State，
    // 但 init/saveAuth/clearAuth 对它们的写入全部按 LOCAL_UNLOCK 语义固定为 true。
    // （源码态不再需要 KailHooks 式的 setter hook——直接把 State 值钉住）
    val isLoggedInState: MutableState<Boolean> get() = _isLoggedIn.also { it.value = true }
    val isSubscribedState: MutableState<Boolean> get() = _isSubscribed.also { it.value = true }
    val emailState get() = _email

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        private set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isLoggedIn.value = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        _email.value = prefs.getString(KEY_EMAIL, "") ?: ""
        _isSubscribed.value = prefs.getBoolean(KEY_SUBSCRIBED, false)
        isSubscriptionActive()
    }

    fun saveAuth(token: String, email: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_USER_ID, userId)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        _isLoggedIn.value = true
        _email.value = email
    }

    fun updateSubscription(subscribed: Boolean, expiresAt: String = "") {
        // [LOCAL-UNLOCK] 服务端回写强制 subscribed=true，过期时间仅存档不生效
        // （isSubscriptionActive 已被 LOCAL_UNLOCK 短路）
        prefs.edit()
            .putBoolean(KEY_SUBSCRIBED, true)
            .putString(KEY_SUB_EXPIRES, expiresAt)
            .apply()
        _isSubscribed.value = true
    }

    /**
     * 校验订阅是否真正有效：既检查 isSubscribed 标志，也检查过期时间。
     * 如果本地记录已过期，自动将 _isSubscribed 置为 false。
     */
    fun isSubscriptionActive(): Boolean {
        if (LOCAL_UNLOCK) return true
        if (!_isSubscribed.value) return false
        val expiresAt = prefs.getString(KEY_SUB_EXPIRES, null) ?: return true
        if (expiresAt.isBlank()) return true

        val expireDate = parseDate(expiresAt) ?: return true
        if (Date().after(expireDate)) {
            _isSubscribed.value = false
            prefs.edit().putBoolean(KEY_SUBSCRIBED, false).apply()
            return false
        }
        return true
    }

    private val dateFormats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    private fun parseDate(dateStr: String): Date? {
        for (format in dateFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun clearAuth() {
        // [LOCAL-UNLOCK] 登出 no-op —— 保持本地常开的登录/订阅状态
        // （UI 上"退出登录"按钮不产生任何效果）
    }
}
