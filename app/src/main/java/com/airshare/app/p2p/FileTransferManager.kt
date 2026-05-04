package com.airshare.app.p2p

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class FileTransferManager {

    private val PORT = 8888
    private val AES_KEY = "AirShareSecureKey" // In production, generate this during handshake

    suspend fun sendFiles(host: String, files: List<File>, onProgress: (Int, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.bind(null)
            socket.connect(InetSocketAddress(host, PORT), 5000)

            val outputStream = socket.getOutputStream()
            val dataOutputStream = DataOutputStream(outputStream)

            // 1. Send File Count
            dataOutputStream.writeInt(files.size)

            files.forEachIndexed { index, file ->
                // 2. Encryption Handshake for Metadata
                val encryptedMetadata = encryptMetadata("${file.name}|${file.length()}")
                dataOutputStream.writeInt(encryptedMetadata.size)
                dataOutputStream.write(encryptedMetadata)

                // 3. Stream File content
                val fileInputStream = FileInputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesSent = 0L
                val fileSize = file.length()

                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    dataOutputStream.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    onProgress(index, totalBytesSent, fileSize)
                }
                fileInputStream.close()
                dataOutputStream.flush()
            }
            socket.close()
        } catch (e: Exception) {
            Log.e("Transfer", "Error sending files", e)
        }
    }

    suspend fun receiveFiles(outputDir: File, onProgress: (String, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(PORT)
        val socket = serverSocket.accept()
        
        val inputStream = socket.getInputStream()
        val dataInputStream = DataInputStream(inputStream)

        val fileCount = dataInputStream.readInt()

        repeat(fileCount) {
            val metadataSize = dataInputStream.readInt()
            val encryptedMetadata = ByteArray(metadataSize)
            dataInputStream.readFully(encryptedMetadata)
            
            val metadata = decryptMetadata(encryptedMetadata).split("|")
            val fileName = metadata[0]
            val fileSize = metadata[1].toLong()

            val outputFile = File(outputDir, fileName)
            val fileOutputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var totalRead = 0L
            
            while (totalRead < fileSize) {
                val toRead = Math.min(buffer.size.toLong(), fileSize - totalRead).toInt()
                val read = dataInputStream.read(buffer, 0, toRead)
                if (read == -1) break
                fileOutputStream.write(buffer, 0, read)
                totalRead += read
                onProgress(fileName, totalRead, fileSize)
            }
            fileOutputStream.close()
        }
        socket.close()
        serverSocket.close()
    }

    private fun encryptMetadata(data: String): ByteArray {
        val key = SecretKeySpec(AES_KEY.padEnd(16).substring(0, 16).toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data.toByteArray())
    }

    private fun decryptMetadata(data: ByteArray): String {
        val key = SecretKeySpec(AES_KEY.padEnd(16).substring(0, 16).toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(data))
    }
}
