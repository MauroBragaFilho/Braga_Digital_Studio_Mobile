package com.bragastudio.mobile.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLogicTest {
    @Test
    fun grantedWinsOverEverything() {
        assertEquals(PermissionStatus.Granted, PermissionLogic.status(granted = true, askedBefore = true, shouldShowRationale = false))
    }

    @Test
    fun neverAskedIsDeniedNotBlocked() {
        // Sem ter pedido nunca, "rationale = false" só significa que ainda não perguntamos.
        assertEquals(PermissionStatus.Denied, PermissionLogic.status(granted = false, askedBefore = false, shouldShowRationale = false))
    }

    @Test
    fun askedAndNoRationaleIsPermanentlyDenied() {
        assertEquals(PermissionStatus.PermanentlyDenied, PermissionLogic.status(false, askedBefore = true, shouldShowRationale = false))
    }

    @Test
    fun askedWithRationaleCanBeAskedAgain() {
        assertEquals(PermissionStatus.Denied, PermissionLogic.status(false, askedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun notificationsAreRuntimeOnlyFromApi33() {
        assertFalse(PermissionLogic.notificationsRuntime(32))
        assertTrue(PermissionLogic.notificationsRuntime(33))
    }

    @Test
    fun monitorNeedsCameraFirst() {
        assertEquals(
            MonitorEntryStep.NeedCamera,
            PermissionLogic.monitorEntryStep(PermissionStatus.Denied, PermissionStatus.Granted, micOfferDismissed = false),
        )
        assertEquals(
            MonitorEntryStep.CameraBlocked,
            PermissionLogic.monitorEntryStep(PermissionStatus.PermanentlyDenied, PermissionStatus.Granted, micOfferDismissed = false),
        )
    }

    @Test
    fun microphoneIsOfferedOnceAndNeverBlocksEntry() {
        assertEquals(
            MonitorEntryStep.OfferMicrophone,
            PermissionLogic.monitorEntryStep(PermissionStatus.Granted, PermissionStatus.Denied, micOfferDismissed = false),
        )
        // Já dispensado: entra sem microfone.
        assertEquals(
            MonitorEntryStep.Ready,
            PermissionLogic.monitorEntryStep(PermissionStatus.Granted, PermissionStatus.Denied, micOfferDismissed = true),
        )
        // Microfone bloqueado de vez: não insiste, entra sem áudio.
        assertEquals(
            MonitorEntryStep.Ready,
            PermissionLogic.monitorEntryStep(PermissionStatus.Granted, PermissionStatus.PermanentlyDenied, micOfferDismissed = false),
        )
        assertEquals(
            MonitorEntryStep.Ready,
            PermissionLogic.monitorEntryStep(PermissionStatus.Granted, PermissionStatus.Granted, micOfferDismissed = false),
        )
    }

    @Test
    fun pendingCountCountsOnlyNotGranted() {
        val statuses = mapOf(
            AppPermission.Camera to PermissionStatus.Granted,
            AppPermission.Microphone to PermissionStatus.Denied,
            AppPermission.Notifications to PermissionStatus.PermanentlyDenied,
        )
        assertEquals(2, PermissionLogic.pendingCount(statuses))
    }
}
