package dev.cannoli.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun ListScrollEffect(
    listState: LazyListState,
    selectedIndex: Int,
    itemCount: Int,
    scrollTarget: Int = -1,
    reorderMode: Boolean = false,
) {
    LaunchedEffect(itemCount, scrollTarget) {
        if (itemCount > 0 && scrollTarget >= 0) {
            val target = scrollTarget.coerceIn(0, itemCount - 1)
            if (listState.firstVisibleItemIndex != target) {
                listState.scrollToItem(target)
            }
        }
    }

    LaunchedEffect(selectedIndex, itemCount) {
        if (itemCount == 0) return@LaunchedEffect
        val index = selectedIndex.coerceAtLeast(0).coerceAtMost(itemCount - 1)
        // visibleItemsInfo can be empty mid-recomposition; under fast auto-repeat (80ms)
        // the empty window arrives often enough to leave the selected row off-screen.
        // Fall back to an unconditional scrollToItem so the selection never drifts out of view.
        if (listState.layoutInfo.visibleItemsInfo.isEmpty()) {
            listState.scrollToItem(index)
            return@LaunchedEffect
        }
        val viewportHeight = listState.layoutInfo.viewportEndOffset
        val fullyVisible = listState.layoutInfo.visibleItemsInfo.filter { info ->
            info.offset >= 0 && info.offset + info.size <= viewportHeight
        }

        if (fullyVisible.size >= itemCount) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val fullyVisibleCount = fullyVisible.size.coerceAtLeast(1)
        val firstFullyVisible = fullyVisible.firstOrNull()?.index ?: 0
        val lastFullyVisible = fullyVisible.lastOrNull()?.index ?: 0

        if (index < firstFullyVisible) {
            listState.scrollToItem(index)
        } else if (index > lastFullyVisible) {
            val targetFirst = (index - fullyVisibleCount + 1).coerceAtLeast(0)
            listState.scrollToItem(targetFirst)
        }
    }
}
