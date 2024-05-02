/*
 * Copyright 2023 Calvin Liang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.calvin.reorderable

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a [ReorderableLazyStaggeredGridState] that is remembered across compositions.
 *
 * Changes to [lazyStaggeredGridState], [scrollThresholdPadding], [scrollThreshold], and [scroller] will result in [ReorderableLazyStaggeredGridState] being updated.
 *
 * @param lazyStaggeredGridState The return value of [rememberLazyStaggeredGridState](androidx.compose.foundation.lazy.LazyStaggeredGridStateKt.rememberLazyStaggeredGridState)
 * @param scrollThresholdPadding The padding that will be added to the top and bottom, or start and end of the grid to determine the scrollThreshold. Useful for when the grid is displayed under the navigation bar or notification bar.
 * @param scrollThreshold The distance in dp from the top and bottom, or start and end of the grid that will trigger scrolling
 * @param scroller The [Scroller] that will be used to scroll the grid. Use [rememberScroller](sh.calvin.reorderable.ScrollerKt.rememberScroller) to create a [Scroller].
 * @param onMove The function that is called when an item is moved
 */
@Composable
fun rememberReorderableLazyStaggeredGridState(
    lazyStaggeredGridState: LazyStaggeredGridState,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    scrollThreshold: Dp = ReorderableLazyCollectionDefaults.ScrollThreshold,
    scroller: Scroller = rememberScroller(
        scrollableState = lazyStaggeredGridState,
        pixelAmount = lazyStaggeredGridState.layoutInfo.viewportSize.height * ScrollAmountMultiplier,
    ),
    onMove: (from: LazyStaggeredGridItemInfo, to: LazyStaggeredGridItemInfo) -> Unit,
): ReorderableLazyStaggeredGridState {
    val density = LocalDensity.current
    val scrollThresholdPx = with(density) { scrollThreshold.toPx() }

    val scope = rememberCoroutineScope()
    val onMoveState = rememberUpdatedState(onMove)
    val layoutDirection = LocalLayoutDirection.current
    val absoluteScrollThresholdPadding = AbsolutePixelPadding(
        start = with(density) {
            scrollThresholdPadding.calculateStartPadding(layoutDirection).toPx()
        },
        end = with(density) {
            scrollThresholdPadding.calculateEndPadding(layoutDirection).toPx()
        },
        top = with(density) { scrollThresholdPadding.calculateTopPadding().toPx() },
        bottom = with(density) { scrollThresholdPadding.calculateBottomPadding().toPx() },
    )
    val state = remember(
        scope, lazyStaggeredGridState, scrollThreshold, scrollThresholdPadding, scroller,
    ) {
        ReorderableLazyStaggeredGridState(
            state = lazyStaggeredGridState,
            scope = scope,
            onMoveState = onMoveState,
            scrollThreshold = scrollThresholdPx,
            scrollThresholdPadding = absoluteScrollThresholdPadding,
            scroller = scroller,
        )
    }
    return state
}

private fun LazyStaggeredGridItemInfo.toLazyCollectionItemInfo() =
    object : LazyCollectionItemInfo<LazyStaggeredGridItemInfo> {
        override val index: Int
            get() = this@toLazyCollectionItemInfo.index
        override val key: Any
            get() = this@toLazyCollectionItemInfo.key
        override val offset: IntOffset
            get() = this@toLazyCollectionItemInfo.offset
        override val size: IntSize
            get() = this@toLazyCollectionItemInfo.size
        override val data: LazyStaggeredGridItemInfo
            get() = this@toLazyCollectionItemInfo

    }

private fun LazyStaggeredGridLayoutInfo.toLazyCollectionLayoutInfo() =
    object : LazyCollectionLayoutInfo<LazyStaggeredGridItemInfo> {
        override val visibleItemsInfo: List<LazyCollectionItemInfo<LazyStaggeredGridItemInfo>>
            get() = this@toLazyCollectionLayoutInfo.visibleItemsInfo.map {
                it.toLazyCollectionItemInfo()
            }
        override val viewportSize: IntSize
            get() = this@toLazyCollectionLayoutInfo.viewportSize
        override val orientation: Orientation
            get() = this@toLazyCollectionLayoutInfo.orientation
        override val reverseLayout: Boolean = false
        override val beforeContentPadding: Int
            get() = this@toLazyCollectionLayoutInfo.beforeContentPadding

    }

private fun LazyStaggeredGridState.toLazyCollectionState() =
    object : LazyCollectionState<LazyStaggeredGridItemInfo> {
        override val firstVisibleItemIndex: Int
            get() = this@toLazyCollectionState.firstVisibleItemIndex
        override val firstVisibleItemScrollOffset: Int
            get() = this@toLazyCollectionState.firstVisibleItemScrollOffset
        override val layoutInfo: LazyCollectionLayoutInfo<LazyStaggeredGridItemInfo>
            get() = this@toLazyCollectionState.layoutInfo.toLazyCollectionLayoutInfo()

        override suspend fun animateScrollBy(value: Float, animationSpec: AnimationSpec<Float>) =
            this@toLazyCollectionState.animateScrollBy(value, animationSpec)

        override suspend fun scrollToItem(scrollToIndex: Int, firstVisibleItemScrollOffset: Int) =
            this@toLazyCollectionState.scrollToItem(scrollToIndex, firstVisibleItemScrollOffset)
    }

class ReorderableLazyStaggeredGridState internal constructor(
    state: LazyStaggeredGridState,
    scope: CoroutineScope,
    onMoveState: State<(from: LazyStaggeredGridItemInfo, to: LazyStaggeredGridItemInfo) -> Unit>,

    /**
     * The threshold in pixels for scrolling the grid when dragging an item.
     * If the dragged item is within this threshold of the top or bottom of the grid, the grid will scroll.
     * Must be greater than 0.
     */
    scrollThreshold: Float,
    scrollThresholdPadding: AbsolutePixelPadding,
    scroller: Scroller,
) : ReorderableLazyCollectionState<LazyStaggeredGridItemInfo>(
    state.toLazyCollectionState(),
    scope,
    onMoveState,
    scrollThreshold,
    scrollThresholdPadding,
    scroller
)

/**
 * A composable that allows an item in LazyVerticalStaggeredGrid or LazyHorizontalStaggeredGrid to be reordered by dragging.
 *
 * @param state The return value of [rememberReorderableLazyStaggeredGridState]
 * @param key The key of the item, must be the same as the key passed to [LazyStaggeredGridScope.item](androidx.compose.foundation.lazy.staggeredgrid.item), [LazyStaggeredGridScope.items](androidx.compose.foundation.lazy.staggeredgrid.items) or similar functions in [LazyStaggeredGridScope](androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope)
 * @param enabled Whether or this item is reorderable
 */
@ExperimentalFoundationApi
@Composable
fun LazyStaggeredGridItemScope.ReorderableItem(
    state: ReorderableLazyStaggeredGridState,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    val dragging by state.isItemDragging(key)
    val offsetModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingItemOffset.y
                translationX = state.draggingItemOffset.x
            }
    } else if (key == state.previousDraggingItemKey) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY =
                    state.previousDraggingItemOffset.value.y
                translationX =
                    state.previousDraggingItemOffset.value.x
            }
    } else {
        Modifier.animateItemPlacement()
    }

    ReorderableCollectionItem(
        state = state,
        key = key,
        modifier = modifier.then(offsetModifier),
        enabled = enabled,
        dragging = dragging,
        content = content,
    )
}