package me.hletrd.telecampro.ui

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.OpenReviewPresentation
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.controls.DialType
import me.hletrd.telecampro.ui.controls.ManualDialCluster
import me.hletrd.telecampro.ui.controls.manualDialTransition
import me.hletrd.telecampro.ui.review.MediaReviewOverlay
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import me.hletrd.telecampro.storage.MediaProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class ModalFocusComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    @Composable
    private fun KeyboardMode(content: @Composable () -> Unit) {
        LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
        backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
        content()
    }

    @Test
    fun `modal boundary excludes finder targets for tab shift-tab and directions`() {
        var finderActivations = 0
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .finderFocusEnabled(false),
                            verticalArrangement = Arrangement.spacedBy(260.dp),
                        ) {
                            Button(
                                onClick = { finderActivations++ },
                                modifier = Modifier.testTag("finder-before"),
                            ) { Text("Finder before") }
                            Button(
                                onClick = { finderActivations++ },
                                modifier = Modifier.testTag("finder-after"),
                            ) { Text("Finder after") }
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .modalFocusBoundary(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Button(onClick = {}, modifier = Modifier.testTag("modal-first")) { Text("First") }
                            Button(onClick = {}, modifier = Modifier.testTag("modal-last")) { Text("Last") }
                        }
                    }
                }
            }
        }

        val first = compose.onNodeWithTag("modal-first")
        val last = compose.onNodeWithTag("modal-last")
        val before = compose.onNodeWithTag("finder-before")
        val after = compose.onNodeWithTag("finder-after")

        first.requestFocus().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        before.assertIsNotFocused()
        after.assertIsNotFocused()

        for (key in listOf(Key.DirectionUp, Key.DirectionLeft)) {
            first.requestFocus().performKeyInput { pressKey(key) }
            before.assertIsNotFocused()
            after.assertIsNotFocused()
        }
        for (key in listOf(Key.Tab, Key.DirectionDown, Key.DirectionRight)) {
            last.requestFocus().performKeyInput { pressKey(key) }
            before.assertIsNotFocused()
            after.assertIsNotFocused()
        }
        assertEquals(0, finderActivations)
    }

    @Test
    fun `settings initial focus popup and Back remain reachable`() {
        var dismissals = 0
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    ProSheet(
                        state = CameraUiState(),
                        actions = actions,
                        onDismiss = { dismissals++ },
                        initialTab = ProSheetTab.LENS,
                    )
                }
            }
        }
        compose.waitForIdle()

        val close = compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_settings))
        close.assertIsFocused()
        compose.onNodeWithContentDescription(context.getString(R.string.label_phone))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNode(isPopup()).assertExists()
        compose.onNode(
            hasText(context.getString(R.string.phone_other)).and(hasAnyAncestor(isPopup())),
        ).requestFocus().assertIsFocused().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(1, dismissals)
    }

    @Test
    fun `settings close has one keyboard owner and every tab is one traversal edge`() {
        val visible = mutableStateOf(true)
        val requestId = mutableLongStateOf(1L)
        var dismissals = 0
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    if (visible.value) {
                        ProSheet(
                            state = CameraUiState(),
                            actions = actions,
                            onDismiss = {
                                dismissals++
                                visible.value = false
                            },
                            openRequestId = requestId.longValue,
                        )
                    }
                }
            }
        }

        val closeDescription = context.getString(R.string.a11y_close_settings)
        listOf(Key.Enter, Key.DirectionCenter).forEachIndexed { index, key ->
            if (index > 0) {
                compose.runOnIdle {
                    requestId.longValue++
                    visible.value = true
                }
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(closeDescription)
                .assertIsFocused()
                .performKeyInput { pressKey(key) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(closeDescription).assertDoesNotExist()
            assertEquals(index + 1, dismissals)
        }

        compose.runOnIdle {
            requestId.longValue++
            visible.value = true
        }
        compose.waitForIdle()

        val close = compose.onNodeWithContentDescription(closeDescription).assertIsFocused()
        val tabs = listOf(
            R.string.settings_tab_my,
            R.string.settings_tab_shoot,
            R.string.settings_tab_exposure,
            R.string.settings_tab_focus,
            R.string.settings_tab_lens,
            R.string.settings_tab_video,
            R.string.settings_tab_image,
            R.string.settings_tab_assist,
            R.string.settings_tab_setup,
        ).map { compose.onNodeWithContentDescription(context.getString(it)) }

        var current = close
        tabs.forEach { next ->
            current.performKeyInput { pressKey(Key.Tab) }
            next.assertIsFocused()
            current = next
        }
        (tabs.dropLast(1).asReversed() + close).forEach { previous ->
            current.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            previous.assertIsFocused()
            current = previous
        }
    }

    @Test
    fun `Fn starts on visible close and cannot focus a disabled finder sibling`() {
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    Box(Modifier.fillMaxSize()) {
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .finderFocusEnabled(false)
                                .testTag("fn-hidden-finder"),
                        ) { Text("Finder") }
                        FnOverlay(
                            state = CameraUiState(),
                            actions = actions,
                            onSelectManualDial = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val close = compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_function_menu))
        close.assertIsFocused()
        close.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        compose.onNodeWithTag("fn-hidden-finder").assertIsNotFocused()
        close.requestFocus().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("fn-hidden-finder").assertIsNotFocused()
    }

    @Test
    fun `production compact Fn and its initial close activate from both center keys`() {
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    CameraScreen(
                        state = CameraUiState(),
                        actions = actions,
                        previewViewFactory = { View(it) },
                        windowRotationOverrideDeg = 0,
                    )
                }
            }
        }
        compose.waitForIdle()

        val openDescription = context.getString(R.string.a11y_open_function_menu)
        val closeDescription = context.getString(R.string.a11y_close_function_menu)
        listOf(
            Key.Enter to Key.DirectionCenter,
            Key.DirectionCenter to Key.Enter,
        ).forEach { (openKey, closeKey) ->
            compose.onNodeWithContentDescription(openDescription)
                .requestFocus()
                .performKeyInput { pressKey(openKey) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(closeDescription)
                .assertIsFocused()
                .performKeyInput { pressKey(closeKey) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(closeDescription).assertDoesNotExist()
        }
    }

    @Test
    fun `compact ruler close and tap focus reset activate from both center keys`() {
        var dialCloseCalls = 0
        var resetCalls = 0
        val resetActions = Proxy.newProxyInstance(
            CameraActions::class.java.classLoader,
            arrayOf(CameraActions::class.java),
        ) { _, method, _ ->
            if (method.name == "onResetFocusPoint") resetCalls++
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        } as CameraActions
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    Column {
                        ManualDialCluster(
                            state = CameraUiState(),
                            actions = actions,
                            openDial = DialType.ZOOM,
                            onSelectDial = {},
                            onCloseDial = { dialCloseCalls++ },
                            glyphRotation = 0f,
                            onOpenFnMenu = {},
                            compact = true,
                        )
                        CameraScreen(
                            state = CameraUiState(tapFocusHeld = true),
                            actions = resetActions,
                            previewViewFactory = { View(it) },
                            windowRotationOverrideDeg = 0,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val dialClose = compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_close_adjustment),
        )
        val baselineCloseCalls = dialCloseCalls
        dialClose.requestFocus().performKeyInput { pressKey(Key.Enter) }
        dialClose.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(baselineCloseCalls + 2, dialCloseCalls)

        val reset = compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_reset_focus_point),
        )
        reset.requestFocus().performKeyInput { pressKey(Key.Enter) }
        reset.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(2, resetCalls)
    }

    @Test
    fun `review initial focus delete dialog and Back remain reachable`() {
        var closes = 0
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    MediaReviewOverlay(
                        uri = Uri.parse("content://telecam.invalid/missing"),
                        deleteScope = MediaDeleteScope.FILE_ONLY,
                        provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                        onClose = { closes++ },
                        onDelete = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(
            context.getString(R.string.review_legacy_unverified_provenance),
        ).assertExists()

        val close = compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_review))
        close.assertIsFocused()
        val delete = compose.onNodeWithContentDescription(
            context.getString(R.string.review_delete_file_title).removeSuffix("?"),
        )
        delete.requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText(context.getString(R.string.action_cancel)).and(hasAnyAncestor(isDialog())))
            .requestFocus()
            .assertIsFocused()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()
        delete.assertIsFocused()

        delete.performKeyInput { pressKey(Key.Enter) }
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText(context.getString(R.string.action_cancel)).and(hasAnyAncestor(isDialog())))
            .performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        delete.assertIsFocused()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(1, closes)
    }

    @Test
    fun `same-frame My Menu WB reopen selects Exposure while ordinary rail focus stays put`() {
        val visible = mutableStateOf(true)
        val requestedTab = mutableStateOf(ProSheetTab.MY_MENU)
        val openRequestId = mutableLongStateOf(1L)
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    if (visible.value) {
                        ProSheet(
                            state = CameraUiState(),
                            actions = actions,
                            onDismiss = { visible.value = false },
                            initialTab = requestedTab.value,
                            openRequestId = openRequestId.longValue,
                            onTabChange = { requestedTab.value = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // An ordinary rail selection updates the remembered reopen tab, but does not advance the
        // request identity. Its own focus must not be stolen by the open-request effect.
        val exposure = compose.onNodeWithContentDescription(context.getString(R.string.settings_tab_exposure))
        exposure.requestFocus().performClick()
        compose.waitForIdle()
        exposure.assertIsSelected().assertIsFocused()

        val my = compose.onNodeWithContentDescription(context.getString(R.string.settings_tab_my))
        my.requestFocus().performClick()
        compose.waitForIdle()
        my.assertIsSelected().assertIsFocused()

        // This is CameraScreen's exact non-manual-WB plan. Dismiss + reopen happen in one main-loop
        // turn, so `visible` is false and true before Compose can dispose the existing ProSheet.
        compose.runOnIdle {
            val transition = manualDialTransition(
                requested = DialType.WB,
                currentlyOpen = null,
                exposureMode = ExposureMode.PROGRAM,
                focusMode = FocusMode.CONTINUOUS,
                wbMode = WbMode.DAYLIGHT,
            )
            assertTrue(transition.openExposureSheet)
            visible.value = false
            requestedTab.value = ProSheetTab.EXPOSURE
            openRequestId.longValue += 1L
            visible.value = true
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(context.getString(R.string.settings_tab_exposure))
            .assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.label_mode)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_settings))
            .assertIsFocused()
    }

    @Test
    fun `production settings and Fn close restore their exact opener`() {
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    CameraScreen(
                        state = CameraUiState(),
                        actions = actions,
                        previewViewFactory = { View(it) },
                        windowRotationOverrideDeg = 0,
                    )
                }
            }
        }
        compose.waitForIdle()

        val settings = compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_open_settings),
        )
        settings.requestFocus().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_settings))
            .assertIsFocused()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_open_settings),
            useUnmergedTree = true,
        )
            .assertIsFocused()

        compose.onNodeWithContentDescription(context.getString(R.string.a11y_open_settings))
            .performClick()
        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_open_settings),
            useUnmergedTree = true,
        ).assertIsFocused()

        val functionMenu = compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_open_function_menu),
        )
        functionMenu.requestFocus().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_function_menu))
            .assertIsFocused()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_open_function_menu),
            useUnmergedTree = true,
        )
            .assertIsFocused()
    }

    @Test
    fun `production review close restores the gallery opener`() {
        val uri = Uri.parse("content://telecam.invalid/focus-origin.jpg")
        val state = mutableStateOf(
            CameraUiState(
                lastMediaUri = uri,
                lastMediaProvenance = MediaProvenance.APP_OWNED,
            ),
        )
        val reviewActions = Proxy.newProxyInstance(
            CameraActions::class.java.classLoader,
            arrayOf(CameraActions::class.java),
        ) { _, method, args ->
            if (method.name == "onReviewOpenChange") {
                val open = args?.get(0) as Boolean
                state.value = state.value.copy(
                    openReview = if (open) {
                        OpenReviewPresentation(
                            uri = uri,
                            provenance = MediaProvenance.APP_OWNED,
                            deleteScope = MediaDeleteScope.FILE_ONLY,
                        )
                    } else {
                        null
                    },
                )
            }
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        } as CameraActions
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    CameraScreen(
                        state = state.value,
                        actions = reviewActions,
                        previewViewFactory = { View(it) },
                        windowRotationOverrideDeg = 0,
                    )
                }
            }
        }
        compose.waitForIdle()

        val gallery = compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_review_last_photo),
        )
        gallery.requestFocus().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_review))
            .assertIsFocused()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.a11y_review_last_photo),
            useUnmergedTree = true,
        )
            .assertIsFocused()
    }
}
