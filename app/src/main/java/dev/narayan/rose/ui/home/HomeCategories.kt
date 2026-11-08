package dev.narayan.rose.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.explorer.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class HomeCategory(
    @StringRes val labelRes: Int,
    val type: FileType?,
    @DrawableRes val iconRes: Int,
    val color: Color,
    val isFavourites: Boolean = false,
    val isFtp: Boolean = false
) {
    val icon: ImageVector @Composable get() = ImageVector.vectorResource(iconRes)
}

internal val page1Categories = listOf(
    HomeCategory(R.string.category_photos, FileType.IMAGE, R.drawable.ic_image_fill, Color(0xFF4C8DFF)),
    HomeCategory(R.string.category_videos, FileType.VIDEO, R.drawable.ic_video_library_fill, Color(0xFF9C6ADE)),
    HomeCategory(R.string.category_audio, FileType.AUDIO, R.drawable.ic_music_note_fill, Color(0xFFE0904C)),
    HomeCategory(R.string.category_documents, FileType.DOCUMENT, R.drawable.ic_description_fill, Color(0xFF4C9A72)),
    HomeCategory(R.string.category_apks, FileType.APK, R.drawable.ic_android, Color(0xFF4CAF7D)),
    HomeCategory(R.string.category_archives, FileType.ZIP, R.drawable.ic_archive_fill, Color(0xFFC98A2E))
)

internal val page2Categories = listOf(
    HomeCategory(
        labelRes = R.string.category_favourites,
        type = null,
        iconRes = R.drawable.ic_favorite_fill,
        color = Color(0xFFE11D48),
        isFavourites = true
    ),
    HomeCategory(
        labelRes = R.string.category_ftp_server,
        type = null,
        iconRes = R.drawable.ic_wifi_tethering,
        color = Color(0xFF00897B),
        isFtp = true
    )
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CategoryGrid(
    counts: Map<FileType, Int>,
    favouriteCount: Int,
    isLoading: Boolean,
    hasRunCountAnimation: Boolean,
    onAnimationFinished: () -> Unit,
    onCategoryClick: (HomeCategory) -> Unit,
    onOpenRecent: () -> Unit,
    canSwipeToRecent: Boolean = true,
    categoryPageIndex: Int = 0,
    onCategoryPageChange: (Int) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    var animationStarted by remember(hasRunCountAnimation) { mutableStateOf(hasRunCountAnimation) }

    LaunchedEffect(hasRunCountAnimation) {
        if (!hasRunCountAnimation) {
            kotlinx.coroutines.delay(60)
            animationStarted = true
            kotlinx.coroutines.delay(1400)
            onAnimationFinished()
        }
    }

    val categoryPagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = categoryPageIndex,
        pageCount = { 2 }
    )
    val coroutineScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

    LaunchedEffect(categoryPagerState.settledPage) {
        onCategoryPageChange(categoryPagerState.settledPage)
    }

    LaunchedEffect(categoryPageIndex) {
        if (!categoryPagerState.isScrollInProgress && categoryPagerState.currentPage != categoryPageIndex) {
            categoryPagerState.scrollToPage(categoryPageIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    val dragPointerId = down.id
                    var isGestureClaimed = false
                    var prevPositionX = down.position.x
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == dragPointerId } ?: break

                            if (!change.pressed) {
                                if (isGestureClaimed) {
                                    change.consume()
                                }
                                break
                            }

                            val currentX = change.position.x
                            val currentY = change.position.y
                            val totalDx = currentX - down.position.x
                            val totalDy = currentY - down.position.y

                            if (!isGestureClaimed) {
                                val totalDistanceSq = totalDx * totalDx + totalDy * totalDy
                                if (totalDistanceSq > touchSlop * touchSlop) {
                                    if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)) {
                                        break
                                    }

                                    val currentPage = categoryPagerState.currentPage
                                    if (currentPage == 0) {
                                        if (totalDx > 0f) {
                                            break
                                        } else {
                                            settleJob?.cancel()
                                            isGestureClaimed = true
                                            prevPositionX = currentX
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        }
                                    } else {
                                        if (totalDx > 0f) {
                                            settleJob?.cancel()
                                            isGestureClaimed = true
                                            prevPositionX = currentX
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        } else {
                                            break
                                        }
                                    }
                                }
                            } else {
                                val deltaX = currentX - prevPositionX
                                prevPositionX = currentX
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                categoryPagerState.dispatchRawDelta(-deltaX)
                            }
                        }
                    } finally {
                        if (isGestureClaimed) {
                            val velocityX = velocityTracker.calculateVelocity().x
                            val currentFraction = categoryPagerState.currentPage + categoryPagerState.currentPageOffsetFraction
                            val targetPage = when {
                                velocityX < -500f -> 1
                                velocityX > 500f -> 0
                                else -> if (currentFraction >= 0.5f) 1 else 0
                            }
                            settleJob = coroutineScope.launch {
                                categoryPagerState.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                )
                            }
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = categoryPagerState,
            userScrollEnabled = false,
            pageSpacing = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val pageCategories = if (page == 0) page1Categories else page2Categories
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val pageOffset = ((categoryPagerState.currentPage - page) + categoryPagerState.currentPageOffsetFraction).let { kotlin.math.abs(it) }
                        val clampedOffset = pageOffset.coerceIn(0f, 1f)
                        val scale = 1f - (clampedOffset * 0.18f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (clampedOffset * 0.75f)
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val row1 = pageCategories.take(3)
                    row1.forEachIndexed { colIndex, category ->
                        val count = if (category.isFavourites) favouriteCount else counts[category.type]
                        CategoryCard(
                            category = category,
                            count = count,
                            animationStarted = animationStarted,
                            hasRunCountAnimation = hasRunCountAnimation,
                            index = colIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { onCategoryClick(category) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                    repeat(3 - row1.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val row2 = pageCategories.drop(3).take(3)
                    if (row2.isNotEmpty()) {
                        row2.forEachIndexed { colIndex, category ->
                            val count = if (category.isFavourites) favouriteCount else counts[category.type]
                            CategoryCard(
                                category = category,
                                count = count,
                                animationStarted = animationStarted,
                                hasRunCountAnimation = hasRunCountAnimation,
                                index = 3 + colIndex,
                                modifier = Modifier.weight(1f),
                                onClick = { onCategoryClick(category) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        repeat(3 - row2.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    } else {
                        repeat(3) {
                            Spacer(modifier = Modifier.weight(1f).padding(1.dp).aspectRatio(1f))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { pageIndex ->
                val isSelected = categoryPagerState.currentPage == pageIndex
                val width by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (isSelected) 16.dp else 6.dp,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 250),
                    label = "CategoryIndicatorWidth"
                )
                val color by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 250),
                    label = "CategoryIndicatorColor"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .size(width = width, height = 5.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            if (categoryPagerState.currentPage != pageIndex) {
                                settleJob?.cancel()
                                settleJob = coroutineScope.launch {
                                    categoryPagerState.animateScrollToPage(
                                        page = pageIndex,
                                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    )
                                }
                            }
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CategoryCard(
    category: HomeCategory,
    count: Int?,
    animationStarted: Boolean,
    hasRunCountAnimation: Boolean,
    index: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val actualCount = count ?: 0
    val targetCount = if (animationStarted || hasRunCountAnimation) actualCount else 0
    val animatedCount by androidx.compose.animation.core.animateIntAsState(
        targetValue = targetCount,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "CategoryCountAnimation"
    )

    val categoryKey = when {
        category.isFavourites -> "category_card_FAVOURITES"
        category.isFtp -> "category_card_FTP"
        category.type != null -> "category_card_${category.type.name}"
        else -> null
    }

    val cardSharedBounds = if (categoryKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = categoryKey),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(260)),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
            )
        }
    } else Modifier

    val density = LocalDensity.current
    val animatedProgress = remember { Animatable(if (hasRunCountAnimation) 1f else 0f) }
    var lastCardClickTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        if (!hasRunCountAnimation) {
            kotlinx.coroutines.delay((index * 25).toLong())
            animatedProgress.animateTo(1f, tween(durationMillis = 220, easing = LinearOutSlowInEasing))
        }
    }

    Card(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastCardClickTime > 500L) {
                lastCardClickTime = now
                onClick()
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .then(cardSharedBounds)
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * with(density) { 24.dp.toPx() }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(category.color.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(category.icon, null, tint = category.color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(category.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            val countText = if (category.isFtp) {
                val ftpState by dev.narayan.rose.ftpserver.FtpServerService.state.collectAsState()
                when (ftpState) {
                    dev.narayan.rose.ftpserver.FtpServerState.RUNNING -> stringResource(R.string.ftp_server_state_summary_running)
                    dev.narayan.rose.ftpserver.FtpServerState.STARTING -> stringResource(R.string.ftp_server_state_summary_starting)
                    else -> stringResource(R.string.ftp_server_state_summary_stopped)
                }
            } else if (count == null) {
                "..."
            } else {
                animatedCount.toString()
            }
            Text(
                countText,
                style = MaterialTheme.typography.bodySmall,
                color = if (category.isFtp) {
                    val ftpState by dev.narayan.rose.ftpserver.FtpServerService.state.collectAsState()
                    if (ftpState == dev.narayan.rose.ftpserver.FtpServerState.RUNNING) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (category.isFtp) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
