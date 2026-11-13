package de.aikitori.tagwerk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import de.aikitori.tagwerk.ui.AppViewModel
import de.aikitori.tagwerk.ui.BatchScreen
import de.aikitori.tagwerk.ui.EditorScreen
import de.aikitori.tagwerk.ui.FolderScreen
import de.aikitori.tagwerk.ui.Screen
import de.aikitori.tagwerk.ui.TagwerkTheme
import kotlinx.coroutines.flow.filterNotNull

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TagwerkTheme {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    snapshotFlow { model.message }.filterNotNull().collect { text ->
                        model.message = null
                        snackbar.showSnackbar(text)
                    }
                }
                when (model.screen) {
                    Screen.Folder -> FolderScreen(model, snackbar)
                    Screen.Editor -> EditorScreen(model, snackbar)
                    Screen.Batch -> BatchScreen(model, snackbar)
                }
            }
        }
    }
}
