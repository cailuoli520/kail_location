package com.kail.locationxposed

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kail.locationxposed.ui.theme.KailLocationXposedTheme
import com.kail.locationxposed.xposed.utils.ShellUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KailLocationXposedTheme {
                MainScreen()
            }
        }
    }
}

private fun isRootHidden(): Boolean {
    return ShellUtils.hasRoot() &&
            !ShellUtils.executeCommand("pm list packages com.kail.locationxposed")
                .contains("package:com.kail.locationxposed")
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val hasRoot = remember { ShellUtils.hasRoot() }
    var rootHidden by remember { mutableStateOf(isRootHidden()) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "KailLocationXposed",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Root 深度隐藏",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (rootHidden) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (rootHidden) "已隐藏" else "未隐藏",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (rootHidden) {
                            "整个应用已从桌面、抽屉、系统设置全部消失，LSPosed 钩子仍然生效"
                        } else {
                            "隐藏后桌面、抽屉、系统设置全部看不到，仅 LSPosed 钩子生效"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = hasRoot && !rootHidden,
                onClick = {
                    ShellUtils.executeCommand("pm hide com.kail.locationxposed")
                    if (isRootHidden()) {
                        rootHidden = true
                        Toast.makeText(
                            context,
                            "已彻底隐藏。模块钩子继续生效，此界面不再可用\n恢复：adb shell pm unhide com.kail.locationxposed",
                            Toast.LENGTH_LONG
                        ).show()
                        (context as? Activity)?.finishAffinity()
                    } else {
                        Toast.makeText(context, "隐藏失败：需要 root 且 pm hide 执行失败", Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!hasRoot) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            ) {
                Text(
                    text = if (!hasRoot) {
                        "无 Root 权限，深度隐藏不可用"
                    } else if (rootHidden) {
                        "已隐藏（恢复需 adb）"
                    } else {
                        "Root 深度隐藏整个应用"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = "恢复：adb shell pm unhide com.kail.locationxposed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    KailLocationXposedTheme {
        MainScreen()
    }
}
