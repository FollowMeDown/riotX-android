/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.crypto.verification

import android.os.Build
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.session.crypto.crosssigning.CrossSigningService
import im.vector.matrix.android.api.session.crypto.sas.CancelCode
import im.vector.matrix.android.api.session.crypto.sas.EmojiRepresentation
import im.vector.matrix.android.api.session.crypto.sas.SasMode
import im.vector.matrix.android.api.session.crypto.sas.SasVerificationTransaction
import im.vector.matrix.android.api.session.crypto.sas.VerificationTxState
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.internal.crypto.actions.SetDeviceVerificationAction
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import im.vector.matrix.android.internal.crypto.model.MXKey
import im.vector.matrix.android.internal.crypto.model.rest.SignatureUploadResponse
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import im.vector.matrix.android.internal.extensions.toUnsignedInt
import org.matrix.olm.OlmSAS
import org.matrix.olm.OlmUtility
import timber.log.Timber
import kotlin.properties.Delegates

/**
 * Represents an ongoing short code interactive key verification between two devices.
 */
internal abstract class SASDefaultVerificationTransaction(
        private val setDeviceVerificationAction: SetDeviceVerificationAction,
        open val credentials: Credentials,
        private val cryptoStore: IMXCryptoStore,
        private val crossSigningService: CrossSigningService,
        private val deviceFingerprint: String,
        transactionId: String,
        otherUserId: String,
        otherDevice: String?,
        isIncoming: Boolean) :
        DefaultVerificationTransaction(transactionId, otherUserId, otherDevice, isIncoming), SasVerificationTransaction {

    companion object {
        const val SAS_MAC_SHA256_LONGKDF = "hmac-sha256"
        const val SAS_MAC_SHA256 = "hkdf-hmac-sha256"

        // ordered by preferred order
        val KNOWN_AGREEMENT_PROTOCOLS = listOf(MXKey.KEY_CURVE_25519_TYPE)
        // ordered by preferred order
        val KNOWN_HASHES = listOf("sha256")
        // ordered by preferred order
        val KNOWN_MACS = listOf(SAS_MAC_SHA256, SAS_MAC_SHA256_LONGKDF)

        // older devices have limited support of emoji, so reply with decimal
        val KNOWN_SHORT_CODES =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    listOf(SasMode.EMOJI, SasMode.DECIMAL)
                } else {
                    listOf(SasMode.DECIMAL)
                }
    }

    override var state by Delegates.observable(VerificationTxState.None) { _, _, new ->
        //        println("$property has changed from $old to $new")
        listeners.forEach {
            try {
                it.transactionUpdated(this)
            } catch (e: Throwable) {
                Timber.e(e, "## Error while notifying listeners")
            }
        }
        if (new == VerificationTxState.Cancelled
                || new == VerificationTxState.OnCancelled
                || new == VerificationTxState.Verified) {
            releaseSAS()
        }
    }

    override var cancelledReason: CancelCode? = null

    private var olmSas: OlmSAS? = null

    var startReq: VerificationInfoStart? = null
    var accepted: VerificationInfoAccept? = null
    var otherKey: String? = null
    var shortCodeBytes: ByteArray? = null

    var myMac: VerificationInfoMac? = null
    var theirMac: VerificationInfoMac? = null

    fun getSAS(): OlmSAS {
        if (olmSas == null) olmSas = OlmSAS()
        return olmSas!!
    }

    // To override finalize(), all you need to do is simply declare it, without using the override keyword:
    protected fun finalize() {
        releaseSAS()
    }

    private fun releaseSAS() {
        // finalization logic
        olmSas?.releaseSas()
        olmSas = null
    }

    /**
     * To be called by the client when the user has verified that
     * both short codes do match
     */
    override fun userHasVerifiedShortCode() {
        Timber.v("## SAS short code verified by user for id:$transactionId")
        if (state != VerificationTxState.ShortCodeReady) {
            // ignore and cancel?
            Timber.e("## Accepted short code from invalid state $state")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        state = VerificationTxState.ShortCodeAccepted
        // Alice and Bob’ devices calculate the HMAC of their own device keys and a comma-separated,
        // sorted list of the key IDs that they wish the other user to verify,
        // the shared secret as the input keying material, no salt, and with the input parameter set to the concatenation of:
        // - the string “MATRIX_KEY_VERIFICATION_MAC”,
        // - the Matrix ID of the user whose key is being MAC-ed,
        // - the device ID of the device sending the MAC,
        // - the Matrix ID of the other user,
        // - the device ID of the device receiving the MAC,
        // - the transaction ID, and
        // - the key ID of the key being MAC-ed, or the string “KEY_IDS” if the item being MAC-ed is the list of key IDs.

        val baseInfo = "MATRIX_KEY_VERIFICATION_MAC" +
                credentials.userId + credentials.deviceId +
                otherUserId + otherDeviceId +
                transactionId

        //  Previously, with SAS verification, the m.key.verification.mac message only contained the user's device key.
        //  It should now contain both the device key and the MSK.
        //  So when Alice and Bob verify with SAS, the verification will verify the MSK.

        val keyMap = HashMap<String, String>()

        val keyId = "ed25519:${credentials.deviceId}"
        val macString = macUsingAgreedMethod(deviceFingerprint, baseInfo + keyId)

        if (macString.isNullOrBlank()) {
            // Should not happen
            Timber.e("## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        keyMap[keyId] = macString

        cryptoStore.getMyCrossSigningInfo()?.takeIf { it.isTrusted }
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?.let { masterPublicKey ->
                    val crossSigningKeyId = "ed25519:$masterPublicKey"
                    macUsingAgreedMethod(masterPublicKey, baseInfo + crossSigningKeyId)?.let { MSKMacString ->
                        keyMap[crossSigningKeyId] = MSKMacString
                    }
                }

        val keyStrings = macUsingAgreedMethod(keyMap.keys.sorted().joinToString(","), baseInfo + "KEY_IDS")

        if (macString.isNullOrBlank() || keyStrings.isNullOrBlank()) {
            // Should not happen
            Timber.e("## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        val macMsg = transport.createMac(transactionId, keyMap, keyStrings)
        myMac = macMsg
        state = VerificationTxState.SendingMac
        sendToOther(EventType.KEY_VERIFICATION_MAC, macMsg, VerificationTxState.MacSent, CancelCode.User) {
            if (state == VerificationTxState.SendingMac) {
                // It is possible that we receive the next event before this one :/, in this case we should keep state
                state = VerificationTxState.MacSent
            }
        }

        // Do I already have their Mac?
        if (theirMac != null) {
            verifyMacs()
        } // if not wait for it
    }

    override fun shortCodeDoesNotMatch() {
        Timber.v("## SAS short code do not match for id:$transactionId")
        cancel(CancelCode.MismatchedSas)
    }

    override fun isToDeviceTransport(): Boolean {
        return transport is SasTransportToDevice
    }

    override fun acceptVerificationEvent(senderId: String, info: VerificationInfo) {
        when (info) {
            is VerificationInfoStart  -> onVerificationStart(info)
            is VerificationInfoAccept -> onVerificationAccept(info)
            is VerificationInfoKey    -> onKeyVerificationKey(senderId, info)
            is VerificationInfoMac    -> onKeyVerificationMac(info)
            else                      -> {
                // nop
            }
        }
    }

    abstract fun onVerificationStart(startReq: VerificationInfoStart)

    abstract fun onVerificationAccept(accept: VerificationInfoAccept)

    abstract fun onKeyVerificationKey(userId: String, vKey: VerificationInfoKey)

    abstract fun onKeyVerificationMac(vKey: VerificationInfoMac)

    protected fun verifyMacs() {
        Timber.v("## SAS verifying macs for id:$transactionId")
        state = VerificationTxState.Verifying

        // Keys have been downloaded earlier in process
        val otherUserKnownDevices = cryptoStore.getUserDevices(otherUserId)

        // Bob’s device calculates the HMAC (as above) of its copies of Alice’s keys given in the message (as identified by their key ID),
        // as well as the HMAC of the comma-separated, sorted list of the key IDs given in the message.
        // Bob’s device compares these with the HMAC values given in the m.key.verification.mac message.
        // If everything matches, then consider Alice’s device keys as verified.

        val baseInfo = "MATRIX_KEY_VERIFICATION_MAC" +
                otherUserId + otherDeviceId +
                credentials.userId + credentials.deviceId +
                transactionId

        val commaSeparatedListOfKeyIds = theirMac!!.mac!!.keys.sorted().joinToString(",")

        val keyStrings = macUsingAgreedMethod(commaSeparatedListOfKeyIds, baseInfo + "KEY_IDS")
        if (theirMac!!.keys != keyStrings) {
            // WRONG!
            cancel(CancelCode.MismatchedKeys)
            return
        }

        val verifiedDevices = ArrayList<String>()

        // cannot be empty because it has been validated
        theirMac!!.mac!!.keys.forEach {
            val keyIDNoPrefix = if (it.startsWith("ed25519:")) it.substring("ed25519:".length) else it
            val otherDeviceKey = otherUserKnownDevices?.get(keyIDNoPrefix)?.fingerprint()
            if (otherDeviceKey == null) {
                Timber.w("## SAS Verification: Could not find device $keyIDNoPrefix to verify")
                // just ignore and continue
                return@forEach
            }
            val mac = macUsingAgreedMethod(otherDeviceKey, baseInfo + it)
            if (mac != theirMac?.mac?.get(it)) {
                // WRONG!
                Timber.e("## SAS Verification: mac mismatch for $otherDeviceKey with id $keyIDNoPrefix")
                cancel(CancelCode.MismatchedKeys)
                return
            }
            verifiedDevices.add(keyIDNoPrefix)
        }

        var otherMasterKeyIsVerified = false
        val otherMasterKey = cryptoStore.getCrossSigningInfo(otherUserId)?.masterKey()
        val otherCrossSigningMasterKeyPublic = otherMasterKey?.unpaddedBase64PublicKey
        if (otherCrossSigningMasterKeyPublic != null) {
            // Did the user signed his master key
            theirMac!!.mac!!.keys.forEach {
                val keyIDNoPrefix = if (it.startsWith("ed25519:")) it.substring("ed25519:".length) else it
                if (keyIDNoPrefix == otherCrossSigningMasterKeyPublic) {
                    // Check the signature
                    val mac = macUsingAgreedMethod(otherCrossSigningMasterKeyPublic, baseInfo + it)
                    if (mac != theirMac?.mac?.get(it)) {
                        // WRONG!
                        Timber.e("## SAS Verification: mac mismatch for MasterKey with id $keyIDNoPrefix")
                        cancel(CancelCode.MismatchedKeys)
                        return
                    } else {
                        otherMasterKeyIsVerified = true
                    }
                }
            }
        }

        // if none of the keys could be verified, then error because the app
        // should be informed about that
        if (verifiedDevices.isEmpty() && !otherMasterKeyIsVerified) {
            Timber.e("## SAS Verification: No devices verified")
            cancel(CancelCode.MismatchedKeys)
            return
        }

        // If not me sign his MSK and upload the signature
        if (otherMasterKeyIsVerified && otherUserId != credentials.userId) {
            // we should trust this master key
            // And check verification MSK -> SSK?
            crossSigningService.trustUser(otherUserId, object : MatrixCallback<SignatureUploadResponse> {
                override fun onFailure(failure: Throwable) {
                    Timber.e(failure, "## SAS Verification: Failed to trust User $otherUserId")
                }
            })
        }

        if (otherUserId == credentials.userId) {
            // If me it's reasonable to sign and upload the device signature
            // Notice that i might not have the private keys, so may ot be able to do it
            crossSigningService.signDevice(otherDeviceId!!, object : MatrixCallback<SignatureUploadResponse> {
                override fun onFailure(failure: Throwable) {
                    Timber.w(failure, "## SAS Verification: Failed to sign new device $otherDeviceId")
                }
            })
        }

        // TODO what if the otherDevice is not in this list? and should we
        verifiedDevices.forEach {
            setDeviceVerified(otherUserId, it)
        }
        transport.done(transactionId)
        state = VerificationTxState.Verified
    }

    private fun setDeviceVerified(userId: String, deviceId: String) {
        // TODO should not override cross sign status
        setDeviceVerificationAction.handle(DeviceTrustLevel(false, true),
                userId,
                deviceId)
    }

    override fun cancel() {
        cancel(CancelCode.User)
    }

    override fun cancel(code: CancelCode) {
        cancelledReason = code
        state = VerificationTxState.Cancelled
        transport.cancelTransaction(transactionId, otherUserId, otherDeviceId ?: "", code)
    }

    protected fun sendToOther(type: String,
                              keyToDevice: VerificationInfo,
                              nextState: VerificationTxState,
                              onErrorReason: CancelCode,
                              onDone: (() -> Unit)?) {
        transport.sendToOther(type, keyToDevice, nextState, onErrorReason, onDone)
    }

    fun getShortCodeRepresentation(shortAuthenticationStringMode: String): String? {
        if (shortCodeBytes == null) {
            return null
        }
        when (shortAuthenticationStringMode) {
            SasMode.DECIMAL -> {
                if (shortCodeBytes!!.size < 5) return null
                return getDecimalCodeRepresentation(shortCodeBytes!!)
            }
            SasMode.EMOJI   -> {
                if (shortCodeBytes!!.size < 6) return null
                return getEmojiCodeRepresentation(shortCodeBytes!!).joinToString(" ") { it.emoji }
            }
            else            -> return null
        }
    }

    override fun supportsEmoji(): Boolean {
        return accepted?.shortAuthenticationStrings?.contains(SasMode.EMOJI) == true
    }

    override fun supportsDecimal(): Boolean {
        return accepted?.shortAuthenticationStrings?.contains(SasMode.DECIMAL) == true
    }

    protected fun hashUsingAgreedHashMethod(toHash: String): String? {
        if ("sha256".toLowerCase() == accepted?.hash?.toLowerCase()) {
            val olmUtil = OlmUtility()
            val hashBytes = olmUtil.sha256(toHash)
            olmUtil.releaseUtility()
            return hashBytes
        }
        return null
    }

    protected fun macUsingAgreedMethod(message: String, info: String): String? {
        if (SAS_MAC_SHA256_LONGKDF.toLowerCase() == accepted?.messageAuthenticationCode?.toLowerCase()) {
            return getSAS().calculateMacLongKdf(message, info)
        } else if (SAS_MAC_SHA256.toLowerCase() == accepted?.messageAuthenticationCode?.toLowerCase()) {
            return getSAS().calculateMac(message, info)
        }
        return null
    }

    override fun getDecimalCodeRepresentation(): String {
        return getDecimalCodeRepresentation(shortCodeBytes!!)
    }

    /**
     * decimal: generate five bytes by using HKDF.
     * Take the first 13 bits and convert it to a decimal number (which will be a number between 0 and 8191 inclusive),
     * and add 1000 (resulting in a number between 1000 and 9191 inclusive).
     * Do the same with the second 13 bits, and the third 13 bits, giving three 4-digit numbers.
     * In other words, if the five bytes are B0, B1, B2, B3, and B4, then the first number is (B0 << 5 | B1 >> 3) + 1000,
     * the second number is ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000, and the third number is ((B3 & 0x3f) << 7 | B4 >> 1) + 1000.
     * (This method of converting 13 bits at a time is used to avoid requiring 32-bit clients to do big-number arithmetic,
     * and adding 1000 to the number avoids having clients to worry about properly zero-padding the number when displaying to the user.)
     * The three 4-digit numbers are displayed to the user either with dashes (or another appropriate separator) separating the three numbers,
     * or with the three numbers on separate lines.
     */
    fun getDecimalCodeRepresentation(byteArray: ByteArray): String {
        val b0 = byteArray[0].toUnsignedInt() // need unsigned byte
        val b1 = byteArray[1].toUnsignedInt() // need unsigned byte
        val b2 = byteArray[2].toUnsignedInt() // need unsigned byte
        val b3 = byteArray[3].toUnsignedInt() // need unsigned byte
        val b4 = byteArray[4].toUnsignedInt() // need unsigned byte
        // (B0 << 5 | B1 >> 3) + 1000
        val first = (b0.shl(5) or b1.shr(3)) + 1000
        // ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000
        val second = ((b1 and 0x7).shl(10) or b2.shl(2) or b3.shr(6)) + 1000
        // ((B3 & 0x3f) << 7 | B4 >> 1) + 1000
        val third = ((b3 and 0x3f).shl(7) or b4.shr(1)) + 1000
        return "$first $second $third"
    }

    override fun getEmojiCodeRepresentation(): List<EmojiRepresentation> {
        return getEmojiCodeRepresentation(shortCodeBytes!!)
    }

    /**
     * emoji: generate six bytes by using HKDF.
     * Split the first 42 bits into 7 groups of 6 bits, as one would do when creating a base64 encoding.
     * For each group of 6 bits, look up the emoji from Appendix A corresponding
     * to that number 7 emoji are selected from a list of 64 emoji (see Appendix A)
     */
    fun getEmojiCodeRepresentation(byteArray: ByteArray): List<EmojiRepresentation> {
        val b0 = byteArray[0].toUnsignedInt()
        val b1 = byteArray[1].toUnsignedInt()
        val b2 = byteArray[2].toUnsignedInt()
        val b3 = byteArray[3].toUnsignedInt()
        val b4 = byteArray[4].toUnsignedInt()
        val b5 = byteArray[5].toUnsignedInt()
        return listOf(
                getEmojiForCode((b0 and 0xFC).shr(2)),
                getEmojiForCode((b0 and 0x3).shl(4) or (b1 and 0xF0).shr(4)),
                getEmojiForCode((b1 and 0xF).shl(2) or (b2 and 0xC0).shr(6)),
                getEmojiForCode((b2 and 0x3F)),
                getEmojiForCode((b3 and 0xFC).shr(2)),
                getEmojiForCode((b3 and 0x3).shl(4) or (b4 and 0xF0).shr(4)),
                getEmojiForCode((b4 and 0xF).shl(2) or (b5 and 0xC0).shr(6))
        )
    }
}