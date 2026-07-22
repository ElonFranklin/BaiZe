package com.baize.ai.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.baize.ai.R

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var switchTts: Switch
    private lateinit var spinnerVoiceRole: Spinner
    private lateinit var tvSpeedLabel: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var switchVoiceInput: Switch
    private lateinit var spinnerVoiceInputMode: Spinner
    private lateinit var tvVoiceInputModeHint: TextView

    // tap = 点击说话（默认）；hold = 按住说话
    private val inputModes = listOf("点击说话（默认）", "按住说话")
    private val inputModeValues = listOf("tap", "hold")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_voice_settings)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        switchTts = findViewById(R.id.switch_tts)
        spinnerVoiceRole = findViewById(R.id.spinner_voice_role)
        tvSpeedLabel = findViewById(R.id.tv_tts_speed_label)
        seekSpeed = findViewById(R.id.seek_tts_speed)
        switchVoiceInput = findViewById(R.id.switch_voice_input)
        spinnerVoiceInputMode = findViewById(R.id.spinner_voice_input_mode)
        tvVoiceInputModeHint = findViewById(R.id.tv_voice_input_mode_hint)

        val ttsPrefs = getSharedPreferences("baize_tts", MODE_PRIVATE)

        switchTts.isChecked = ttsPrefs.getBoolean("tts_enabled", true)

        val voiceRoles = listOf("晓晓 (女声)", "云扬 (男声)", "晓辰 (女声)", "晓墨 (男声)")
        val roleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceRoles)
        roleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceRole.adapter = roleAdapter
        spinnerVoiceRole.setSelection(ttsPrefs.getInt("voice_role_index", 0))

        val savedSpeed = ttsPrefs.getFloat("tts_speed", 1.0f)
        val speedToProgress = { speed: Float -> ((speed - 0.5f) / 0.05f).toInt().coerceIn(0, 30) }
        seekSpeed.progress = speedToProgress(savedSpeed)
        tvSpeedLabel.text = String.format("语速: %.1fx", savedSpeed)

        switchVoiceInput.isChecked = ttsPrefs.getBoolean("voice_input_enabled", true)

        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, inputModes)
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceInputMode.adapter = modeAdapter
        val savedMode = ttsPrefs.getString("voice_input_mode", "tap") ?: "tap"
        val modeIndex = inputModeValues.indexOf(savedMode).coerceAtLeast(0)
        spinnerVoiceInputMode.setSelection(modeIndex)
        updateModeHint(modeIndex)
        updateModeEnabled(switchVoiceInput.isChecked)
    }

    private fun setupListeners() {
        val ttsPrefs = getSharedPreferences("baize_tts", MODE_PRIVATE)

        switchTts.setOnCheckedChangeListener { _, isChecked ->
            ttsPrefs.edit().putBoolean("tts_enabled", isChecked).apply()
            setResult(RESULT_OK)
        }

        spinnerVoiceRole.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ttsPrefs.edit().putInt("voice_role_index", position).apply()
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val progressToSpeed = { progress: Int -> 0.5f + progress * 0.05f }
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                tvSpeedLabel.text = String.format("语速: %.1fx", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val speed = progressToSpeed(seekBar?.progress ?: 10)
                ttsPrefs.edit().putFloat("tts_speed", speed).apply()
                setResult(RESULT_OK)
            }
        })

        switchVoiceInput.setOnCheckedChangeListener { _, isChecked ->
            ttsPrefs.edit().putBoolean("voice_input_enabled", isChecked).apply()
            updateModeEnabled(isChecked)
            setResult(RESULT_OK)
        }

        spinnerVoiceInputMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = inputModeValues.getOrElse(position) { "tap" }
                ttsPrefs.edit().putString("voice_input_mode", mode).apply()
                updateModeHint(position)
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateModeEnabled(enabled: Boolean) {
        spinnerVoiceInputMode.isEnabled = enabled
        tvVoiceInputModeHint.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateModeHint(position: Int) {
        tvVoiceInputModeHint.text = if (position == 1) {
            "按住说话：按住麦克风开始，松手结束。系统仍可能在长停顿时提前结束。"
        } else {
            "点击说话：点一下开始，停顿约2秒自动结束，或再点麦克风手动结束。"
        }
    }
}
