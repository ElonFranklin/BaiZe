package com.baize.ai.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import com.baize.ai.ui.shop.ShopActivity
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etNickname: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var tvSwitchMode: TextView
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTestAccount: TextView

    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        repository = ShopRepository.getInstance(this)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etNickname = findViewById(R.id.et_nickname)
        btnLogin = findViewById(R.id.btn_login)
        btnRegister = findViewById(R.id.btn_register)
        tvSwitchMode = findViewById(R.id.tv_switch_mode)
        tvError = findViewById(R.id.tv_error)
        progressBar = findViewById(R.id.progress_bar)
        tvTestAccount = findViewById(R.id.tv_test_account)
        // 首发隐藏测试账号提示
        tvTestAccount.visibility = View.GONE
    }

    private fun setupListeners() {
        tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateUI()
        }

        btnLogin.setOnClickListener { performLogin() }
        btnRegister.setOnClickListener { performRegister() }

        // 首发：不提供测试账号一键填充
    }

    private fun updateUI() {
        tvError.visibility = View.GONE
        if (isLoginMode) {
            btnLogin.visibility = View.VISIBLE
            btnRegister.visibility = View.GONE
            etNickname.visibility = View.GONE
            tvSwitchMode.text = "没有账号？点击注册"
        } else {
            btnLogin.visibility = View.GONE
            btnRegister.visibility = View.VISIBLE
            etNickname.visibility = View.VISIBLE
            tvSwitchMode.text = "已有账号？点击登录"
        }
    }

    private fun performLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            showError("请输入邮箱和密码")
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val result = repository.login(email, password)
            showLoading(false)
            result.onSuccess {
                Toast.makeText(this@AuthActivity, "登录成功", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            result.onFailure {
                showError(it.message ?: "登录失败")
            }
        }
    }

    private fun performRegister() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val nickname = etNickname.text.toString().trim()

        if (email.isEmpty() || password.isEmpty() || nickname.isEmpty()) {
            showError("请填写完整信息")
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val result = repository.register(email, password, nickname)
            showLoading(false)
            result.onSuccess {
                Toast.makeText(this@AuthActivity, "注册成功", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            result.onFailure {
                showError(it.message ?: "注册失败")
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !show
        btnRegister.isEnabled = !show
    }
}

