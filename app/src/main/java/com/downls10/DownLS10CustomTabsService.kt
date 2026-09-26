package com.downls10

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

class DownLS10CustomTabsService : CustomTabsService() {
    override fun warmup(flags: Long): Boolean = true
    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true
    override fun mayLaunchUrl(sessionToken: CustomTabsSessionToken, url: Uri?, extras: Bundle?, otherLikelyBundles: MutableList<Bundle>?): Boolean = true
    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null
    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false
    override fun requestPostMessageChannel(sessionToken: CustomTabsSessionToken, postMessageOrigin: Uri): Boolean = false
    override fun postMessage(sessionToken: CustomTabsSessionToken, message: String, extras: Bundle?): Int = CustomTabsService.RESULT_FAILURE_DISALLOWED
    override fun validateRelationship(sessionToken: CustomTabsSessionToken, relation: Int, origin: Uri, extras: Bundle?): Boolean = false
    override fun receiveFile(sessionToken: CustomTabsSessionToken, uri: Uri, purpose: Int, extras: Bundle?): Boolean = false
}