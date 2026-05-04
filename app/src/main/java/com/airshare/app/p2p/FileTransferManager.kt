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
import java.security.spec.MGF1ParameterSpec
import kotlin.math.min

class FileTransferManager {

    private val PORT = 8888
    private val SOCKET_TIMEOUT = 10000 // 10 seconds

    suspend fun sendFiles(
        host: String,
        contentResolver: ContentResolver,
        files: List<Pair<Uri, String>>,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = Socket()
            socket.bind(null)
            socket.connect(InetSocketAddress(host, PORT), SOCKET_TIMEOUT)
            socket.soTimeout = SOCKET_TIMEOUT

            val inputStream = socket.getInputStream()
            val dataInputStream = DataInputStream(inputStream)
            val outputStream = socket.getOutputStream()
            val dataOutputStream = DataOutputStream(outputStream)

            // 1. Receive RSA Public Key
            val pubKeySize = dataInputStream.readInt()
            val pubKeyBytes = ByteArray(pubKeySize)
            dataInputStream.readFully(pubKeyBytes)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pubKeyBytes))

            // 2. Generate and Send Session Key (AES-256)
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val sessionKey = keyGen.generateKey()

            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            val oaepParams = OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
            )
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)
            val encryptedSessionKey = rsaCipher.doFinal(sessionKey.encoded)
            dataOutputStream.writeInt(encryptedSessionKey.size)
            dataOutputStream.write(encryptedSessionKey)

            // 3. Send File Count
            dataOutputStream.writeInt(files.size)

            files.forEachIndexed { index, (uri, displayName) ->
                val fileSize = getFileSize(contentResolver, uri)
                
                // 4. Metadata Handshake (JSON)
                val metadataJson = JSONObject().apply {
                    put("fileName", displayName)
                    put("fileSize", fileSize)
                    put("mimeType", contentResolver.getType(uri) ?: "application/octet-stream")
                    // Calculate hash for integrity
                    val hashInputStream = contentResolver.openInputStream(uri)
                    put("sha256", hashInputStream?.use { calculateHash(it) } ?: "")
                }
                
                val (iv, encryptedMetadata) = encryptWithGCM(metadataJson.toString().toByteArray(), sessionKey)
                dataOutputStream.writeInt(iv.size)
                dataOutputStream.write(iv)
                dataOutputStream.writeInt(encryptedMetadata.size)
                dataOutputStream.write(encryptedMetadata)
                dataOutputStream.flush()

                // Wait for receiver confirmation
                val isAccepted = dataInputStream.readBoolean()
                if (!isAccepted) {
                    throw IOException("Receiver declined file: $displayName")
                }

                // 5. Encrypt and Stream File content
                val fileIV = generateIV()
                dataOutputStream.writeInt(fileIV.size)
                dataOutputStream.write(fileIV)

                val fileCipher = Cipher.getInstance("AES/GCM/NoPadding")
                fileCipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))

                val fileInputStream = contentResolver.openInputStream(uri) ?: return@forEachIndexed
                val buffer = ByteArray(1024)
                var bytesRead: Int
                var totalBytesProcessed = 0L

                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    val output = fileCipher.update(buffer, 0, bytesRead)
                    if (output != null) {
                        dataOutputStream.write(output)
                    }
                    totalBytesProcessed += bytesRead
                    onProgress(index, totalBytesProcessed, fileSize)
                }
                val finalOutput = fileCipher.doFinal()
                if (finalOutput != null) {
                    dataOutputStream.write(finalOutput)
                }
                
                fileInputStream.close()
                dataOutputStream.flush()
            }
            socket.close()
        }
    }

    private fun getFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                cursor.getLong(sizeIndex)
            } else 0L
        } ?: 0L
    }

    suspend fun receiveFiles(
        contentResolver: ContentResolver,
        onReceiveRequest: suspend (String, Long) -> Boolean,
        onProgress: (String, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(PORT)
        runCatching {
            serverSocket.soTimeout = SOCKET_TIMEOUT
            val socket = serverSocket.accept()
            socket.setSoTimeout(SOCKET_TIMEOUT)
            
            val inputStream = socket.getInputStream()
            val dataInputStream = DataInputStream(inputStream)
            val outputStream = socket.getOutputStream()
            val dataOutputStream = DataOutputStream(outputStream)

            // 1. Generate and Send RSA Public Key
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(2048)
            val keyPair = keyPairGen.generateKeyPair()
            val pubKeyBytes = keyPair.public.encoded
            dataOutputStream.writeInt(pubKeyBytes.size)
            dataOutputStream.write(pubKeyBytes)

            // 2. Receive and Decrypt Session Key
            val encSessionKeySize = dataInputStream.readInt()
            val encSessionKey = ByteArray(encSessionKeySize)
            dataInputStream.readFully(encSessionKey)

            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            val oaepParams = OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
            )
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPair.private, oaepParams)
            val sessionKeyBytes = rsaCipher.doFinal(encSessionKey)
            val sessionKey = SecretKeySpec(sessionKeyBytes, "AES")

            // 3. Read File Count
            val fileCount = dataInputStream.readInt()

            repeat(fileCount) {
                // 4. Decrypt Metadata (Handshake)
                val ivSize = dataInputStream.readInt()
                val iv = ByteArray(ivSize)
                dataInputStream.readFully(iv)
                
                val metadataSize = dataInputStream.readInt()
                val encryptedMetadata = ByteArray(metadataSize)
                dataInputStream.readFully(encryptedMetadata)
                
                val metadataJson = JSONObject(String(decryptWithGCM(encryptedMetadata, sessionKey, iv)))
                val fileName = metadataJson.getString("fileName")
                val fileSize = metadataJson.getLong("fileSize")
                val mimeType = metadataJson.getString("mimeType")
                val expectedHash = metadataJson.optString("sha256", "")

                // Handle the onReceiveRequest callback to ask for user permission
                if (!onReceiveRequest(fileName, fileSize)) {
                    dataOutputStream.writeBoolean(false) // Decline
                    throw IOException("User declined file transfer")
                }
                dataOutputStream.writeBoolean(true) // Accept
                dataOutputStream.flush()

                // Sanitize filename
                val safeName = fileName.replace("..", "_").replace("/", "_").replace("\\", "_")

                // 5. Decrypt File content
                val fileIVSize = dataInputStream.readInt()
                val fileIV = ByteArray(fileIVSize)
                dataInputStream.readFully(fileIV)

                // MediaStore API for Android 11+ compatibility
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AirShare")
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to create MediaStore entry")

                try {
                    contentResolver.openOutputStream(uri)?.use { fileOutputStream ->
                        val fileCipher = Cipher.getInstance("AES/GCM/NoPadding")
                        fileCipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))

                        val encryptedFileSize = (fileSize + 16).toInt()
                        val buffer = ByteArray(1024)
                        var totalBytesReceived = 0L
                        
                        while (totalBytesReceived < encryptedFileSize) {
                            val toRead = min(buffer.size.toLong(), encryptedFileSize - totalBytesReceived).toInt()
                            dataInputStream.readFully(buffer, 0, toRead)
                            
                            val output = fileCipher.update(buffer, 0, toRead)
                            if (output != null) {
                                fileOutputStream.write(output)
                            }
                            totalBytesReceived += toRead
                            onProgress(fileName, min(totalBytesReceived, fileSize), fileSize)
                        }
                        
                        val finalOutput = fileCipher.doFinal()
                        if (finalOutput != null) {
                            fileOutputStream.write(finalOutput)
                        }

                        // Verify integrity
                        val verificationStream = contentResolver.openInputStream(uri)
                        val actualHash = verificationStream?.use { calculateHash(it) }
                        if (expectedHash.isNotEmpty() && actualHash != expectedHash) {
                            throw IOException("Hash verification failed for $fileName")
                        }
                    }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                
                onProgress(fileName, fileSize, fileSize)
            }
            socket.close()
        }.onFailure { e ->
            Log.e("FileTransferManager", "Transfer error", e)
        }.also {
            serverSocket.close()
        }
    }

    private fun generateIV(): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        return iv
    }

    private fun encryptWithGCM(data: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val iv = generateIV()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Pair(iv, cipher.doFinal(data))
    }

    private fun decryptWithGCM(data: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
