package org.techwithkaushik.datasetbuilder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var openedProject by remember { mutableStateOf<String?>(null) }
                    if (openedProject == null) {
                        DatasetHomeEntry(this) { projectId -> openedProject = projectId }
                    } else {
                        DatasetAnnotationScreen(
                            images = emptyList(),
                            onExit = { openedProject = null },
                            onMessage = { message ->
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                }
            }
        }
    }
}
