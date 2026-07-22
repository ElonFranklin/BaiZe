package com.baize.ai.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.baize.ai.R

class AboutSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_about_settings)

        // 返回按钮
        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        // 开源协议
        findViewById<Button>(R.id.btn_license)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ElonFranklin/BaiZe/blob/main/LICENSE"))
            startActivity(intent)
        }

        // 反馈：优先邮箱
        findViewById<Button>(R.id.btn_feedback)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:tommusk333@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "白泽反馈与建议")
            }
            try {
                startActivity(Intent.createChooser(intent, "发送反馈邮件"))
            } catch (e: Exception) {
                Toast.makeText(this, "请发送邮件至 tommusk333@gmail.com", Toast.LENGTH_LONG).show()
            }
        }
    }
}

