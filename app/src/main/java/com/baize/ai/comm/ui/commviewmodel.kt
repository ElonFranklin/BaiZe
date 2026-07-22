package com.baize.ai.comm.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baize.ai.comm.CommManager
import com.baize.ai.comm.model.BzMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 通信模块 ViewModel v0.9.2
 * 修复：回调切主线程更新 StateFlow
 */
class CommViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CommViewModel"
    }

    private val commManager = CommManager(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 当前联系人
    private val _currentContact = MutableStateFlow<String?>(null)
    val currentContact: StateFlow<String?> = _currentContact

    // 聊天消息列表（增量更新）
    private val _messages = MutableStateFlow<List<BzMessage>>(emptyList())
    val messages: StateFlow<List<BzMessage>> = _messages

    // 最近联系人
    private val _contacts = MutableStateFlow<List<String>>(emptyList())
    val contacts: StateFlow<List<String>> = _contacts

    // 连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // 上次加载的消息数（用于增量更新）
    private var lastMessageCount = 0

    init {
        commManager.initialize("baiZe:local_user")

        // 监听新消息（回调在 IO 线程，切主线程更新 UI）
        commManager.onMessage { message ->
            mainHandler.post {
                handleNewMessage(message)
            }
        }

        // 监听连接状态（回调在 IO 线程，切主线程更新 UI）
        commManager.onConnectionChange { connected ->
            mainHandler.post {
                _isConnected.value = connected
            }
        }
    }

    /**
     * 处理新消息（增量更新）
     */
    private fun handleNewMessage(message: BzMessage) {
        val currentContactId = _currentContact.value

        // 如果是当前联系人的消息，增量添加
        if (message.from == currentContactId || message.to == currentContactId) {
            val currentMessages = _messages.value.toMutableList()
            // 检查是否已存在（避免重复）
            if (currentMessages.none { it.id == message.id }) {
                currentMessages.add(message)
                _messages.value = currentMessages
                lastMessageCount = currentMessages.size
            }
        }

        // 刷新联系人列表（轻量操作）
        loadContacts()
    }

    /**
     * 连接到竹萤节点
     */
    fun connect(host: String, port: Int = 9200) {
        viewModelScope.launch {
            commManager.connect(host, port)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        commManager.disconnect()
    }

    /**
     * 选择联系人
     */
    fun selectContact(contactId: String) {
        _currentContact.value = contactId
        loadMessages(contactId)
    }

    /**
     * 发送文本消息
     */
    fun sendMessage(text: String) {
        val contactId = _currentContact.value ?: return
        viewModelScope.launch {
            val message = commManager.sendMessage(to = contactId, text = text)
            if (message != null) {
                // 本地立即显示（乐观更新）
                mainHandler.post {
                    val currentMessages = _messages.value.toMutableList()
                    currentMessages.add(message)
                    _messages.value = currentMessages
                }
            }
        }
    }

    /**
     * 发送投票
     */
    fun sendVote(options: List<String>) {
        val contactId = _currentContact.value ?: return
        viewModelScope.launch {
            val message = commManager.sendVote(to = contactId, options = options)
            if (message != null) {
                mainHandler.post {
                    val currentMessages = _messages.value.toMutableList()
                    currentMessages.add(message)
                    _messages.value = currentMessages
                }
            }
        }
    }

    /**
     * 加载聊天记录
     */
    private fun loadMessages(contactId: String) {
        val messages = commManager.getConversation(contactId)
        _messages.value = messages
        lastMessageCount = messages.size
    }

    /**
     * 加载联系人列表
     */
    private fun loadContacts() {
        _contacts.value = commManager.getRecentContacts()
    }

    /**
     * 获取通信管理器
     */
    fun getCommManager(): CommManager = commManager

    /**
     * 清理资源
     */
    override fun onCleared() {
        super.onCleared()
        commManager.destroy()
        Log.i(TAG, "ViewModel cleared, CommManager destroyed")
    }
}
