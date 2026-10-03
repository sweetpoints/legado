package io.legado.app.ui.qrcode

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class QrScanComposeTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<QrScanViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private class Owner:LifecycleOwner { val registry=LifecycleRegistry(this); override val lifecycle get()=registry }
    private class Repo:QrScanRepository {
        var value=QrScanSession(UUID.randomUUID().toString()); var writes=0
        var claimGate:CompletableDeferred<Unit>?=null; var claiming=false; var failClaim=false
        override suspend fun open(id:String?)=value
        override suspend fun write(value:QrScanSession) { if(value.completed) { claiming=true; claimGate?.await(); if(failClaim) error("disk full") }; writes++; this.value=value }
        override suspend fun decode(uri:String)=uri
        override suspend fun release(id:String)=Unit
    }
    @After fun after() { compose.runOnIdle { gates.forEach{it.complete(Unit)}; models.forEach { it.viewModelScope.cancel() } } }
    @Test fun loadingAndDecodeDisableCameraAndGalleryButKeepBackAccessible() {
        var state by mutableStateOf(QrScanState()); var camera=false; var gallery=0; var back=0
        compose.setContent { LegadoComposeTheme { QrScanScreen(state,true,{gallery++},{back++},{}) { camera=it } } }
        compose.onNodeWithTag("qr-progress").assertIsDisplayed(); assertFalse(camera)
        compose.runOnIdle { state=QrScanState(loading=false) }; compose.onNodeWithTag("qr-gallery").performClick(); assertEquals(1,gallery); assertTrue(camera)
        compose.runOnIdle { state=state.copy(decoding=true) }; compose.onNodeWithTag("qr-gallery").assertIsNotEnabled(); assertFalse(camera)
        compose.onNodeWithTag("qr-back").performClick(); assertEquals(1,back)
    }
    @Test fun permissionPendingHasGalleryAndErrorRetryWithoutRenderingCamera() {
        var retries=0; var gallery=0; var camera=true
        compose.setContent { LegadoComposeTheme { QrScanScreen(QrScanState(loading=false,error="unreadable image"),false,{gallery++},{},{retries++}) { camera=it } } }
        assertFalse(camera); compose.onNodeWithTag("qr-error").assertTextEquals("unreadable image")
        compose.onNodeWithTag("qr-retry").performClick(); compose.onNodeWithTag("qr-gallery").performClick(); assertEquals(1,retries); assertEquals(1,gallery)
    }
    @Test fun pauseDuringDurableClaimRetainsResultAndResumeDeliversNullOnce() {
        val repo=Repo(); val gate=CompletableDeferred<Unit>(); gates+=gate; val owner=Owner(); lateinit var vm:QrScanViewModel; var launches=0
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.RESUMED; vm=QrScanViewModel(repo,SavedStateHandle()); models+=vm }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { QrScanRoute(vm,true,{true},{},{assertNull(it); launches++},{}) {} } } }
        compose.waitUntil { !vm.state.value.loading }; compose.runOnIdle { repo.claimGate=gate; vm.capture(null) }; compose.waitUntil { repo.claiming }
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.CREATED; gate.complete(Unit) }; compose.waitUntil { vm.state.value.pendingResult!=null && !repo.value.completed }; assertEquals(0,launches)
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.RESUMED }; compose.waitUntil { launches==1 }
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.CREATED }; compose.runOnIdle { owner.registry.currentState=Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1,launches)
    }
    @Test fun failedClaimShowsErrorAndExplicitRetryDeliversStoredTextOnce() {
        val repo=Repo(); lateinit var vm:QrScanViewModel; val results=mutableListOf<String?>()
        compose.runOnIdle { vm=QrScanViewModel(repo,SavedStateHandle()); models+=vm }
        compose.setContent { LegadoComposeTheme { QrScanRoute(vm,true,{true},{},{results+=it},{}) {} } }
        compose.waitUntil { !vm.state.value.loading }; compose.runOnIdle { repo.failClaim=true; vm.capture("result") }
        compose.waitUntil { vm.state.value.error!=null }; assertTrue(results.isEmpty()); val writes=repo.writes; compose.waitForIdle(); assertEquals(writes,repo.writes)
        compose.runOnIdle { repo.failClaim=false }; compose.onNodeWithTag("qr-retry").performClick(); compose.waitUntil { results.size==1 }; assertEquals(listOf("result"),results)
    }
}
