package com.vayunmathur.library.biometric

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.DatabaseHelper
import javax.crypto.KeyGenerator

class BiometricDatabaseHelper(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "db_auth_key"
    override val passphraseKey = "encrypted_passphrase"
    override val ivKey = "passphrase_iv"

    override fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyStoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(false)

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }
}

fun unlockDatabaseWithBiometrics(
    activity: FragmentActivity,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit
) {
    val helper = BiometricDatabaseHelper(activity)
    val executor = ContextCompat.getMainExecutor(activity)

    if (!helper.isKeyGenerated()) {
        helper.generateKey()
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher!!
                val passphrase = helper.createAndStorePassphrase(cipher)
                onSuccess(passphrase)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Setup Secure Database")
            .setSubtitle("Authenticate to create your secure encryption key")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(helper.getCipherForEncryption()))
    } else {
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher!!
                val passphrase = helper.decryptPassphrase(cipher)
                onSuccess(passphrase)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Database")
            .setSubtitle("Authenticate to access your secure data")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(helper.getCipherForDecryption()))
    }
}
