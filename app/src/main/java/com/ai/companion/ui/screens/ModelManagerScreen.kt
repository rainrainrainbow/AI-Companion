package com.ai.companion.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.companion.core.llm.ModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    var localModels by remember { mutableStateOf(modelManager.getLocalModels()) }
    var scanResults by remember { mutableStateOf(listOf<ModelManager.ModelInfo>()) }
    var showScanResult by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadingModel by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val storageInfo = remember { modelManager.getStorageInfo() }

    // SAF 文件选择器：选择.gguf文件
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                statusMessage = "正在从文件选择器导入..."
                val success = modelManager.importModelFromUri(uri)
                if (success) {
                    localModels = modelManager.getLocalModels()
                    statusMessage = "✅ 从文件选择器导入成功！"
                } else {
                    statusMessage = "❌ 导入失败，文件可能已存在或无法读取"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            // 状态提示
            statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 存储信息
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("存储空间", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("已安装模型: ${storageInfo.modelCount} 个")
                    Text("占用空间: ${storageInfo.totalSize / (1024*1024)}MB")
                    Text("剩余空间: ${storageInfo.freeSpace / (1024*1024)}MB")

                    if (!modelManager.isNativeLibAvailable()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "⚠️ 原生库(libllama.so)未安装，模型无法运行",
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 操作按钮：扫描下载目录、文件选择器、下载模型
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scanResults = modelManager.scanDownloadDir()
                        showScanResult = true
                        if (scanResults.isEmpty()) {
                            statusMessage = "Download目录中未找到.gguf模型文件"
                        } else {
                            statusMessage = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("扫描下载", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("选择文件", fontSize = 13.sp)
                }

                Button(
                    onClick = { showDownloadDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载模型", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 扫描结果
            if (showScanResult && scanResults.isNotEmpty()) {
                Text("📁 Download目录中的模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                scanResults.forEach { model ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(model.sizeFormatted, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        statusMessage = "正在导入 ${model.name}..."
                                        val success = modelManager.importModel(model.path)
                                        if (success) {
                                            localModels = modelManager.getLocalModels()
                                            statusMessage = "✅ ${model.name} 导入成功！"
                                        } else {
                                            statusMessage = "❌ 导入失败"
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("导入", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 已安装模型列表
            Text("📦 已安装的模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            if (localModels.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Storage, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("请从Download目录导入或下载模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(localModels) { model ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (model.isLoaded)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        if (model.isLoaded) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text("当前", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        }
                                    }
                                    Text(model.sizeFormatted, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                if (!model.isLoaded) {
                                    TextButton(
                                        onClick = {
                                            modelManager.setCurrentModel(model.name)
                                            localModels = modelManager.getLocalModels()
                                            statusMessage = "✅ 已切换至 ${model.name}"
                                        }
                                    ) {
                                        Text("启用", fontSize = 12.sp)
                                    }
                                }

                                IconButton(
                                    onClick = { showDeleteConfirm = model.name },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 下载对话框
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("下载模型") },
            text = {
                Column {
                    Text("选择要下载的模型（需要WiFi连接）", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    ModelManager.RECOMMENDED_MODELS.forEach { source ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            onClick = {
                                showDownloadDialog = false
                                downloadingModel = source.name
                                scope.launch {
                                    val fileName = source.url.substringAfterLast("/")
                                    statusMessage = "正在下载 ${source.name}..."
                                    val success = modelManager.downloadModel(source.url, fileName) { progress ->
                                        downloadProgress = progress
                                    }
                                    if (success) {
                                        localModels = modelManager.getLocalModels()
                                        statusMessage = "✅ ${source.name} 下载完成！"
                                        modelManager.setCurrentModel(fileName)
                                    } else {
                                        statusMessage = "❌ 下载失败，请检查网络"
                                    }
                                    downloadingModel = null
                                    downloadProgress = 0f
                                }
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(source.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(source.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("大小: ${source.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 下载进度
    downloadingModel?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("下载中") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(downloadProgress * 100).toInt()}%", fontSize = 12.sp)
                }
            },
            confirmButton = {}
        )
    }

    // 删除确认
    showDeleteConfirm?.let { name ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除模型") },
            text = { Text("确定要删除 $name 吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        modelManager.deleteModel(name)
                        localModels = modelManager.getLocalModels()
                        statusMessage = "已删除 $name"
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}