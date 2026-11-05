package io.embodit.junglechess

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.junglechess.ui.AppRoot
import io.embodit.junglechess.ui.board.BoardView
import io.embodit.junglechess.ui.control.ControlScreen
import io.embodit.junglechess.ui.window.WindowCtl

/**
 * Skeleton A (design package container): the default Volumetric container is the board
 * (0.56 x 0.36 x 0.72 m, manifest), the control window is a Planar window placed to its right.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            AppRoot { BoardView() }
        }
        WindowContainer(
            WindowCtl.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 640.dp), // tallest page (settings) fits without scrolling (critic B1, template §6)
            placement = { containers.firstOrNull { it.id == WindowCtl.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            AppRoot {
                ControlScreen(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 600.dp, maxWidth = 900.dp, maxHeight = 640.dp))
            }
        }
    }
