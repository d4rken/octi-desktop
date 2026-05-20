package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.collections.fromGzip
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleId
import okio.ByteString
import okio.ByteString.Companion.toByteString

fun encryptModulePayload(
    credentials: PayloadEncryption.KeySet,
    ownerDeviceId: String,
    moduleId: ModuleId,
    plaintextJson: String,
): ByteArray {
    val crypto = PayloadEncryption(keySet = credentials)
    val aad = "$ownerDeviceId:${moduleId.id}".toByteArray(Charsets.UTF_8)
    return crypto.encrypt(plaintextJson.toByteArray(Charsets.UTF_8).toByteString().toGzip(), aad).toByteArray()
}

fun decryptModulePayload(
    credentials: PayloadEncryption.KeySet,
    ownerDeviceId: String,
    moduleId: ModuleId,
    ciphertext: ByteArray,
): String {
    val crypto = PayloadEncryption(keySet = credentials)
    val aad = "$ownerDeviceId:${moduleId.id}".toByteArray(Charsets.UTF_8)
    return crypto.decrypt(ByteString.of(*ciphertext), aad).fromGzip().string(Charsets.UTF_8)
}
