package me.hletrd.telecampro.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.controls.DialType
import me.hletrd.telecampro.ui.controls.manualDialTransition
import me.hletrd.telecampro.ui.review.MediaReviewOverlay
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
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
    fun `review initial focus delete dialog and Back remain reachable`() {
        var closes = 0
        compose.setContent {
            KeyboardMode {
                TeleCamProTheme {
                    MediaReviewOverlay(
                        uri = Uri.parse("content://telecam.invalid/missing"),
                        deleteScope = MediaDeleteScope.FILE_ONLY,
                        onClose = { closes++ },
                        onDelete = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val close = compose.onNodeWithContentDescription(context.getString(R.string.a11y_close_review))
        close.assertIsFocused()
        compose.onNodeWithContentDescription(
            context.getString(R.string.review_delete_file_title).removeSuffix("?"),
        ).requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText(context.getString(R.string.action_cancel)).and(hasAnyAncestor(isDialog())))
            .requestFocus()
            .assertIsFocused()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()

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
}
