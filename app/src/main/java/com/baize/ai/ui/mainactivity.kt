package com.baize.ai.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baize.ai.ui.chat.ChatMessage
import com.baize.ai.ui.chat.ChatViewModel
import com.baize.ai.ui.chat.TierMode
import com.baize.ai.ui.chat.ModelMode
import com.baize.ai.soul.core.SoulPromptBuilder
import com.baize.ai.ui.settings.SettingsHubActivity
import com.baize.ai.comm.ui.CommTestActivity
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.rememberAsyncImagePainter
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import java.io.File
import android.provider.MediaStore
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.util.Base64
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * MainActivity - Baize v2
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val sttLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSttResult(1001, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initApp()
    }

    private fun initApp() {
        viewModel.initVoice(this)
        viewModel.setSttLauncher(sttLauncher)

        val permLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                android.widget.Toast.makeText(this, "Need microphone permission", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFEADDFF),
                    background = Color(0xFFF5F5F5)
                )
            ) {
                BaizeChatScreen(viewModel = viewModel)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        viewModel.handleSttResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        viewModel.forceRefreshSoul()
        viewModel.refreshModelMode()
        viewModel.refreshCloudConfig()
        viewModel.reloadChatHistory()
        viewModel.loadAvatar()
    }
}

/**
 * Compress image and encode to base64 for API transmission
 */
fun compressAndEncodeImage(context: Context, uri: Uri): String? {
    return try {
        android.util.Log.d("BaizeImage", "Compressing: $uri")
        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            android.util.Log.e("BaizeImage", "Cannot open input stream")
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        // Calculate sample size
        val maxDim = 768
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        // Decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val inputStream2 = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
        inputStream2.close()

        if (bitmap != null) {
            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()
            bitmap.recycle()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else null
    } catch (e: Exception) {
        android.util.Log.e("ImageHelper", "Failed to compress image", e)
        null
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaizeChatScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val selectedImageUri = remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            val base64 = compressAndEncodeImage(context, uri)
            if (base64 != null) {
                selectedImageUri.value = "data:image/jpeg;base64,$base64"
                viewModel.setSelectedImage(selectedImageUri.value)
            } else {
                android.widget.Toast.makeText(context, "图片处理失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.soulName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // 首发精简：隐藏通信测试(卫星)与 AUTO，档位请在「对话设置」调整
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsHubActivity::class.java))
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.isListening) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (viewModel.isHoldToTalkEnabled()) {
                            "正在听… 松手结束，结果会填入输入框"
                        } else {
                            "正在听… 说完可再点麦克风结束，结果会填入输入框"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            ChatInput(
                value = uiState.inputText,
                onValueChange = { viewModel.updateInputText(it) },
                onSend = {
                    val text = uiState.inputText.ifBlank { " " }
                    viewModel.sendMessage(text)
                    selectedImageUri.value = null
                },
                enabled = !uiState.isGenerating && uiState.isReady,
                isListening = uiState.isListening,
                holdToTalk = viewModel.isHoldToTalkEnabled(),
                onMicClick = { viewModel.toggleVoiceInput() },
                onMicPress = { viewModel.startVoiceInput() },
                onMicRelease = { viewModel.stopVoiceInput() },
                onImageClick = { imagePickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }) },
                hasImage = selectedImageUri.value != null
            )
            // Show selected image preview
            if (selectedImageUri.value != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        // Thumbnail preview
                        val previewBitmap = remember(selectedImageUri.value) {
                            selectedImageUri.value?.let { dataUri ->
                                try {
                                    val base64Data = dataUri.substringAfter("base64,")
                                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (e: Exception) { null }
                            }
                        }
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = "Image attached",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        // Send button for image-only
                        IconButton(onClick = {
                            viewModel.sendMessage(" ")
                            selectedImageUri.value = null
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Send image",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            selectedImageUri.value = null
                            viewModel.clearSelectedImage()
                        }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp
                        )
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (uiState.isModelLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Auto-scroll to bottom when new messages arrive
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.size) { index ->
                    val message = messages[index]
                    ChatBubble(
                        message = message,
                        avatarUri = uiState.avatarUri,
                        isSpeaking = uiState.speakingMessageIndex == index,
                        onSpeak = { text -> viewModel.toggleTts(text, index) },
                        onDelete = { viewModel.deleteMessage(it) },
                        messageIndex = index
                    )
                }
                // Thinking indicator
                if (uiState.isGenerating && !uiState.isStreaming) {
                    item {
                        Row(
                            modifier = Modifier.padding(start = 44.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Thinking...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.isStreaming && uiState.streamingText.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = uiState.streamingText,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            } else if (uiState.isGenerating) {
                // Non-streaming thinking indicator at bottom
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Thinking...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, avatarUri: String? = null, onSpeak: (String) -> Unit = {}, isSpeaking: Boolean = false, onDelete: ((Int) -> Unit)? = null, messageIndex: Int = -1) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val timeStr = remember(message.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(message.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Image(
                painter = if (!avatarUri.isNullOrBlank()) {
                    rememberAsyncImagePainter(model = android.net.Uri.parse(avatarUri))
                } else {
                    painterResource(id = com.baize.ai.R.drawable.baize_avatar)
                },
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = backgroundColor
            ) {
                Column {
                    // Display image if present
                    if (!message.imageUri.isNullOrBlank()) {
                        val imageUri = try {
                            if (message.imageUri.startsWith("data:")) {
                                // Base64 image - decode and display
                                null // Will use bitmap below
                            } else {
                                Uri.parse(message.imageUri)
                            }
                        } catch (e: Exception) { null }

                        if (message.imageUri.startsWith("data:")) {
                            // Decode base64 image
                            val base64Data = message.imageUri.substringAfter("base64,")
                            val bytes = try {
                                android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            } catch (e: Exception) { null }
                            if (bytes != null) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Attached image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .clip(MaterialTheme.shapes.medium),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        } else if (imageUri != null) {
                            Image(
                                painter = rememberAsyncImagePainter(model = imageUri),
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    SelectionContainer { Text(
                        text = message.content,
                        color = contentColor,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = timeStr,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (!isUser) {
                    IconButton(onClick = { onSpeak(message.content) }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                            contentDescription = "TTS",
                            modifier = Modifier.size(14.dp),
                            tint = if (isSpeaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    if (onDelete != null) {
                        var showDeleteDialog by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("Delete message?") },
                                text = { Text("This action cannot be undone.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        onDelete(messageIndex)
                                        showDeleteDialog = false
                                    }) {
                                        Text("Delete")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "You",
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    isListening: Boolean = false,
    holdToTalk: Boolean = false,
    onMicClick: () -> Unit = {},
    onMicPress: () -> Unit = {},
    onMicRelease: () -> Unit = {},
    onImageClick: () -> Unit = {},
    hasImage: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        when {
                            isListening && holdToTalk -> "松手结束识别..."
                            isListening -> "再说完可点麦克风结束..."
                            holdToTalk -> "按住说话..."
                            else -> "Say something..."
                        }
                    )
                },
                enabled = enabled,
                maxLines = 4,
                interactionSource = remember { MutableInteractionSource() }.also { interactionSource ->
                    LaunchedEffect(interactionSource) {
                        interactionSource.interactions.collect { interaction ->
                            if (interaction is PressInteraction.Release) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Image picker button
            IconButton(onClick = onImageClick, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Photo,
                    contentDescription = "Pick image",
                    tint = if (hasImage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            if (holdToTalk) {
                // 按住说话 / 松手结束
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            detectTapGestures(
                                onPress = {
                                    onMicPress()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        onMicRelease()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = if (isListening) "松开结束" else "按住说话",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                // 默认：点击开始 / 再点结束
                IconButton(onClick = onMicClick, enabled = enabled) {
                    Icon(
                        imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isListening) "结束语音输入" else "语音输入",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            val coroutineScope = rememberCoroutineScope()
            var canSend by remember { mutableStateOf(true) }
            Button(
                onClick = {
                    if (canSend) {
                        canSend = false
                        onSend()
                        coroutineScope.launch {
                            delay(500)
                            canSend = true
                        }
                    }
                },
                enabled = enabled && (value.isNotBlank() || hasImage) && canSend
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun TierSwitchButton(
    tierMode: TierMode,
    currentTier: SoulPromptBuilder.Tier,
    onToggleMode: () -> Unit,
    onSetTier: (SoulPromptBuilder.Tier) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = {
            if (tierMode == TierMode.AUTO) {
                onToggleMode()
            } else {
                expanded = true
            }
        }) {
            val tierText = if (tierMode == TierMode.AUTO) {
                "AUTO"
            } else {
                when (currentTier) {
                    SoulPromptBuilder.Tier.BASIC -> "Basic"
                    SoulPromptBuilder.Tier.STANDARD -> "Standard"
                    SoulPromptBuilder.Tier.FULL -> "Full"
                }
            }
            Text(
                text = tierText,
                fontSize = 12.sp,
                color = if (tierMode == TierMode.MANUAL) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Basic (fast)") },
                onClick = {
                    onSetTier(SoulPromptBuilder.Tier.BASIC)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Standard (balanced)") },
                onClick = {
                    onSetTier(SoulPromptBuilder.Tier.STANDARD)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Full (detailed)") },
                onClick = {
                    onSetTier(SoulPromptBuilder.Tier.FULL)
                    expanded = false
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Auto mode") },
                onClick = {
                    onToggleMode()
                    expanded = false
                }
            )
        }
    }
}











