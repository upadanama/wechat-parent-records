package com.jz.chatshot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jz.chatshot.ui.CaptureTab
import com.jz.chatshot.ui.RosterTab
import com.jz.chatshot.ui.SendTab

class MainActivity : ComponentActivity() {

    private val resumeTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme {
                ChatshotApp(resumeTick.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        A11y.ensureEnabled(this)
        resumeTick.value++
    }
}

@Composable
private fun ChatshotApp(tick: Int) {
    var tab by rememberSaveable { mutableStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("名单") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("采集") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("打包发送") })
            }
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> RosterTab()
                    1 -> CaptureTab(tick)
                    else -> SendTab()
                }
            }
        }
    }
}
