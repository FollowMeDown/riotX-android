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
package im.vector.matrix.android.internal.crypto.model.rest

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Sent by both devices to send their ephemeral Curve25519 public key to the other device.
 */
@JsonClass(generateAdapter = true)
data class KeyVerificationKey(
        /**
         * the ID of the transaction that the message is part of
         */
        @Json(name = "transaction_id")
        @JvmField
        var transactionID: String? = null,

        /**
         * The device’s ephemeral public key, as an unpadded base64 string
         */
        @JvmField
        var key: String? = null

) : SendToDeviceObject {

    companion object {
        fun create(tid: String, key: String): KeyVerificationKey {
            return KeyVerificationKey().apply {
                this.transactionID = tid
                this.key = key
            }
        }
    }

    fun isValid(): Boolean {
        if (transactionID.isNullOrBlank() || key.isNullOrBlank()) {
            return false
        }
        return true
    }
}
