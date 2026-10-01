package bosca.core.security.providers

import bosca.core.errors.asException
import cocoapods.GoogleSignIn.GIDSignIn
import cocoapods.GoogleSignIn.GIDSignInResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
suspend fun UIViewController.loginWithGoogle(): GIDSignInResult? {
    return suspendCancellableCoroutine { continuation ->
        GIDSignIn.sharedInstance().signInWithPresentingViewController(this, completion = { signInResult, error ->
            if (error != null) {
                continuation.resumeWithException(error.asException())
            } else {
                continuation.resume(signInResult)
            }
        })
    }
}