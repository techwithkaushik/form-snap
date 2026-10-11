package org.techwithkaushik.datasetbuilder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    DatasetAnnotationScreen(
                        images = emptyList(),
                        onExit = { finish() },
                        onMessage = { message ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
    }
}
