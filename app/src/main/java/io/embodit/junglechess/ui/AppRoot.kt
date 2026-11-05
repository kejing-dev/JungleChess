package io.embodit.junglechess.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.embodit.junglechess.platform.Graph
import io.embodit.junglechess.ui.theme.JungleTheme

/** Theme of every window, following the in-app language setting. */
@Composable
fun AppRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.controller.state.collectAsStateWithLifecycle()
    JungleTheme(language = state.settings.language, content = content)
}
