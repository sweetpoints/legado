package io.legado.app.ui.book.source.edit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.SourceMigrationIssue
import io.legado.app.model.sourceEngine.SourceMigrationPreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI contracts; these fixtures do not execute a migration channel or source request. */
class BookSourceMigrationUiTest {
    @get:Rule val compose = createComposeRule()
    private val candidate = """{"schemaVersion":1,"id":"fixed-ui-source"}"""
    private var previews = 0
    private var applies = 0
    private lateinit var clipboard: ClipboardManager
    private val document = BookSourceEditDocument.from(BookSource("fixed-ui-source", "固定书源"))

    private fun actions() =
        BookSourceScreenActions(
            field = { _, _, _, _, _ -> },
            focus = { _, _ -> },
            tab = {},
            options = {},
            expanded = {},
            save = {},
            native = { _, _ -> },
            autoComplete = {},
            paste = {},
            clearCookie = {},
            insert = {},
            undo = {},
            redo = {},
            groups = {},
            dismissGroups = {},
            variableEdit = {},
            variableSave = {},
            dismissVariable = {},
            cancel = {},
            discard = {},
            keepEditing = {},
            retry = {},
            migration = { previews++ },
        )

    private fun showDialog(state: BookSourceComposeState) {
        compose.setContent {
            clipboard = LocalClipboardManager.current
            BookSourceMigrationDialog(
                state,
                onDismiss = {},
                onApply = { applies++ },
                onRetry = { previews++ },
            )
        }
    }

    private fun report(manual: Boolean = false, revision: Long = document.revision) =
        BookSourceComposeState(
            document = document,
            migrationAvailable = true,
            migrationReport =
                BookSourceMigrationReport(
                    revision,
                    SourceMigrationPreview(
                        if (manual)
                            listOf(
                                SourceMigrationIssue(
                                    "ruleSearch.name",
                                    "legacy.unsupported",
                                    "手工迁移固定问题",
                                )
                            )
                        else emptyList(),
                        candidate,
                        manual,
                        if (manual) "manualRequired" else "unverified",
                    ),
                ),
        )

    @Test
    fun editorShowsOnlyFixedFlutterV8EngineAndPreviewCallsAction() {
        val state =
            mutableStateOf(BookSourceComposeState(document = document, migrationAvailable = true))
        compose.setContent {
            BookSourceEditScreen(
                state.value,
                actions(),
                maxLines = 6,
                keyboardRows = 1,
                keyboardVisible = false,
            )
        }
        compose.onNodeWithText("书源引擎：Flutter/V8").assertIsDisplayed()
        compose.onNodeWithText("旧引擎").assertDoesNotExist()
        compose.onNodeWithTag("source-engine-legacy").assertDoesNotExist()
        compose.onNodeWithTag("sourceMigrationPreview").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, previews) }
    }

    @Test
    fun manualMigrationDisplaysIssueAndAllowsCopyButNotApply() {
        showDialog(report(manual = true))
        compose.onNodeWithText("ruleSearch.name\nlegacy.unsupported\n手工迁移固定问题").assertExists()
        compose.onNodeWithTag("sourceMigrationApply").assertIsNotEnabled()
        compose
            .onNodeWithTag("sourceMigrationCopy")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(candidate, clipboard.getText()?.text)
            assertEquals(0, applies)
        }
    }

    @Test
    fun staleUnverifiedReportCannotApplyAndOffersNewPreview() {
        showDialog(report(revision = document.revision - 1))
        compose.onNodeWithText("草稿已更改，此预览已过期。请重新生成后再应用。").assertExists()
        compose.onNodeWithTag("sourceMigrationApply").assertIsNotEnabled()
        compose.onNodeWithText("重新生成预览").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, previews)
            assertEquals(0, applies)
        }
    }

    @Test
    fun currentUnverifiedReportExplicitlyWarnsAndApplyCallsAction() {
        showDialog(report())
        compose.onNodeWithText("迁移预览尚未运行书源，也未验证请求、内容或登录状态。应用后请按正常流程保存，再调试书源。").assertExists()
        compose.onNodeWithTag("sourceMigrationApply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, applies) }
    }

    @Test
    fun existingModernPayloadShowsReasonWithoutCandidateApplyOrCopy() {
        showDialog(
            BookSourceComposeState(
                document = document,
                migrationAvailable = true,
                migrationError = "已使用新版配置，无需再次从旧字段迁移",
            )
        )
        compose.onNodeWithText("已使用新版配置，无需再次从旧字段迁移").assertExists()
        compose.onNodeWithTag("sourceMigrationApply").assertIsNotEnabled()
        compose.onNodeWithTag("sourceMigrationCopy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, applies) }
    }
}
