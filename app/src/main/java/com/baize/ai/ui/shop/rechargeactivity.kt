package com.baize.ai.ui.shop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * RechargeActivity — 充值页面
 * 
 * 5 档充值：体验装 ¥1 / 基础装 ¥3 / 进阶装 ¥10 / 豪华装 ¥30 / 至尊装 ¥98
 */
class RechargeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RechargeScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(
    onBack: () -> Unit,
    viewModel: ShopViewModel = viewModel()
) {
    val tiers by viewModel.tiers.collectAsState()
    val gems by viewModel.gems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val purchaseResult by viewModel.purchaseResult.collectAsState()
    var selectedTier by remember { mutableStateOf<String?>(null) }
    var showPayDialog by remember { mutableStateOf(false) }

    // Show result dialog
    purchaseResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.clearPurchaseResult() },
            title = { Text(if (result.success) "成功" else "失败") },
            text = { Text(result.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearPurchaseResult() }) {
                    Text("确定")
                }
            }
        )
    }

    // Pay method dialog
    if (showPayDialog && selectedTier != null) {
        AlertDialog(
            onDismissRequest = { showPayDialog = false },
            title = { Text("选择支付方式") },
            text = { Text("为「$selectedTier」充值") },
            confirmButton = {
                TextButton(onClick = {
                    showPayDialog = false
                    viewModel.recharge(selectedTier!!, "alipay")
                }) {
                    Text("支付宝")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPayDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("充值") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Balance display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💎", fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("$gems", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("宝石", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("选择充值档位", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            // Tier list
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tiers) { tier ->
                    val isSelected = selectedTier == tier.name
                    val shape = RoundedCornerShape(12.dp)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = shape
                            )
                            .clickable {
                                selectedTier = tier.name
                                showPayDialog = true
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tier.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "¥${tier.priceCents / 100}  →  ${tier.totalGems}💎",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                if (tier.bonusGems > 0) {
                                    Text(
                                        text = "含赠送 ${tier.bonusGems}💎",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "已选",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
