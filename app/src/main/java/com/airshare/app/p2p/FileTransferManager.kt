package com.airshare.app.p2p

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.security.MessageDigest
import java.io.FileDescriptor
import java.io.FileInputStream
import java.security.spec.MGF1ParameterSpec
import kotlin.math.min

class FileTransferManager {

    private val PORT = 8888
    private val SOCKET_TIMEOUT = 15000 // 15 seconds
    private val TAG = "FileTransferManager"

    // ====================== SENDER SIDE ======================

    suspend fun sendFiles(
        host: String,
        contentResolver: ContentResolver,
        files: List<Pair<Uri, String>>,
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
            val pubKeyBytes = ByteArray(pubKeySize)
            inputStream.readFully(pubKeyBytes)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(pubKeyBytes))

            // 2. Generate AES Session Key and encrypt with RSA
            val sessionKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val encryptedSessionKey = encryptSessionKey(sessionKey, publicKey)
            
            outputStream.writeInt(encryptedSessionKey.size)
            outputStream.write(encryptedSessionKey)

            // 3. Send File Count
            outputStream.writeInt(files.size)

            files.forEachIndexed { index, (uri, displayName) ->
                sendSingleFile(contentResolver, uri, displayName, outputStream, inputStream, index, sessionKey, onProgress)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
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
        Log.i(TAG, "File sent successfully: $displayName")
    }

    // ====================== RECEIVER SIDE ======================

    private var serverSocket: ServerSocket? = null

    suspend fun startReceiving(
        contentResolver: ContentResolver,
        scope: kotlinx.coroutines.CoroutineScope,
        onReceiveRequest: suspend (String, Long, String, Int) -> Boolean,
        onProgress: (String, Long, Long) -> Unit,
        onTransferComplete: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (serverSocket == null || serverSocket?.isClosed == true) {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "ServerSocket started on port $PORT")
            }

            Log.i(TAG, "Waiting for incoming connections...")

            while (true) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (serverSocket?.isClosed == true) break
                    Log.w(TAG, "Accept error, continuing...", e)
                    continue
                }

                socket.soTimeout = SOCKET_TIMEOUT

                // Handle in separate coroutine
                scope.launch(Dispatchers.IO) {
                    try {
                        handleIncomingTransfer(socket, contentResolver, onReceiveRequest, onProgress)
                        onTransferComplete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling transfer", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                Log.e(TAG, "Fatal receiving error", e)
            }
        }
    }

    private suspend fun handleIncomingTransfer(
        socket: Socket,
        contentResolver: ContentResolver,
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

            // 2. Receive AES Session Key
            val encKeySize = input.readInt()
            val encKey = ByteArray(encKeySize)
            input.readFully(encKey)
            val sessionKey = decryptSessionKey(encKey, keyPair.private)

            // 3. Receive File Count
            val fileCount = input.readInt()

            repeat(fileCount) {
                receiveSingleFile(input, output, contentResolver, sessionKey, fileCount, onReceiveRequest, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transfer handling failed", e)
        } finally {
            socket.close()
        }
    }

    private suspend fun receiveSingleFile(
        input: DataInputStream,
        output: DataOutputStream,
        contentResolver: ContentResolver,
        sessionKey: SecretKey,
        totalFileCount: Int,
        onReceiveRequest: suspend (String, Long, String, Int) -> Boolean,
        onProgress: (String, Long, Long) -> Unit
    ) {
        // Decrypt Metadata
        val ivSize = input.readInt()
        val iv = ByteArray(ivSize).also { input.readFully(it) }
        val metaSize = input.readInt()
        val encMeta = ByteArray(metaSize).also { input.readFully(it) }

        val metadataJson = JSONObject(String(decryptWithGCM(encMeta, sessionKey, iv)))
        val fileName = metadataJson.getString("fileName")
        val fileSize = metadataJson.getLong("fileSize")
        val expectedHash = metadataJson.getString("sha256")

        // Ask user permission
        if (!onReceiveRequest(fileName, fileSize, "Nearby Device", totalFileCount)) {
            output.writeBoolean(false)
            return
        }

        output.writeBoolean(true)
        output.flush()

        // Receive File
        val fileIVSize = input.readInt()
        val fileIV = ByteArray(fileIVSize).also { input.readFully(it) }

        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
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

        Log.i(TAG, "File received successfully: $fileName")
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
            Log.e(TAG, "Error closing server socket", e)
        }
    }
}
