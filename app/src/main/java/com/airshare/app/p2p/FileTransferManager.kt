package com.airshare.app.p2p

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class FileTransferManager {

    private val PORT = 8888

    suspend fun sendFiles(
        host: String,
        contentResolver: ContentResolver,
        files: List<Pair<Uri, String>>,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = Socket()
            socket.bind(null)
            socket.connect(InetSocketAddress(host, PORT), 5000)

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
                
                // 4. Encrypt and Send Metadata
                val metadata = "$displayName|$fileSize"
                val (iv, encryptedMetadata) = encryptWithGCM(metadata.toByteArray(), sessionKey)
                dataOutputStream.writeInt(iv.size)
                dataOutputStream.write(iv)
                dataOutputStream.writeInt(encryptedMetadata.size)
                dataOutputStream.write(encryptedMetadata)

                // 5. Encrypt and Stream File content
                val fileIV = generateIV()
                dataOutputStream.writeInt(fileIV.size)
                dataOutputStream.write(fileIV)

                val fileCipher = Cipher.getInstance("AES/GCM/NoPadding")
                fileCipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))

                val fileInputStream = contentResolver.openInputStream(uri) ?: return@forEachIndexed
                val buffer = ByteArray(8192)
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

    suspend fun receiveFiles(outputDir: File, onProgress: (String, Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(PORT)
        runCatching {
            val socket = serverSocket.accept()
            
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
                // 4. Decrypt Metadata
                val ivSize = dataInputStream.readInt()
                val iv = ByteArray(ivSize)
                dataInputStream.readFully(iv)
                
                val metadataSize = dataInputStream.readInt()
                val encryptedMetadata = ByteArray(metadataSize)
                dataInputStream.readFully(encryptedMetadata)
                
                val metadataString = String(decryptWithGCM(encryptedMetadata, sessionKey, iv))
                val parts = metadataString.split("|")
                val fileName = parts[0]
                val fileSize = parts[1].toLong()

                // Sanitize filename to prevent path traversal
                val safeName = fileName.replace("..", "_").replace("/", "_").replace("\\", "_")

                // 5. Decrypt File content
                val fileIVSize = dataInputStream.readInt()
                val fileIV = ByteArray(fileIVSize)
                dataInputStream.readFully(fileIV)

                // Read ALL encrypted bytes first (fileSize + 16-byte GCM tag)
                val encryptedFileSize = (fileSize + 16).toInt()
                val encryptedFileBytes = ByteArray(encryptedFileSize)
                dataInputStream.readFully(encryptedFileBytes)

                val fileCipher = Cipher.getInstance("AES/GCM/NoPadding")
                fileCipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(128, fileIV))
                
                val decryptedFileBytes = fileCipher.doFinal(encryptedFileBytes)

                val outputFile = File(outputDir, safeName)
                FileOutputStream(outputFile).use { it.write(decryptedFileBytes) }
                
                onProgress(fileName, fileSize, fileSize)
            }
            socket.close()
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
