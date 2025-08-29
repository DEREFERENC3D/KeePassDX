/*
 * Copyright 2025 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.credentialprovider.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.kunzisoft.keepass.activities.FileDatabaseSelectActivity
import com.kunzisoft.keepass.activities.GroupActivity
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.addSpecialMode
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.addTypeMode
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper.buildActivityResultLauncher
import com.kunzisoft.keepass.credentialprovider.SpecialMode
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.credentialprovider.passkey.data.PublicKeyCredentialCreationParameters
import com.kunzisoft.keepass.credentialprovider.passkey.data.PublicKeyCredentialUsageParameters
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.addAppOrigin
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.addAuthCode
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.addNodeId
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.addSearchInfo
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.buildCreatePublicKeyCredentialResponse
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.buildPasskeyPublicKeyCredential
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.checkSecurity
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.getVerifiedGETClientDataResponse
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.removeAppOrigin
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.removePasskey
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrieveAppOrigin
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrieveNodeId
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrievePasskey
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrievePasskeyCreationRequestParameters
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrievePasskeyUsageRequestParameters
import com.kunzisoft.keepass.credentialprovider.passkey.util.PasskeyHelper.retrieveSearchInfo
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.helper.SearchHelper
import com.kunzisoft.keepass.model.AppOrigin
import com.kunzisoft.keepass.model.Passkey
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InvalidObjectException
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyLauncherActivity : DatabaseModeActivity() {

    private var mUsageParameters: PublicKeyCredentialUsageParameters? = null
    private var mCreationParameters: PublicKeyCredentialCreationParameters? = null
    private var mPasskey: Passkey? = null

    private var mPasskeySelectionActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.buildActivityResultLauncher(
            lockDatabase = true,
            dataTransformation = { intent ->
                // Build a new formatted response from the selection response
                val responseIntent = Intent()
                try {
                    Log.d(TAG, "Passkey selection result")
                    if (intent == null)
                        throw IOException("Intent is null")
                    val passkey = intent.retrievePasskey()
                        ?: throw IOException("Passkey is null")
                    val appOrigin = intent.retrieveAppOrigin()
                        ?: throw IOException("App origin is null")
                    intent.removePasskey()
                    intent.removeAppOrigin()
                    mUsageParameters?.let { usageParameters ->
                        // Check verified origin
                        PendingIntentHandler.setGetCredentialResponse(
                            responseIntent,
                            GetCredentialResponse(
                                buildPasskeyPublicKeyCredential(
                                    requestOptions = usageParameters.publicKeyCredentialRequestOptions,
                                    clientDataResponse = getVerifiedGETClientDataResponse(
                                        usageParameters = usageParameters,
                                        appOrigin = appOrigin
                                    ),
                                    passkey = passkey
                                )
                            )
                        )
                    } ?: run {
                        throw IOException("Usage parameters is null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to create selection response for passkey", e)
                    showError(e)
                }
                // Return the response
                responseIntent
            }
        )

    private var mPasskeyRegistrationActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.buildActivityResultLauncher(
            lockDatabase = true,
            dataTransformation = { intent ->
                // Build a new formatted response from the creation response
                val responseIntent = Intent()
                try {
                    Log.d(TAG, "Passkey registration result")
                    val passkey = intent?.retrievePasskey()
                    intent?.removePasskey()
                    intent?.removeAppOrigin()
                    // If registered passkey is the same as the one we want to validate,
                    if (mPasskey == passkey) {
                        mCreationParameters?.let {
                            PendingIntentHandler.setCreateCredentialResponse(
                                intent = responseIntent,
                                response = buildCreatePublicKeyCredentialResponse(
                                    publicKeyCredentialCreationParameters = it
                                )
                            )
                        }
                    } else {
                        throw SecurityException("Passkey was modified before registration")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to create registration response for passkey", e)
                    showError(e)
                }
                responseIntent
            }
        )
    
    override fun applyCustomStyle(): Boolean {
        return false
    }

    override fun finishActivityIfReloadRequested(): Boolean {
        return false
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase?) {
        super.onDatabaseRetrieved(database)

        lifecycleScope.launch(CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Passkey launch error", e)
            showError(e)
            setResult(RESULT_CANCELED)
            finish()
        }) {
            val searchInfo = intent.retrieveSearchInfo() ?: SearchInfo()
            val appOrigin = intent.retrieveAppOrigin() ?: AppOrigin()
            val nodeId = intent.retrieveNodeId()
            checkSecurity(intent, nodeId)
            when (mSpecialMode) {
                SpecialMode.SELECTION -> {
                    launchSelection(database, nodeId, searchInfo, appOrigin)
                }
                SpecialMode.REGISTRATION -> {
                    // TODO Registration in predefined group
                    // launchRegistration(database, nodeId, mSearchInfo)
                    launchRegistration(database, null, searchInfo)
                }
                else -> {
                    throw InvalidObjectException("Passkey launch mode not supported")
                }
            }
        }
    }

    private fun autoSelectPasskeyAndSetResult(
        database: ContextualDatabase?,
        nodeId: UUID,
        appOrigin: AppOrigin
    ) {
        mUsageParameters?.let { usageParameters ->
            // To get the passkey from the database
            val passkey = database
                ?.getEntryById(NodeIdUUID(nodeId))
                ?.getEntryInfo(database)
                ?.passkey
                ?: throw GetCredentialUnknownException("No passkey with nodeId $nodeId found")

            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                result,
                GetCredentialResponse(
                    buildPasskeyPublicKeyCredential(
                        requestOptions = usageParameters.publicKeyCredentialRequestOptions,
                        clientDataResponse = getVerifiedGETClientDataResponse(
                            usageParameters = usageParameters,
                            appOrigin = appOrigin
                        ),
                        passkey = passkey
                    )
                )
            )
            setResult(RESULT_OK, result)
            finish()
        } ?: run {
            Log.e(TAG, "Unable to auto select passkey, usage parameters are empty")
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private suspend fun launchSelection(
        database: ContextualDatabase?,
        nodeId: UUID?,
        searchInfo: SearchInfo,
        appOrigin: AppOrigin
    ) {
        Log.d(TAG, "Launch passkey selection")
        retrievePasskeyUsageRequestParameters(
            intent = intent,
            assetManager = assets,
            appOrigin = appOrigin,
        ) { usageParameters ->
            // Save the requested parameters
            mUsageParameters = usageParameters
            // Manage the passkey to use
            nodeId?.let { nodeId ->
                autoSelectPasskeyAndSetResult(database, nodeId, appOrigin)
            } ?: run {
                SearchHelper.checkAutoSearchInfo(
                    context = this,
                    database = database,
                    searchInfo = searchInfo,
                    onItemsFound = { _, _ ->
                        Log.w(
                            TAG, "Passkey found for auto selection, should not append," +
                                    "use PasskeyProviderService instead"
                        )
                        finish()
                    },
                    onItemNotFound = { openedDatabase ->
                        Log.d(
                            TAG, "No Passkey found for selection," +
                                    "launch manual selection in opened database"
                        )
                        GroupActivity.launchForPasskeySelectionResult(
                            context = this,
                            database = openedDatabase,
                            activityResultLauncher = mPasskeySelectionActivityResultLauncher,
                            searchInfo = null,
                            autoSearch = false
                        )
                    },
                    onDatabaseClosed = {
                        Log.d(TAG, "Manual passkey selection in closed database")
                        FileDatabaseSelectActivity.launchForPasskeySelectionResult(
                            activity = this,
                            activityResultLauncher = mPasskeySelectionActivityResultLauncher,
                            searchInfo = searchInfo,
                        )
                    }
                )
            }
        }
    }

    private fun autoRegisterPasskeyAndSetResult(
        database: ContextualDatabase?,
        nodeId: UUID,
        passkey: Passkey
    ) {
        // TODO Overwrite and Register in a predefined group
        mCreationParameters?.let { creationParameters ->
            // To set the passkey to the database
            setResult(RESULT_OK)
            finish()
        } ?: run {
            Log.e(TAG, "Unable to auto select passkey, usage parameters are empty")
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private suspend fun launchRegistration(
        database: ContextualDatabase?,
        nodeId: UUID?,
        searchInfo: SearchInfo
    ) {
        Log.d(TAG, "Launch passkey registration")
        retrievePasskeyCreationRequestParameters(
            intent = intent,
            assetManager = assets,
            passkeyCreated = { passkey, appInfoToStore, publicKeyCredentialParameters ->
                // Save the requested parameters
                mPasskey = passkey
                mCreationParameters = publicKeyCredentialParameters
                // Manage the passkey and create a register info
                val registerInfo = RegisterInfo(
                    searchInfo = searchInfo,
                    passkey = passkey,
                    appOrigin = appInfoToStore
                )
                // If nodeId already provided
                nodeId?.let { nodeId ->
                    autoRegisterPasskeyAndSetResult(database, nodeId, passkey)
                } ?: run {
                    SearchHelper.checkAutoSearchInfo(
                        context = this,
                        database = database,
                        searchInfo = searchInfo,
                        onItemsFound = { openedDatabase, _ ->
                            Log.w(TAG, "Passkey found for registration, " +
                                    "but launch manual registration for a new entry")
                            GroupActivity.launchForRegistration(
                                context = this,
                                activityResultLauncher = mPasskeyRegistrationActivityResultLauncher,
                                database = openedDatabase,
                                registerInfo = registerInfo,
                                typeMode = TypeMode.PASSKEY
                            )
                        },
                        onItemNotFound = { openedDatabase ->
                            Log.d(TAG, "Launch new manual registration in opened database")
                            GroupActivity.launchForRegistration(
                                context = this,
                                activityResultLauncher = mPasskeyRegistrationActivityResultLauncher,
                                database = openedDatabase,
                                registerInfo = registerInfo,
                                typeMode = TypeMode.PASSKEY
                            )
                        },
                        onDatabaseClosed = {
                            Log.d(TAG, "Manual passkey registration in closed database")
                            FileDatabaseSelectActivity.launchForRegistration(
                                context = this,
                                activityResultLauncher = mPasskeyRegistrationActivityResultLauncher,
                                registerInfo = registerInfo,
                                typeMode = TypeMode.PASSKEY
                            )
                        }
                    )
                }
            }
        )
    }

    private fun showError(e: Throwable) {
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }

    companion object {
        private val TAG = PasskeyLauncherActivity::class.java.name

        /**
         * Get a pending intent to launch the passkey launcher activity
         * [nodeId] can be :
         *  - null if manual selection is requested
         *  - null if manual registration is requested
         *  - an entry node id if direct selection is requested
         *  - a group node id if direct registration is requested in a default group
         *  - an entry node id if overwriting is requested in an existing entry
         */
        fun getPendingIntent(
            context: Context,
            specialMode: SpecialMode,
            searchInfo: SearchInfo? = null,
            appOrigin: AppOrigin? = null,
            nodeId: UUID? = null
        ): PendingIntent? {
            return PendingIntent.getActivity(
                context,
                (Math.random() * Integer.MAX_VALUE).toInt(),
                Intent(context, PasskeyLauncherActivity::class.java).apply {
                    addSpecialMode(specialMode)
                    addTypeMode(TypeMode.PASSKEY)
                    addSearchInfo(searchInfo)
                    addAppOrigin(appOrigin)
                    addNodeId(nodeId)
                    addAuthCode(nodeId)
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
