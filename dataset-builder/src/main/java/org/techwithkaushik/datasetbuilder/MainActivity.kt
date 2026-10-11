package org.techwithkaushik.datasetbuilder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var openedProject by remember { mutableStateOf<Pair<String, String>?>(null) }
                    val currentProject = openedProject
                    BackHandler(enabled = currentProject != null) { openedProject = null }
                    if (currentProject == null) {
                        DatasetHomeEntry(this) { projectId, projectName -> openedProject = projectId to projectName }
                    } else {
                        DatasetAnnotationScreen(
                            images = emptyList(),
                            onExit = { openedProject = null },
                            onMessage = { message ->
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            },
                            projectId = currentProject.first,
                            projectName = currentProject.second,
                        )
                    }
                }
            }
        }
    }
}
