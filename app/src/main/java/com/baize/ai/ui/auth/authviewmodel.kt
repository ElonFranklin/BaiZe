package com.baize.ai.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baize.ai.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AuthViewModel — 登录/注册状态管理
 */
class AuthViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState

    private val _isLoggedIn = MutableStateFlow(ApiClient.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private var loginAttempts = 0
    private var lastAttemptTime = 0L
    private val maxAttempts = 5
    private val lockoutDuration = 30_000L // 30 seconds

    sealed class AuthUiState {
        data object Idle : AuthUiState()
        data object Loading : AuthUiState()
        data class Success(val nickname: String) : AuthUiState()
        data class Error(val message: String) : AuthUiState()
    }

    fun login(phone: String, email: String, password: String) {
        // Client-side rate limiting
        if (loginAttempts >= maxAttempts) {
            val lockoutRemaining = lockoutDuration - (System.currentTimeMillis() - lastAttemptTime)
            if (lockoutRemaining > 0) {
                _uiState.value = AuthUiState.Error("尝试次数过多，请${lockoutRemaining / 1000}秒后重试")
                return
            }
            loginAttempts = 0
        }

        if (password.isBlank()) {
            _uiState.value = AuthUiState.Error("请输入密码")
            return
        }
        if (phone.isBlank() && email.isBlank()) {
            _uiState.value = AuthUiState.Error("请输入手机号或邮箱")
            return
        }
        if (password.length < 8) {
            _uiState.value = AuthUiState.Error("密码至少8位")
            return
        }

        // Phone format validation
        if (phone.isNotBlank() && !phone.matches(Regex("^1[3-9]\\d{9}$"))) {
            _uiState.value = AuthUiState.Error("手机号格式不正确")
            return
        }

        loginAttempts++
        lastAttemptTime = System.currentTimeMillis()

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = ApiClient.login(
                phone = phone.ifBlank { null },
                email = email.ifBlank { null },
                password = password
            )
            if (result.success) {
                loginAttempts = 0
                _isLoggedIn.value = true
                _uiState.value = AuthUiState.Success(result.nickname ?: "用户")
            } else {
                _uiState.value = AuthUiState.Error(result.error ?: "登录失败")
            }
        }
    }

    fun register(phone: String, email: String, password: String, nickname: String) {
        if (password.isBlank()) {
            _uiState.value = AuthUiState.Error("请输入密码")
            return
        }
        if (phone.isBlank() && email.isBlank()) {
            _uiState.value = AuthUiState.Error("请输入手机号或邮箱")
            return
        }
        if (password.length < 6) {
            _uiState.value = AuthUiState.Error("密码至少6位")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = ApiClient.register(
                phone = phone.ifBlank { null },
                email = email.ifBlank { null },
                password = password,
                nickname = nickname.ifBlank { null }
            )
            if (result.success) {
                _isLoggedIn.value = true
                _uiState.value = AuthUiState.Success(result.nickname ?: "用户")
            } else {
                _uiState.value = AuthUiState.Error(result.error ?: "注册失败")
            }
        }
    }

    fun logout() {
        ApiClient.logout()
        _isLoggedIn.value = false
        _uiState.value = AuthUiState.Idle
    }

    fun clearState() {
        _uiState.value = AuthUiState.Idle
    }
}
