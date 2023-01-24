package com.greenbird.utilihive.stubs.sftp

import com.greenbird.utilihive.stubs.sftp.SftpServerTestContext.Companion.withSftpServer
import com.jcraft.jsch.*
import org.apache.commons.io.IOUtils
import org.assertj.core.api.Assertions.*
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SftpServerTestContextTest {

    companion object {
        private val DUMMY_CONTENT: ByteArray = byteArrayOf(1, 4, 2, 4, 2, 4)
        private const val DUMMY_PORT = 46354
        private val JSCH: JSch = JSch()
        private const val TIMEOUT = 200
    }

    @Throws(JSchException::class)
    private fun SftpServerTestContext.connectToServer(): Session =
        connectToServerAtPort(port)

    @Throws(JSchException::class)
    private fun connectToServerAtPort(port: Int): Session =
        createSessionWithCredentials("dummy user", "dummy password", port)
            .apply { connect(TIMEOUT) }

    @Throws(JSchException::class)
    private fun connectSftpChannel(session: Session): ChannelSftp =
        (session.openChannel("sftp") as ChannelSftp).apply { connect() }


    @Throws(JSchException::class)
    private fun SftpServerTestContext.connectAndDisconnect() {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        channel.disconnect()
        session.disconnect()
    }

    @Throws(JSchException::class)
    private fun createSessionWithCredentials(
        username: String,
        password: String,
        port: Int
    ): Session = JSCH.getSession(username, "127.0.0.1", port).apply {
        setConfig("StrictHostKeyChecking", "no")
        setPassword(password)
    }

    @Throws(JSchException::class)
    private fun SftpServerTestContext.createSessionWithCredentials(
        username: String,
        password: String
    ): Session =
        createSessionWithCredentials(username, password, port)

    @Throws(JSchException::class, SftpException::class, IOException::class)
    private fun SftpServerTestContext.downloadFile(
        path: String
    ): ByteArray {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        return try {
            IOUtils.toByteArray(channel[path])
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    @Throws(JSchException::class, SftpException::class)
    private fun SftpServerTestContext.uploadFile(
        pathAsString: String,
        content: ByteArray,
    ) {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        try {
            val path = Paths.get(pathAsString)
            if (path.parent != path.root) channel.mkdir(path.parent.toString())
            channel.put(ByteArrayInputStream(content), pathAsString)
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    private fun assertAuthenticationFails(
        connectToServer: ThrowableAssert.ThrowingCallable
    ) = assertThatThrownBy(connectToServer)
        .isInstanceOf(JSchException::class.java)
        .hasMessage("Auth fail for methods 'password,keyboard-interactive,publickey'")

    @Throws(JSchException::class, SftpException::class)
    private fun SftpServerTestContext.assertEmptyDirectory(directory: String) {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        val entries = channel.ls(directory)
        assertThat(entries).hasSize(2) // these are the entries . and ..
        channel.disconnect()
        session.disconnect()
    }

    private fun assertConnectionToSftpServerNotPossible(port: Int) =
        assertThatThrownBy { connectToServerAtPort(port) }
            .withFailMessage("SFTP server is still running on port %d.", port)
            .hasCauseInstanceOf(ConnectException::class.java)

    private fun SftpServerTestContext.assertFileDoesNotExist(path: String) =
        assertThat(existsFile(path)).isFalse

    private fun SftpServerTestContext.assertDirectoryDoesNotExist(directory: String) {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        try {
            assertThatThrownBy { channel.ls(directory) }
                .isInstanceOf(SftpException::class.java)
                .hasMessage("No such file or directory")
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    // Round-trip tests

    @Test
    fun `WHEN a file is written to the SFTP server THEN it can be read and has same content`() =
        withSftpServer {
            val session = connectToServer()
            val channel = connectSftpChannel(session)
            channel.put(
                ByteArrayInputStream(
                    "dummy content".toByteArray(UTF_8)
                ),
                "dummy file.txt"
            )
            val file = channel["dummy file.txt"]
            assertThat(IOUtils.toString(file, UTF_8))
                .isEqualTo("dummy content")
            channel.disconnect()
            session.disconnect()
        }

    // Connection tests

    @Test
    fun `GIVEN running SFTP stub THEN multiple connections to the server are possible`() =
        withSftpServer {
            connectAndDisconnect()
            connectAndDisconnect()
        }

    @Test
    fun `WHEN overriding a port THEN a client can connect to the server at the specified port`() =
        withSftpServer {
            port = 8394
            connectToServerAtPort(8394)
        }

    // Authentication tests

    @Test
    fun `GIVEN server without credentials THEN the server accepts connections with password`() =
        withSftpServer {
            val session = createSessionWithCredentials(
                "dummy user",
                "dummy password"
            )
            session.connect(TIMEOUT)
        }

    @Test
    fun `GIVEN server with credentials THEN the server accepts connections with correct password`() =
        withSftpServer {
            addUser("dummy user", "dummy password")
            val session = createSessionWithCredentials(
                "dummy user",
                "dummy password"
            )
            session.connect(TIMEOUT)
        }

    @Test
    fun `GIVEN server with credentials THEN the server rejects connections with wrong password`() =
        withSftpServer {
            addUser("dummy user", "correct password")
            val session = createSessionWithCredentials(
                "dummy user",
                "wrong password"
            )
            assertAuthenticationFails { session.connect(TIMEOUT) }
        }

    @Test
    fun `GIVEN server with credentials WHEN addUser is called multiple times THEN the last password is effective`() =
        withSftpServer {
            addUser("dummy user", "first password")
            addUser("dummy user", "second password")
            val session = createSessionWithCredentials(
                "dummy user",
                "second password"
            )
            session.connect(TIMEOUT)
        }

    // File upload tests

    @Test
    fun `WHEN a text file is put to root directory by the server THEN object can be read from server`() =
        withSftpServer {
            putFile(
                "/dummy_file.txt",
                "dummy content with umlaut ü",
                UTF_8
            )
            val file = downloadFile("/dummy_file.txt")
            assertThat(file.toString(UTF_8))
                .isEqualTo("dummy content with umlaut ü")
        }

    @Test
    fun `WHEN a text file is put to directory by the server object can be read from server`() =
        withSftpServer {
            putFile(
                "/dummy_directory/dummy_file.txt",
                "dummy content with umlaut ü",
                UTF_8
            )
            val file = downloadFile("/dummy_directory/dummy_file.txt")
            assertThat(file.toString(UTF_8)).isEqualTo("dummy content with umlaut ü")
        }

    @Test
    fun `WHEN putting a text file on the server outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer {
            serverCapture.set(this)
        }
        assertThatThrownBy {
            serverCapture.get().putFile(
                "/dummy_file.txt", "dummy content", UTF_8
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to upload file because withSftpServer is already finished."
            )
    }

    @Test
    fun `WHEN a binary file is put to the root directory by the server THEN object can be read from server`() =
        withSftpServer {
            putFile("/dummy_file.bin", DUMMY_CONTENT)
            val file = downloadFile("/dummy_file.bin")
            assertThat(file).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `WHEN a binary file is put to a directory by the server THEN object can be read from server`() =
        withSftpServer {
            putFile("/dummy_directory/dummy_file.bin", DUMMY_CONTENT)
            val file = downloadFile("/dummy_directory/dummy_file.bin")
            assertThat(file).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `WHEN putting a binary file on the server outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer {
            serverCapture.set(this)
        }
        assertThatThrownBy {
            serverCapture.get().putFile("/dummy_file.bin", DUMMY_CONTENT)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to upload file because withSftpServer is already finished."
            )
    }

    @Test
    fun `WHEN a file from a stream is put to the root directory by the server THEN object can be read from server`() =
        withSftpServer {
            putFile("/dummy_file.bin", ByteArrayInputStream(DUMMY_CONTENT))
            val file = downloadFile("/dummy_file.bin")
            assertThat(file).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `WHEN a file from a stream is put to a directory by the server THEN object can be read from server`() =
        withSftpServer {
            putFile("/dummy_directory/dummy_file.bin", ByteArrayInputStream(DUMMY_CONTENT))
            val file = downloadFile("/dummy_directory/dummy_file.bin")
            assertThat(file).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `WHEN putting a file from a stream on the server outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer {
            serverCapture.set(this)
        }
        assertThatThrownBy {
            serverCapture.get().putFile("/dummy_file.bin", ByteArrayInputStream(DUMMY_CONTENT))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to upload file because withSftpServer is already finished."
            )
    }

    // Directory creation tests

    @Test
    fun `WHEN a single directory is created by the server THEN it can be read by the client`() =
        withSftpServer {
            createDirectory("/a/directory")
            assertEmptyDirectory("/a/directory")
        }

    @Test
    fun `WHEN a single directory created outside of the lambda THEN an exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy { serverCapture.get().createDirectory("/a/directory") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to create directory because withSftpServer is already finished.")
    }

    @Test
    fun `WHEN multiple directories are created by the server THEN they can be read by the client`() =
        withSftpServer {
            createDirectories(
                "/a/directory",
                "/another/directory",
            )
            assertEmptyDirectory("/a/directory")
            assertEmptyDirectory("/another/directory")
        }

    @Test
    fun `WHEN multiple directories are created outside of the lambda THEN an exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy {
            serverCapture.get().createDirectories(
                "/a/directory",
                "/another/directory"
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to create directory because withSftpServer is already finished.")
    }

    // File download tests

    @Test
    fun `GIVEN a text file is written to the server THEN it can be retrieved by the server object`() =
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.txt",
                "dummy content with umlaut ü".toByteArray(UTF_8)
            )
            val fileContent = getFileContent("/dummy_directory/dummy_file.txt", UTF_8)
            assertThat(fileContent).isEqualTo("dummy content with umlaut ü")
        }

    @Test
    fun `GIVEN a text file is retrieved outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.txt",
                "dummy content".toByteArray(UTF_8)
            )
            serverCapture.set(this)
        }
        assertThatThrownBy {
            serverCapture.get().getFileContent("/dummy_directory/dummy_file.txt", UTF_8)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to download file because withSftpServer is already finished.")
    }

    @Test
    fun `GIVEN a binary file is written to the server THEN it can be retrieved by the server object`() =
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.bin",
                DUMMY_CONTENT
            )
            val fileContent = getFileContent("/dummy_directory/dummy_file.bin")
            assertThat(fileContent).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `GIVEN a binary file is retrieved outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.bin",
                DUMMY_CONTENT
            )
            serverCapture.set(this)
        }
        assertThatThrownBy {
            serverCapture.get().getFileContent("/dummy_directory/dummy_file.bin")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to download file because withSftpServer is already finished.")
    }

    // File existence check

    @Test
    fun `GIVEN file exists on the server THEN existsFile returns true`() =
        withSftpServer {
            uploadFile("/dummy_directory/dummy_file.bin", DUMMY_CONTENT)
            val exists = existsFile("/dummy_directory/dummy_file.bin")
            assertThat(exists).isTrue
        }

    @Test
    fun `GIVEN file does not exists on the server THEN existsFile returns false`() =
        withSftpServer {
            val exists = existsFile("/dummy_directory/dummy_file.bin")
            assertThat(exists).isFalse
        }

    @Test
    fun `GIVEN a directory exists on the server THEN calling existsFile on it returns false`() =
        withSftpServer {
            uploadFile("/dummy_directory/dummy_file.bin", DUMMY_CONTENT)
            val exists = existsFile("/dummy_directory")
            assertThat(exists).isFalse
        }

    @Test
    fun `WHEN checking a file on the server outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy {
            serverCapture.get().existsFile("/dummy_file.bin")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to check existence of file because withSftpServer is already finished."
            )
    }

    // Server shutdown tests
    @Test
    fun `WHEN a test successfully finished THEN SFTP server is shutdown`() {
        val portCapture = AtomicInteger()
        withSftpServer { portCapture.set(port) }
        assertConnectionToSftpServerNotPossible(portCapture.get())
    }

    @Test
    @Suppress("TooGenericExceptionThrown", "SwallowedException")
    fun `WHEN a test errors THEN SFTP server is shutdown`() {
        val portCapture = AtomicInteger()
        try {
            withSftpServer {
                portCapture.set(port)
                throw RuntimeException()
            }
        } catch (_: Throwable) { // ignored intentionally
        }
        assertConnectionToSftpServerNotPossible(portCapture.get())
    }

    @Test
    fun `GIVEN running test WHEN a port was changed during test THEN first SFTP server is shutdown`() {
        val portCapture = AtomicInteger()
        withSftpServer {
            portCapture.set(port)
            port = 0 // random
        }
        assertConnectionToSftpServerNotPossible(portCapture.get())
    }

    @Test
    fun `GIVEN running test WHEN a port was changed during test THEN second SFTP server is shutdown`() {
        withSftpServer {
            port = DUMMY_PORT
            port = 0 // random
        }
        assertConnectionToSftpServerNotPossible(DUMMY_PORT)
    }


    // Port selection tests

    @Test
    fun `WHEN running two servers THEN by default they run at different ports`() {
        val portCaptureForFirstServer = AtomicInteger()
        val portCaptureForSecondServer = AtomicInteger()
        withSftpServer {
            portCaptureForFirstServer.set(port)
            withSftpServer {
                portCaptureForSecondServer.set(port)
            }
        }
        assertThat(portCaptureForFirstServer)
            .doesNotHaveValue(portCaptureForSecondServer.get())
    }

    @Test
    fun `WHEN changing port during a test THEN client can connect to the server at the new port`() =
        withSftpServer {
            port = 0 // random
            connectToServerAtPort(port)
        }

    @Test
    fun `WHEN setting a negative port THEN correct exception thrown`() {
        assertThatThrownBy {
            withSftpServer { port = -1 }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Port cannot be set to -1 because only ports between 0 and 65535 are valid."
            )
    }

    @Test
    fun `WHEN setting zero port THEN port set to random number`() {
        val portCapture = AtomicInteger()
        withSftpServer {
            port = 0
            portCapture.set(port)
        }
        assertThat(portCapture.get()).isBetween(1, 65535)
    }

    @Test
    fun `WHEN setting port to 1024 THEN server can be run`() {
        // In a perfect world I would test to set port to 1 but the lowest
        // port that can be used by a non-root user is 1024
        withSftpServer { port = 1024 }
    }

    @Test
    fun `WHEN setting port to 65535 THEN server can be run`() =
        withSftpServer {
            port = 65535
            connectToServerAtPort(65535)
        }

    @Test
    fun `WHEN setting port greater than 65535 THEN correct exception thrown`() {
        assertThatThrownBy {
            withSftpServer { port = 65536 }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Port cannot be set to 65536 because only ports between 0 and 65535 are valid."
            )
    }

    @Test
    fun `WHEN setting the port outside of the lambda THEN correct exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy { serverCapture.get().port = DUMMY_PORT }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to set port because withSftpServer is already finished."
            )
    }

    // Port query tests

    @Test
    fun `GIVEN running test THEN port can be read during the test`() {
        val portCapture = AtomicInteger()
        withSftpServer { portCapture.set(port) }
        assertThat(portCapture).doesNotHaveValue(0)
    }

    @Test
    fun `GIVEN finished test THEN port cannot be read after the test`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy { serverCapture.get().port }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to call getPort() because withSftpServer is already finished."
            )
    }

    // Cleanup tests

    @Test
    fun `GIVEN file in root directory WHEN calling deleteAllFilesAndDirectories THEN file in root directory is deleted`() =
        withSftpServer {
            uploadFile("/dummy_file.bin", DUMMY_CONTENT)
            deleteAllFilesAndDirectories()
            assertFileDoesNotExist("/dummy_file.bin")
        }

    @Test
    fun `GIVEN file in sub-directory WHEN calling deleteAllFilesAndDirectories THEN file in sub-directory is deleted`() =
        withSftpServer {
            uploadFile("/dummy_directory/dummy_file.bin", DUMMY_CONTENT)
            deleteAllFilesAndDirectories()
            assertFileDoesNotExist("/dummy_directory/dummy_file.bin")
        }

    @Test
    fun `GIVEN a directory WHEN calling deleteAllFilesAndDirectories THEN the directory is deleted`() =
        withSftpServer {
            createDirectory("/dummy_directory")
            deleteAllFilesAndDirectories()
            assertDirectoryDoesNotExist("/dummy_directory")
        }

    @Test
    fun `GIVEN empty file system WHEN calling deleteAllFilesAndDirectories THEN call succeeds`() =
        withSftpServer { deleteAllFilesAndDirectories() }

}
