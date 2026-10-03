package io.legado.app.ui.file

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class FileManagementUiTest {
    @get:Rule val compose=createComposeRule();private val models=mutableListOf<FileManagementViewModel>()
    @After fun after() { compose.runOnIdle { models.forEach { it.stop() } } }
    private fun show(files:ManagementFiles=ManagementFiles()):FileManagementViewModel {
        lateinit var model:FileManagementViewModel;compose.runOnIdle { model=FileManagementViewModel(files,ManagementDrafts(),SavedStateHandle());models+=model }
        compose.setContent { LegadoComposeTheme { FileManagementRoute(model,{},{},{error(it)}) } };compose.waitUntil { model.state.value.loaded };return model
    }
    @Test fun longPressKeepsOnlyDeleteAndDeletesExactFileWithoutOpeningIt() {
        val files=ManagementFiles();val model=show(files)
        compose.onNodeWithTag("file-management-row-/root/root.txt").performTouchInput { longClick() };compose.onNodeWithTag("file-management-delete-/root/root.txt").assertExists().performClick()
        compose.waitUntil { model.state.value.rows.size==1 };assertEquals(listOf("/root/root.txt"),files.deleted);assertEquals(0,files.opens);assertEquals("folder",model.state.value.rows.single().name)
    }
    @Test fun parentLongPressIsConsumedWithoutOpeningMenuDeletingOrNavigatingUp() {
        val files=ManagementFiles();val model=show(files);compose.onNodeWithTag("file-management-row-/root/folder").performClick();compose.waitUntil { model.state.value.directory=="/root/folder" && !model.state.value.loading }
        compose.onNodeWithTag("file-management-row-/root").performTouchInput { longClick() };compose.onNodeWithTag("file-management-delete-/root").assertDoesNotExist();assertEquals("/root/folder",model.state.value.directory);assertTrue(files.deleted.isEmpty())
    }
    @Test fun caseSensitiveSearchKeepsParentAndCrumbNavigationClearsQuery() {
        val model=show();compose.onNodeWithTag("file-management-row-/root/folder").performClick();compose.waitUntil { model.state.value.directory=="/root/folder" && !model.state.value.loading }
        compose.onNodeWithTag("file-management-query").performTextInput("alpha");compose.onNodeWithTag("file-management-row-/root/folder/alpha.txt").assertExists();compose.onNodeWithTag("file-management-row-/root/folder/Alpha.txt").assertDoesNotExist();compose.onNodeWithTag("file-management-row-/root").assertExists()
        compose.onNodeWithTag("file-management-crumb-/root").performClick();compose.waitUntil { model.state.value.directory=="/root" && !model.state.value.loading };compose.onNodeWithTag("file-management-query").assertTextContains("");assertEquals("",model.state.value.query)
    }
    @Test fun accessibilityDeleteActionOpensSameMenuAndOnlyConfirmationRemovesFile() {
        val files=ManagementFiles();show(files);val actions=compose.onNodeWithTag("file-management-row-/root/root.txt").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single().action()) };assertTrue(files.deleted.isEmpty());compose.onNodeWithTag("file-management-delete-/root/root.txt").performClick();compose.waitUntil { files.deleted.size==1 }
    }
    private fun longList()=FileManagementState(root="/root",directory="/root",loaded=true,loading=false,crumbs=listOf(ManagedFileCrumb("/root","root")),rows=(0..199).map { ManagedFile("/root/$it","File $it",ManagedFileKind.File) })
    @Test fun currentDirectoryScrollRestoresWithoutResetToTop() {
        val tester=StateRestorationTester(compose);val state=longList()
        tester.setContent { LegadoComposeTheme { FileManagementScreen(state,FileManagementActions()) } }
        compose.onNodeWithTag("file-management-list").performScrollToNode(hasTestTag("file-management-row-/root/70"));compose.onNodeWithTag("file-management-row-/root/70").assertIsDisplayed();tester.emulateSavedInstanceStateRestore();compose.onNodeWithTag("file-management-row-/root/70").assertIsDisplayed()
    }
    @Test fun fastScrollThumbRetainsDirectNavigationAcrossLargeDirectory() {
        compose.setContent { LegadoComposeTheme { FileManagementScreen(longList(),FileManagementActions()) } }
        compose.onNodeWithTag("file-management-fast-scroll").performTouchInput { down(Offset(center.x,4f));moveTo(Offset(center.x,height*.8f),500);up() }
        compose.waitUntil { compose.onNodeWithTag("file-management-fast-scroll").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current>=140 }
    }
    @Test fun darkSmallScreenHasReadableFileNamesAndReachableBreadcrumbsAndMenu() {
        val state=longList().copy(rows=listOf(ManagedFile("/root/file","Readable file",ManagedFileKind.File)))
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.size(320.dp,300.dp)) { FileManagementScreen(state,FileManagementActions()) } } }
        compose.onNodeWithTag("file-management-crumb-/root").assertIsDisplayed();compose.onNodeWithTag("file-management-row-/root/file").assertIsDisplayed()
        val image=compose.onNodeWithText("Readable file").captureToImage().toPixelMap();assertTrue((0 until image.width).any { x->(0 until image.height).any { y->val color=image[x,y];color.red>.7f&&color.green>.7f&&color.blue>.7f } })
        compose.onNodeWithTag("file-management-row-/root/file").performTouchInput { longClick() };compose.onNodeWithTag("file-management-delete-/root/file").assertIsDisplayed()
    }
}
