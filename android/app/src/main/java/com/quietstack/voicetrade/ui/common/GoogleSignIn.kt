package com.quietstack.voicetrade.ui.common

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.quietstack.voicetrade.BuildConfig
import timber.log.Timber

sealed class GoogleSignInFailure(message: String) : Exception(message) {
    /** The user closed the account picker: not an error worth showing. */
    data object Cancelled : GoogleSignInFailure("cancelled")
    data object NotConfigured : GoogleSignInFailure("Google sign-in isn't set up in this build")
    data class Failed(val detail: String) : GoogleSignInFailure(detail)
}

/** Shows the Google account picker and returns a Google ID token for the backend to verify. */
object GoogleSignIn {
    suspend fun idToken(activity: Activity): Result<String> {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) return Result.failure(GoogleSignInFailure.NotConfigured)
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Result.success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.failure(GoogleSignInFailure.Failed("Unexpected credential type"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(GoogleSignInFailure.Cancelled)
        } catch (e: GetCredentialException) {
            Timber.w(e, "Google sign-in failed")
            Result.failure(GoogleSignInFailure.Failed(e.message ?: e.type))
        }
    }
}
