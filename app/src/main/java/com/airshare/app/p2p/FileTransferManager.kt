package com.airshare.app.p2p

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.airshare.app.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import com.airshare.app.BuildConfig
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.io.FileDescriptor
import java.io.FileInputStream
import java.security.spec.MGF1ParameterSpec
import kotlin.math.min

class FileTransferManager {

    private val PORT = 8888
    private val SOCKET_TIMEOUT = 15000 // 15 seconds
    private val TAG = "FileTransferManager"

    companion object {
        private const val MAX_FILE_SIZE = 4L * 1024 * 1024 * 1024  // 4GB
        private const val MAX_FILE_COUNT = 100
        private const val MAX_KEY_SIZE = 4096  // Max RSA key size
        private const val MAX_METADATA_SIZE = 10 * 1024  // 10KB
        private const val EXPECTED_KEY_FINGERPRINT = ""
        private const val TRANSFER_TIMEOUT = 5 * 60 * 1000L // 5 minutes
    }

    private fun verifyKeyFingerprint(publicKey: java.security.PublicKey): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)
            .take(8)
            .joinToString("") { "%02x".format(it) }

        if (BuildConfig.DEBUG) {
            LogUtil.d(TAG, "🔐 Key Fingerprint: $fingerprint")
            LogUtil.d(TAG, "User should verify both devices show same fingerprint")
        }

        return EXPECTED_KEY_FINGERPRINT.isEmpty() || fingerprint == EXPECTED_KEY_FINGERPRINT
    }

    private fun sanitizeFileName(fileName: String): String {
        // Whitelist: only alphanumeric, dot, underscore, hyphen
        val sanitized = fileName
            .replace(Regex("[^a-zA-Z0-9.-_]"), "_")
            .trim()
            .take(255)  // Max filename length

        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
            return "file_${System.currentTimeMillis()}"
        }

        return sanitized
    }

    // ====================== SENDER SIDE ======================

    suspend fun sendFiles(
        host: String,
        contentResolver: ContentResolver,
        files: List<Pair<Uri, String>>,
        onVerifyFingerprint: suspend (String, String) -> Boolean,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket().apply {
                bind(null)
                connect(InetSocketAddress(host, PORT), SOCKET_TIMEOUT)
                soTimeout = SOCKET_TIMEOUT
            }

            val outputStream = DataOutputStream(socket.getOutputStream())
            val inputStream = DataInputStream(socket.getInputStream())

            // 1. Send RSA Public Key (Receiver sends first, we receive)
            val pubKeySize = inputStream.readInt()
            if (pubKeySize > MAX_KEY_SIZE || pubKeySize < 256) {
                throw SecurityException("Invalid key size: $pubKeySize")
            }
            val pubKeyBytes = ByteArray(pubKeySize)
            inputStream.readFully(pubKeyBytes)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(pubKeyBytes))

            val receiverFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(publicKey.encoded)
                .take(8)
                .joinToString("") { "%02x".format(it) }

            if (!verifyKeyFingerprint(publicKey)) {
                throw SecurityException("Key fingerprint mismatch - possible MITM attack")
            }

            // Mutual Fingerprint: Send our fingerprint (ephemeral) for receiver to verify
            val ourKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val ourFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(ourKeyPair.public.encoded)
                .take(8)
                .joinToString("") { "%02x".format(it) }
            
            outputStream.writeUTF(ourFingerprint)
            outputStream.flush()
            LogUtil.d(TAG, "Sent our fingerpint: $ourFingerprint")

            // Security Verification: Ask user to compare fingerprints
            if (!onVerifyFingerprint(ourFingerprint, receiverFingerprint)) {
                throw SecurityException("User cancelled transfer due to fingerprint mismatch")
            }

            // 2. Send File Count
            outputStream.writeInt(files.size)
            outputStream.flush()

            files.forEachIndexed { index, (uri, displayName) ->
                // Generate NEW session key for EACH file
                val fileSessionKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val encryptedSessionKey = encryptSessionKey(fileSessionKey, publicKey)
                
                // Send encrypted key before each file
                outputStream.writeInt(encryptedSessionKey.size)
                outputStream.write(encryptedSessionKey)
                outputStream.flush()

                sendSingleFile(contentResolver, uri, displayName, outputStream, inputStream, index, fileSessionKey, onProgress)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Send failed", e)
            Result.failure(e)
        } finally {
            socket?.close()
        }
    }

    private suspend fun sendSingleFile(
        contentResolver: ContentResolver,
        uri: Uri,
        displayName: String,
        output: DataOutputStream,
        input: DataInputStream,
        fileIndex: Int,
        sessionKey: SecretKey,
        onProgress: (Int, Long, Long) -> Unit
    ) {
        withTimeoutOrNull(TRANSFER_TIMEOUT) {
            val fileSize = getFileSize(contentResolver, uri)
            val fileHash = calculateHash(contentResolver, uri)

            // Metadata
            val metadata = JSONObject().apply {
                put("fileName", displayName)
                put("fileSize", fileSize)
                put("mimeType", contentResolver.getType(uri) ?: "application/octet-stream")
                put("sha256", fileHash)
            }

            val (iv, encryptedMetadata) = encryptWithGCM(metadata.toString().toByteArray(), sessionKey)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(encryptedMetadata.size)
            output.write(encryptedMetadata)
            output.flush()

            // Wait for acceptance
            if (!input.readBoolean()) {
                throw IOException("Receiver declined the file")
            }

            // Send File Data
            val fileIV = generateIV()
            output.writeInt(fileIV.size)
            output.write(fileIV)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))
            }

            contentResolver.openInputStream(uri)?.use { fileInput ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalSent = 0L

                while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                    val encrypted = cipher.update(buffer, 0, bytesRead)
                    encrypted?.let { output.write(it) }
                    totalSent += bytesRead
                    onProgress(fileIndex, totalSent, fileSize)
                }
                val final = cipher.doFinal()
                final?.let { output.write(it) }
            }

            output.flush()
            LogUtil.i(TAG, "File sent successfully: $displayName")
        } ?: throw IOException("Transfer timeout after ${TRANSFER_TIMEOUT / 1000} seconds")
    }

    // ====================== RECEIVER SIDE ======================

    private var serverSocket: ServerSocket? = null

    suspend fun startReceiving(
        contentResolver: ContentResolver,
        scope: kotlinx.coroutines.CoroutineScope,
        onVerifyFingerprint: suspend (String, String) -> Boolean,
        onReceiveRequest: suspend (String, Long, String, Int) -> Boolean,
        onProgress: (String, Long, Long) -> Unit,
        onTransferComplete: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (serverSocket == null || serverSocket?.isClosed == true) {
                serverSocket = ServerSocket(PORT)
                LogUtil.i(TAG, "ServerSocket started on port $PORT")
            }

            LogUtil.i(TAG, "Waiting for incoming connections...")

            while (true) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (serverSocket?.isClosed == true) break
                    LogUtil.w(TAG, "Accept error, continuing...", e)
                    continue
                }

                socket.soTimeout = SOCKET_TIMEOUT

                // Handle each connection in separate coroutine
                scope.launch(Dispatchers.IO) {
                    try {
                        handleIncomingTransfer(
                            socket, 
                            contentResolver, 
                            onVerifyFingerprint,
                            onReceiveRequest, 
                            onProgress
                        )
                        onTransferComplete()
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "Error in transfer handling", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                LogUtil.e(TAG, "Fatal receiving error", e)
            }
        }
    }

    private suspend fun handleIncomingTransfer(
        socket: Socket,
        contentResolver: ContentResolver,
        onVerifyFingerprint: suspend (String, String) -> Boolean,
        onReceiveRequest: suspend (String, Long, String, Int) -> Boolean,
        onProgress: (String, Long, Long) -> Unit
    ) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // 1. Generate & Send RSA Key Pair
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pubKeyBytes = keyPair.public.encoded
            output.writeInt(pubKeyBytes.size)
            output.write(pubKeyBytes)
            output.flush()

            // Mutual Fingerprint: Receive sender's fingerprint
            val senderFingerprint = input.readUTF()
            val ourFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(keyPair.public.encoded)
                .take(8)
                .joinToString("") { "%02x".format(it) }
            
            LogUtil.d(TAG, "🔐 Mutual Security Check:")
            LogUtil.d(TAG, "  Our Fingerprint: $ourFingerprint")
            LogUtil.d(TAG, "  Sender Fingerprint: $senderFingerprint")

            // Ask user to verify fingerprints
            if (!onVerifyFingerprint(senderFingerprint, ourFingerprint)) {
                throw SecurityException("User cancelled transfer due to fingerprint mismatch")
            }

            // 2. Receive File Count
            val fileCount = input.readInt()
            if (fileCount > MAX_FILE_COUNT || fileCount <= 0) {
                throw SecurityException("Invalid file count: $fileCount")
            }

            repeat(fileCount) {
                receiveSingleFile(input, output, contentResolver, keyPair.private, fileCount, onReceiveRequest, onProgress)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Transfer handling failed", e)
        } finally {
            socket.close()
        }
    }

    private suspend fun receiveSingleFile(
        input: DataInputStream,
        output: DataOutputStream,
        contentResolver: ContentResolver,
        privateKey: java.security.PrivateKey,
        totalFileCount: Int,
        onReceiveRequest: suspend (String, Long, String, Int) -> Boolean,
        onProgress: (String, Long, Long) -> Unit
    ) {
        withTimeoutOrNull(TRANSFER_TIMEOUT) {
            // Read encrypted key for THIS file
            val encKeySize = input.readInt()
            if (encKeySize > MAX_KEY_SIZE) {
                throw SecurityException("Encrypted key too large: $encKeySize")
            }
            val encKey = ByteArray(encKeySize)
            input.readFully(encKey)
            val sessionKey = decryptSessionKey(encKey, privateKey)

            // Decrypt Metadata
            val ivSize = input.readInt()
            if (ivSize != 12) throw SecurityException("Invalid IV size")
            val iv = ByteArray(ivSize).also { input.readFully(it) }
            val metaSize = input.readInt()
            if (metaSize > MAX_METADATA_SIZE) throw SecurityException("Metadata too large")
            val encMeta = ByteArray(metaSize).also { input.readFully(it) }

            val metadataJson = JSONObject(String(decryptWithGCM(encMeta, sessionKey, iv)))
            val fileName = metadataJson.getString("fileName")
            val fileSize = metadataJson.getLong("fileSize")

            if (fileSize > MAX_FILE_SIZE || fileSize < 0) {
                throw SecurityException("Invalid file size: $fileSize")
            }

            val expectedHash = metadataJson.getString("sha256")

            // Ask user permission
            if (!onReceiveRequest(fileName, fileSize, "Nearby Device", totalFileCount)) {
                output.writeBoolean(false)
                return@withTimeoutOrNull
            }

            output.writeBoolean(true)
            output.flush()

            // Receive File
            val fileIVSize = input.readInt()
            if (fileIVSize != 12) throw SecurityException("Invalid file IV size")
            val fileIV = ByteArray(fileIVSize).also { input.readFully(it) }

            val safeName = sanitizeFileName(fileName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, metadataJson.getString("mimeType"))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AirShare")
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create file")

            var actualHash = ""
            contentResolver.openOutputStream(uri)?.use { fileOut ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))
                }

                val buffer = ByteArray(8192)
                var totalReceived = 0L

                while (totalReceived < fileSize) {
                    val toRead = minOf(buffer.size.toLong(), fileSize - totalReceived).toInt()
                    input.readFully(buffer, 0, toRead)

                    val decrypted = cipher.update(buffer, 0, toRead)
                    decrypted?.let { fileOut.write(it) }

                    totalReceived += toRead
                    onProgress(fileName, totalReceived, fileSize)
                }

                val final = cipher.doFinal()
                final?.let { fileOut.write(it) }

                // Verify hash
                actualHash = calculateHashFromUri(contentResolver, uri)
            }

            if (expectedHash.isNotEmpty() && actualHash != expectedHash) {
                contentResolver.delete(uri, null, null)
                throw IOException("Hash verification failed for $fileName")
            }

            LogUtil.i(TAG, "File received successfully: $fileName")
        } ?: throw IOException("Transfer timeout after ${TRANSFER_TIMEOUT / 1000} seconds")
    }

    // ====================== Utility Functions ======================

    private fun getFileSize(cr: ContentResolver, uri: Uri): Long {
        return cr.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) cursor.getLong(sizeIndex) else 0L
        } ?: 0L
    }

    private fun calculateHash(cr: ContentResolver, uri: Uri): String {
        return cr.openFileDescriptor(uri, "r")?.use { calculateHash(it.fileDescriptor) } ?: ""
    }

    private fun calculateHashFromUri(cr: ContentResolver, uri: Uri): String {
        return cr.openFileDescriptor(uri, "r")?.use { calculateHash(it.fileDescriptor) } ?: ""
    }

    private fun calculateHash(fd: FileDescriptor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(fd).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generateIV(): ByteArray = ByteArray(12).apply { SecureRandom().nextBytes(this) }

    private fun encryptWithGCM(data: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val iv = generateIV()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return iv to cipher.doFinal(data)
    }

    private fun decryptWithGCM(data: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(data)
    }

    private fun encryptSessionKey(sessionKey: SecretKey, publicKey: java.security.PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return cipher.doFinal(sessionKey.encoded)
    }

    private fun decryptSessionKey(encryptedKey: ByteArray, privateKey: java.security.PrivateKey): SecretKey {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val keyBytes = cipher.doFinal(encryptedKey)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun stopReceiving() {
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error closing server socket", e)
        }
    }
}
