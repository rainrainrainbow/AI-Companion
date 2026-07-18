package com.ai.companion.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.companion.core.llm.ChatMessage
import com.ai.companion.core.llm.Role
import com.ai.companion.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ))
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO
            ))
        }
        viewModel.loadModel()
        viewModel.startCompanionService(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("星璃", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        // 情绪指示器
                        Surface(
                            shape = CircleShape,
                            color = getEmotionColor(uiState.emotion),
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = getEmotionText(uiState.emotion),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 微调按钮
                    IconButton(onClick = { viewModel.startFineTune(context) }) {
                        Icon(Icons.Default.Tune, "微调模型")
                    }
                    // 设置
                    IconButton(onClick = { viewModel.toggleSettings() }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                    // 后台服务开关
                    IconButton(onClick = {
                        viewModel.toggleCompanionService(context)
                    }) {
                        Icon(
                            if (uiState.serviceRunning) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            "后台服务"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // 底部输入栏
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    // 模型加载状态
                    if (!uiState.modelLoaded) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 语音按钮
                        FilledIconButton(
                            onClick = {
                                if (uiState.isListening) viewModel.stopVoiceInput()
                                else viewModel.startVoiceInput()
                            },
                            modifier = Modifier.size(40.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (uiState.isListening)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Icon(
                                if (uiState.isListening) Icons.Default.Mic else Icons.Default.MicNone,
                                "语音输入"
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // 文本输入
                        OutlinedTextField(
                            value = uiState.inputText,
                            onValueChange = { viewModel.updateInput(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("和星璃聊天...") },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    viewModel.sendMessage()
                                }
                            ),
                            singleLine = true
                        )

                        Spacer(Modifier.width(8.dp))

                        // 发送按钮
                        FilledIconButton(
                            onClick = { viewModel.sendMessage() },
                            modifier = Modifier.size(40.dp),
                            enabled = uiState.inputText.isNotBlank() && !uiState.isThinking,
                            colors = IconButtonDefaults.filledIconButtonColors()
                        ) {
                            Icon(Icons.Default.Send, "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 3D虚拟形象区域
            if (uiState.showAvatar) {
                AvatarView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    emotion = uiState.emotion,
                    isSpeaking = uiState.isSpeaking
                )
            }

            // 聊天消息列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = if (uiState.showAvatar) 280.dp else 0.dp,
                        bottom = 8.dp
                    ),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { msg ->
                    ChatBubble(message = msg)
                }

                // 思考中指示器
                if (uiState.isThinking) {
                    item {
                        ThinkingIndicator()
                    }
                }
            }

            // 设置面板
            if (uiState.showSettings) {
                SettingsPanel(
                    viewModel = viewModel,
                    onDismiss = { viewModel.toggleSettings() }
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    val alignment = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
fun AvatarView(
    modifier: Modifier = Modifier,
    emotion: String,
    isSpeaking: Boolean
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 3D渲染区域 - 使用AndroidView嵌入Filament
            AndroidView(
                factory = { ctx ->
                    android.opengl.GLSurfaceView(ctx).apply {
                        // 这里由Avatar3DEngine初始化
                        setEGLContextClientVersion(3)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 占位: 当3D模型未加载时显示表情符号
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = getEmotionEmoji(emotion),
                    fontSize = 72.sp
                )
                if (isSpeaking) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在说话...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设置", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }

            Spacer(Modifier.height(24.dp))

            // 伴侣信息
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("星璃 - 你的AI伴侣", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("状态: ${if (uiState.modelLoaded) "已就绪" else "加载中..."}")
                    Text("情绪: ${getEmotionText(uiState.emotion)}")
                    Text("亲密度: ${uiState.intimacyLevel}/100")
                    Text("今日互动: ${uiState.todayInteractions}次")
                    Text("反馈缓存: ${uiState.feedbackCount}/${uiState.feedbackThreshold}")
                }
            }

            Spacer(Modifier.height(16.dp))

            // 设置选项
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 3D头像开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("显示3D虚拟形象")
                        Switch(
                            checked = uiState.showAvatar,
                            onCheckedChange = { viewModel.toggleAvatar() }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // TTS开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("语音回复")
                        Switch(
                            checked = uiState.ttsEnabled,
                            onCheckedChange = { viewModel.toggleTTS() }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 自动微调
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("自动夜间微调")
                            Text(
                                "达到${uiState.feedbackThreshold}条反馈后自动在夜间执行",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.autoFineTune,
                            onCheckedChange = { viewModel.toggleAutoFineTune() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.startFineTune(context = LocalContext.current) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text("立即微调模型 (${uiState.feedbackCount}条反馈)")
            }
        }
    }
}

// === 辅助函数 ===

fun getEmotionColor(emotion: String): Color {
    return when (emotion) {
        "HAPPY" -> Color(0xFF4CAF50)
        "SAD" -> Color(0xFF42A5F5)
        "ANGRY" -> Color(0xFFEF5350)
        "TIRED" -> Color(0xFFAB47BC)
        "ANXIOUS" -> Color(0xFFFFA726)
        "LOVING" -> Color(0xFFEC407A)
        else -> Color(0xFF9E9E9E)
    }
}

fun getEmotionText(emotion: String): String {
    return when (emotion) {
        "HAPPY" -> "开心"
        "SAD" -> "难过"
        "ANGRY" -> "有点生气"
        "TIRED" -> "累了"
        "ANXIOUS" -> "焦虑"
        "LOVING" -> "温暖"
        else -> "平静"
    }
}

fun getEmotionEmoji(emotion: String): String {
    return when (emotion) {
        "HAPPY" -> "😊"
        "SAD" -> "😢"
        "ANGRY" -> "😤"
        "TIRED" -> "😴"
        "ANXIOUS" -> "😰"
        "LOVING" -> "🥰"
        else -> "😌"
    }
}