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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object ReorderableLazyCollectionDefaults {
    val ScrollThreshold = 48.dp
}

internal const val ScrollAmountMultiplier = 0.05f

internal data class AbsolutePixelPadding(
    val start: Float,
    val end: Float,
    val top: Float,
    val bottom: Float,
) {
    companion object {
        @Composable
        fun fromPaddingValues(paddingValues: PaddingValues): AbsolutePixelPadding {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current

            return AbsolutePixelPadding(
                start = with(density) {
                    paddingValues.calculateStartPadding(layoutDirection).toPx()
                },
                end = with(density) { paddingValues.calculateEndPadding(layoutDirection).toPx() },
                top = with(density) { paddingValues.calculateTopPadding().toPx() },
                bottom = with(density) { paddingValues.calculateBottomPadding().toPx() },
            )
        }
    }
}

internal interface LazyCollectionItemInfo<out T> {
    val index: Int
    val key: Any
    val offset: IntOffset
    val size: IntSize
    val data: T

    val center: IntOffset
        get() = IntOffset(offset.x + size.width / 2, offset.y + size.height / 2)
}

internal data class CollectionScrollPadding(
    val start: Float,
    val end: Float,
) {
    companion object {
        fun fromAbsolutePixelPadding(
            orientation: Orientation,
            padding: AbsolutePixelPadding,
            reverseLayout: Boolean,
        ): CollectionScrollPadding {
            return when (orientation) {
                Orientation.Vertical -> CollectionScrollPadding(
                    start = padding.top,
                    end = padding.bottom,
                )

                Orientation.Horizontal -> CollectionScrollPadding(
                    start = padding.start,
                    end = padding.end,
                )
            }.let {
                when (reverseLayout) {
                    true -> CollectionScrollPadding(
                        start = it.end,
                        end = it.start,
                    )

                    false -> it
                }
            }
        }
    }
}

/**
 * The offsets in the main axis from the start of the content to the
 * start and end of the content minus the padding.
 */
internal data class ScrollAreaOffsets(
    val start: Float,
    val end: Float,
)

internal interface LazyCollectionLayoutInfo<out T> {
    val visibleItemsInfo: List<LazyCollectionItemInfo<T>>
    val viewportSize: IntSize
    val orientation: Orientation
    val reverseLayout: Boolean
    val beforeContentPadding: Int

    fun getScrollAreaOffsets(
        padding: AbsolutePixelPadding,
    ) = getScrollAreaOffsets(
        CollectionScrollPadding.fromAbsolutePixelPadding(
            orientation,
            padding,
            reverseLayout,
        )
    )

    fun getScrollAreaOffsets(padding: CollectionScrollPadding): ScrollAreaOffsets {
        val (startPadding, endPadding) = padding
        val contentEndOffset = when (orientation) {
            Orientation.Vertical -> viewportSize.height
            Orientation.Horizontal -> viewportSize.width
        } - endPadding

        return ScrollAreaOffsets(
            start = startPadding,
            end = contentEndOffset,
        )
    }

    fun getItemsInContentArea(padding: AbsolutePixelPadding) = getItemsInContentArea(
        CollectionScrollPadding.fromAbsolutePixelPadding(
            orientation,
            padding,
            reverseLayout,
        )
    )

    fun getItemsInContentArea(padding: CollectionScrollPadding): List<LazyCollectionItemInfo<T>> {
        val (contentStartOffset, contentEndOffset) = getScrollAreaOffsets(
            padding
        )

        return when (orientation) {
            Orientation.Vertical -> {
                visibleItemsInfo.filter { item ->
                    item.offset.y >= contentStartOffset && item.offset.y + item.size.height < contentEndOffset
                }
            }

            Orientation.Horizontal -> {
                visibleItemsInfo.filter { item ->
                    item.offset.x >= contentStartOffset && item.offset.x + item.size.width < contentEndOffset
                }
            }
        }
    }
}

internal interface LazyCollectionState<out T> {
    val firstVisibleItemIndex: Int
    val firstVisibleItemScrollOffset: Int
    val layoutInfo: LazyCollectionLayoutInfo<T>

    suspend fun animateScrollBy(
        value: Float,
        animationSpec: AnimationSpec<Float> = spring(),
    ): Float

    suspend fun scrollToItem(scrollToIndex: Int, firstVisibleItemScrollOffset: Int)
}

interface ReorderableLazyCollectionStateInterface {
    val isAnyItemDragging: Boolean
}

// base on https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation/integration-tests/foundation-demos/src/main/java/androidx/compose/foundation/demos/LazyColumnDragAndDropDemo.kt;drc=edde6e8b9d304264598f962a3b0e5c267e1373bb
// and https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation/integration-tests/foundation-demos/src/main/java/androidx/compose/foundation/demos/LazyGridDragAndDropDemo.kt;drc=44e4233f7bc0290a1509ef2d448f1309eb63248f
@Stable
open class ReorderableLazyCollectionState<out T> internal constructor(
    private val state: LazyCollectionState<T>,
    private val scope: CoroutineScope,
    private val onMoveState: State<(from: T, to: T) -> Unit>,

    /**
     * The threshold in pixels for scrolling the list when dragging an item.
     * If the dragged item is within this threshold of the top or bottom of the list, the list will scroll.
     * Must be greater than 0.
     */
    private val scrollThreshold: Float,
    private val scrollThresholdPadding: AbsolutePixelPadding,
    private val scroller: Scroller,

    /**
     * A function that determines whether the `draggingItem` should be swapped with the `item`.
     * Given their bounding rectangles, return `true` if they should be swapped.
     * The default implementation is to swap if the dragging item's bounding rectangle has crossed the center of the item's bounding rectangle.
     */
    private val shouldItemSwap: (draggingItem: Rect, item: Rect) -> Boolean = { draggingItem, item ->
        draggingItem.contains(item.center)
    },
) : ReorderableLazyCollectionStateInterface {
    internal val orientation: Orientation
        get() = state.layoutInfo.orientation

    private var draggingItemKey by mutableStateOf<Any?>(null)
    private val draggingItemIndex: Int?
        get() = draggingItemLayoutInfo?.index

    /**
     * Whether any item is being dragged. This property is observable.
     */
    override val isAnyItemDragging by derivedStateOf {
        draggingItemKey != null
    }

    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(IntOffset.Zero)

    private var draggingItemTargetIndex: Int? = null
    private val draggingItemLayoutInfo: LazyCollectionItemInfo<T>?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingItemKey }
    internal val draggingItemOffset: Offset
        get() = (draggingItemLayoutInfo?.let {
            draggingItemDraggedDelta +
                    (draggingItemInitialOffset.toOffset() - it.offset.toOffset())
                        .reverseAxisIfNecessary()
        }) ?: Offset.Zero

    // the offset of the handle center from the top left of the dragging item when dragging starts
    private var draggingItemHandleOffset = Offset.Zero

    internal val reorderableKeys = HashSet<Any?>()

    internal var previousDraggingItemKey by mutableStateOf<Any?>(null)
        private set
    internal var previousDraggingItemOffset = Animatable(Offset.Zero, Offset.VectorConverter)
        private set

    private fun Offset.reverseAxisIfNecessary() = when (state.layoutInfo.reverseLayout) {
        true -> reverseAxis(orientation)
        false -> this
    }

    internal suspend fun onDragStart(key: Any, handleOffset: Offset) {
        state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key == key
        }?.also {
            val mainAxisOffset = it.offset.getAxis(orientation)
            if (mainAxisOffset < 0) {
                // if item is not fully in view, scroll to it
                state.animateScrollBy(mainAxisOffset.toFloat(), spring())
            }

            draggingItemKey = key
            draggingItemInitialOffset = it.offset
            draggingItemHandleOffset = handleOffset
        }
    }

    internal fun onDragStop() {
        if (draggingItemIndex != null) {
            previousDraggingItemKey = draggingItemKey
            val startOffset = draggingItemOffset
            scope.launch {
                previousDraggingItemOffset.snapTo(startOffset)
                previousDraggingItemOffset.animateTo(
                    Offset.Zero,
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = Offset.VisibilityThreshold
                    )
                )
                previousDraggingItemKey = null
            }
        }
        draggingItemDraggedDelta = Offset.Zero
        draggingItemKey = null
        draggingItemInitialOffset = IntOffset.Zero
        scroller.stop()
        draggingItemTargetIndex = null
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset.toOffset() +
                draggingItemOffset.reverseAxisIfNecessary()
        val endOffset = startOffset + draggingItem.size.toSize()
        val (contentStartOffset, contentEndOffset) = state.layoutInfo.getScrollAreaOffsets(
            scrollThresholdPadding
        )

        if (!scroller.isScrolling) {
            val draggingItemRect = Rect(startOffset, endOffset)
            // find a target item to swap with
            val targetItem =
                state.layoutInfo.getItemsInContentArea(scrollThresholdPadding).find { item ->
                    val targetItemRect = Rect(item.offset.toOffset(), item.size.toSize())

                    shouldItemSwap(draggingItemRect, targetItemRect)
                            && draggingItem.index != item.index
                            && item.key in reorderableKeys
                }
            if (targetItem != null) {
                swapItems(draggingItem, targetItem)
            }
        }

        // the distance from the top or left of the list to the center of the dragging item handle
        val handleOffset = when (state.layoutInfo.reverseLayout) {
            true -> endOffset - draggingItemHandleOffset
            false -> startOffset + draggingItemHandleOffset
        } + IntOffset.fromAxis(
            orientation,
            state.layoutInfo.beforeContentPadding
        ).toOffset()

        // check if the handle center is in the scroll threshold
        val distanceFromStart = handleOffset.getAxis(orientation) - contentStartOffset
        val distanceFromEnd = contentEndOffset - handleOffset.getAxis(orientation)

        if (distanceFromStart < scrollThreshold) {
            scroller.start(
                Scroller.Direction.BACKWARD,
                getScrollSpeedMultiplier(distanceFromStart),
                onScroll = {
                    swapDraggingItemToEndIfNecessary(Scroller.Direction.BACKWARD)
                }
            )
        } else if (distanceFromEnd < scrollThreshold) {
            scroller.start(
                Scroller.Direction.FORWARD,
                getScrollSpeedMultiplier(distanceFromEnd),
                onScroll = {
                    swapDraggingItemToEndIfNecessary(Scroller.Direction.FORWARD)
                }
            )
        } else {
            scroller.stop()
        }
    }

    // keep dragging item in visible area to prevent it from disappearing
    private fun swapDraggingItemToEndIfNecessary(
        direction: Scroller.Direction,
    ) {
        val draggingItem = draggingItemLayoutInfo ?: return
        val itemsInContentArea = state.layoutInfo.getItemsInContentArea(
            scrollThresholdPadding
        )
        val draggingItemIsAtTheEnd = when (direction) {
            Scroller.Direction.BACKWARD -> itemsInContentArea.firstOrNull()?.index?.let { draggingItem.index < it }
            Scroller.Direction.FORWARD -> itemsInContentArea.lastOrNull()?.index?.let { draggingItem.index > it }
        } ?: false

        if (draggingItemIsAtTheEnd) return

        val targetItem = when (direction) {
            Scroller.Direction.BACKWARD -> itemsInContentArea.find { it.key in reorderableKeys }
            Scroller.Direction.FORWARD -> itemsInContentArea.findLast { it.key in reorderableKeys }
        }
        if (targetItem != null && targetItem.index != draggingItem.index) {
            swapItems(draggingItem, targetItem)
        }
    }


    private fun swapItems(
        draggingItem: LazyCollectionItemInfo<T>,
        targetItem: LazyCollectionItemInfo<T>,
    ) {
        if (draggingItem.index == targetItem.index) return

        draggingItemTargetIndex = targetItem.index

        // TODO: when `requestScrollToItem` is released uncomment this and remove `scrollToIndex` and the following `if` block
        // see https://android-review.googlesource.com/c/platform/frameworks/support/+/2987293
        // see https://github.com/Calvin-LL/Reorderable/issues/4
//        if (
//            draggingItem.index == state.firstVisibleItemIndex ||
//            targetItem.index == state.firstVisibleItemIndex
//        ) {
//            state.requestScrollToItem(
//                state.firstVisibleItemIndex,
//                state.firstVisibleItemScrollOffset
//            )
//        }
//
//        onMoveState.value(draggingItem, targetItem)

        val scrollToIndex = if (targetItem.index == state.firstVisibleItemIndex) {
            draggingItem.index
        } else if (draggingItem.index == state.firstVisibleItemIndex) {
            targetItem.index
        } else {
            null
        }
        if (scrollToIndex != null) {
            scope.launch {
                // this is needed to neutralize automatic keeping the first item first.
                // see https://github.com/Calvin-LL/Reorderable/issues/4
                state.scrollToItem(scrollToIndex, state.firstVisibleItemScrollOffset)
                onMoveState.value(draggingItem.data, targetItem.data)
            }
        } else {
            onMoveState.value(draggingItem.data, targetItem.data)
        }
    }

    internal fun isItemDragging(key: Any): State<Boolean> {
        return derivedStateOf {
            key == draggingItemKey
        }
    }

    private fun getScrollSpeedMultiplier(distance: Float): Float {
        // map distance in scrollThreshold..-scrollThreshold to 1..10
        return (1 - ((distance + scrollThreshold) / (scrollThreshold * 2)).coerceIn(
            0f,
            1f
        )) * 10
    }
}

interface ReorderableCollectionItemScope {
    /**
     * Make the UI element the draggable handle for the reorderable item.
     *
     * This modifier can only be used on the UI element that is a child of [ReorderableItem].
     *
     * @param enabled Whether or not drag is enabled
     * @param interactionSource [MutableInteractionSource] that will be used to emit [DragInteraction.Start] when this draggable is being dragged.
     * @param onDragStarted The function that is called when the item starts being dragged
     * @param onDragStopped The function that is called when the item stops being dragged
     */
    fun Modifier.draggableHandle(
        enabled: Boolean = true,
        interactionSource: MutableInteractionSource? = null,
        onDragStarted: (startedPosition: Offset) -> Unit = {},
        onDragStopped: () -> Unit = {},
    ): Modifier

    /**
     * Make the UI element the draggable handle for the reorderable item. Drag will start only after a long press.
     *
     * This modifier can only be used on the UI element that is a child of [ReorderableItem].
     *
     * @param enabled Whether or not drag is enabled
     * @param interactionSource [MutableInteractionSource] that will be used to emit [DragInteraction.Start] when this draggable is being dragged.
     * @param onDragStarted The function that is called when the item starts being dragged
     * @param onDragStopped The function that is called when the item stops being dragged
     */
    fun Modifier.longPressDraggableHandle(
        enabled: Boolean = true,
        interactionSource: MutableInteractionSource? = null,
        onDragStarted: (startedPosition: Offset) -> Unit = {},
        onDragStopped: () -> Unit = {},
    ): Modifier
}

internal class ReorderableCollectionItemScopeImpl(
    private val reorderableLazyCollectionState: ReorderableLazyCollectionState<*>,
    private val key: Any,
    private val itemPositionProvider: () -> Offset,
) : ReorderableCollectionItemScope {
    /**
     * Make the UI element the draggable handle for the reorderable item.
     *
     * @param enabled Whether or not drag is enabled
     * @param interactionSource [MutableInteractionSource] that will be used to emit [DragInteraction.Start] when this draggable is being dragged.
     * @param onDragStarted The function that is called when the item starts being dragged
     * @param onDragStopped The function that is called when the item stops being dragged
     */
    override fun Modifier.draggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
    ) = composed {
        var handleOffset by remember { mutableStateOf(Offset.Zero) }
        var handleSize by remember { mutableStateOf(IntSize.Zero) }

        val coroutineScope = rememberCoroutineScope()

        onGloballyPositioned {
            handleOffset = it.positionInRoot()
            handleSize = it.size
        }.draggable(
            key1 = reorderableLazyCollectionState,
            enabled = enabled && (reorderableLazyCollectionState.isItemDragging(key).value || !reorderableLazyCollectionState.isAnyItemDragging),
            interactionSource = interactionSource,
            onDragStarted = {
                coroutineScope.launch {
                    val handleOffsetRelativeToItem = handleOffset - itemPositionProvider()
                    val handleCenter = Offset(
                        handleOffsetRelativeToItem.x + handleSize.width / 2f,
                        handleOffsetRelativeToItem.y + handleSize.height / 2f
                    )

                    reorderableLazyCollectionState.onDragStart(key, handleCenter)
                }
                onDragStarted(it)
            },
            onDragStopped = {
                reorderableLazyCollectionState.onDragStop()
                onDragStopped()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                reorderableLazyCollectionState.onDrag(dragAmount)
            },
        )
    }

    /**
     * Make the UI element the draggable handle for the reorderable item. Drag will start only after a long press.
     *
     * @param enabled Whether or not drag is enabled
     * @param interactionSource [MutableInteractionSource] that will be used to emit [DragInteraction.Start] when this draggable is being dragged
     * @param onDragStarted The function that is called when the item starts being dragged
     * @param onDragStopped The function that is called when the item stops being dragged
     */
    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
    ) = composed {
        var handleOffset by remember { mutableStateOf(Offset.Zero) }
        var handleSize by remember { mutableStateOf(IntSize.Zero) }

        val coroutineScope = rememberCoroutineScope()

        onGloballyPositioned {
            handleOffset = it.positionInRoot()
            handleSize = it.size
        }.longPressDraggable(
            key1 = reorderableLazyCollectionState,
            enabled = enabled && (reorderableLazyCollectionState.isItemDragging(key).value || !reorderableLazyCollectionState.isAnyItemDragging),
            interactionSource = interactionSource,
            onDragStarted = {
                coroutineScope.launch {
                    val handleOffsetRelativeToItem = handleOffset - itemPositionProvider()
                    val handleCenter = Offset(
                        handleOffsetRelativeToItem.x + handleSize.width / 2f,
                        handleOffsetRelativeToItem.y + handleSize.height / 2f
                    )

                    reorderableLazyCollectionState.onDragStart(key, handleCenter)
                }
                onDragStarted(it)
            },
            onDragStopped = {
                reorderableLazyCollectionState.onDragStop()
                onDragStopped()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                reorderableLazyCollectionState.onDrag(dragAmount)
            },
        )
    }
}

/**
 * A composable that allows items to be reordered by dragging.
 *
 * @param state The return value of [rememberReorderableLazyCollectionState]
 * @param key The key of the item, must be the same as the key passed to the parent composable
 * @param enabled Whether or this item is reorderable
 * @param dragging Whether or not this item is currently being dragged
 */
@ExperimentalFoundationApi
@Composable
internal fun ReorderableCollectionItem(
    state: ReorderableLazyCollectionState<*>,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dragging: Boolean,
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    var itemPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier.onGloballyPositioned {
            itemPosition = it.positionInRoot()
        }
    ) {
        ReorderableCollectionItemScopeImpl(
            reorderableLazyCollectionState = state,
            key = key,
            itemPositionProvider = { itemPosition },
        ).content(dragging)
    }

    LaunchedEffect(state.reorderableKeys, enabled) {
        if (enabled) {
            state.reorderableKeys.add(key)
        } else {
            state.reorderableKeys.remove(key)
        }
    }
}