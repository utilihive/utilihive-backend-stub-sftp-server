package com.greenbird.utilihive.stubs.sftp

import com.greenbird.utilihive.stubs.sftp.SftpServerTestContext.Companion.withSftpServer
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import org.apache.commons.io.IOUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystemException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class SftpServerTestContextTest {

    companion object {
        private val DUMMY_CONTENT: ByteArray = byteArrayOf(1, 4, 2, 4, 2, 4)
        private val JSCH: JSch = JSch()
        private const val TIMEOUT = 200
    }

    private fun SftpServerTestContext.connectToServer(): Session =
        connectToServerAtPort(port)

    private fun connectToServerAtPort(port: Int): Session =
        createSessionWithCredentials("dummy user", "dummy password", port)
            .apply { connect(TIMEOUT) }

    private fun connectSftpChannel(session: Session): ChannelSftp =
        (session.openChannel("sftp") as ChannelSftp).apply { connect() }


    private fun SftpServerTestContext.connectAndDisconnect() {
        val session = connectToServer()
        val channel = connectSftpChannel(session)
        channel.disconnect()
        session.disconnect()
    }

    private fun createSessionWithCredentials(
        username: String,
        password: String,
        port: Int
    ): Session = JSCH.getSession(username, "127.0.0.1", port).apply {
        setConfig("StrictHostKeyChecking", "no")
        setPassword(password)
    }

    private fun SftpServerTestContext.createSessionWithCredentials(
        username: String,
        password: String
    ): Session =
        createSessionWithCredentials(username, password, port)

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
        assertThat(getPath(path).exists()).isFalse

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
        withSftpServer(port = 8394) {
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
                "Failed to upload file because withSftpServer is closed."
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
                "Failed to upload file because withSftpServer is closed."
            )
    }

    // Absolute vs relative path tests

    @Test
    fun `GIVEN initial dir root WHEN text file is put to a dir using absolute path THEN object has correct path`() =
        withSftpServer {
            putFile(
                "/some/dir/dummy_file.txt",
                "dummy content",
            )
            val path = getPath("/some/dir/dummy_file.txt")
            assertThat(path.exists()).isTrue

        }

    @Test
    fun `GIVEN initial dir root WHEN text file is put to a dir using relative path THEN object has correct path`() =
        withSftpServer {
            putFile(
                "some/dir/dummy_file.txt",
                "dummy content",
            )
            val path = getPath("/some/dir/dummy_file.txt")
            assertThat(path.exists()).isTrue
        }

    @Test
    fun `WHEN trying to set relative dir as sftp initial dir THEN exception thrown`() {
        assertThatThrownBy {
            withSftpServer(initialDirectory = "some") {}
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Initial directory must be absolute path.")
    }

    @Test
    fun `GIVEN changed initial dir WHEN text file is put to a dir using absolute path THEN object has correct path`() =
        withSftpServer(initialDirectory = "/some") {
            putFile(
                "/some/dir/dummy_file.txt",
                "dummy content",
            )
            val path = getPath("/some/dir/dummy_file.txt")
            assertThat(path.exists()).isTrue
        }

    @Test
    fun `GIVEN changed initial dir WHEN text file is put to a dir using relative path THEN object has correct path`() =
        withSftpServer(initialDirectory = "/some") {
            putFile(
                "dir/dummy_file.txt",
                "dummy content",
            )
            val path = getPath("/some/dir/dummy_file.txt")
            assertThat(path.exists()).isTrue
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
            .hasMessage("Failed to create directory because withSftpServer is closed.")
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
            .hasMessage("Failed to create directory because withSftpServer is closed.")
    }

    // File download tests

    @Test
    fun `GIVEN a text file is written to the server THEN it can be retrieved by the server object`() =
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.txt",
                "dummy content with umlaut ü".toByteArray(UTF_8)
            )
            val fileContent = getFileText("/dummy_directory/dummy_file.txt", UTF_8)
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
            serverCapture.get().getFileText("/dummy_directory/dummy_file.txt", UTF_8)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to download file because withSftpServer is closed.")
    }

    @Test
    fun `GIVEN a binary file is written to the server THEN it can be retrieved by the server object`() =
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.bin",
                DUMMY_CONTENT
            )
            val fileContent = getFileBytes("/dummy_directory/dummy_file.bin")
            assertThat(fileContent).isEqualTo(DUMMY_CONTENT)
        }

    @Test
    fun `WHEN calling getFileBytes on a directory THEN exception is thrown`() =
        withSftpServer {
            uploadFile(
                "/dummy_directory/dummy_file.bin",
                DUMMY_CONTENT
            )
            assertThatThrownBy {
                getFileBytes("/dummy_directory/")
            }.isInstanceOf(FileSystemException::class.java)
                .hasMessage("/dummy_directory: is not a file")
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
            serverCapture.get().getFileBytes("/dummy_directory/dummy_file.bin")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to download file because withSftpServer is closed.")
    }

    // File Path checks

    @Test
    fun `WHEN calling getPath on the server outside of the lambda THEN exception is thrown`() {
        val serverCapture = AtomicReference<SftpServerTestContext>()
        withSftpServer { serverCapture.set(this) }
        assertThatThrownBy {
            serverCapture.get().getPath("/dummy_file.bin")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Failed to get path because withSftpServer is closed."
            )
    }

    @Test
    fun `GIVEN file exists on the server WHEN calling getPath THEN correct path is returned`() =
        withSftpServer {
            uploadFile("/dummy_directory/dummy_file.bin", DUMMY_CONTENT)
            val path = getPath("/dummy_directory/dummy_file.bin")
            assertThat(path.exists()).isTrue
            assertThat(path.isRegularFile()).isTrue
            val path2 = getPath("/dummy_directory", "dummy_file.bin")
            assertThat(path2.exists()).isTrue
            assertThat(path2.isRegularFile()).isTrue
        }

    @Test
    fun `GIVEN directory exists on the server WHEN calling getPath THEN correct path is returned`() =
        withSftpServer {
            createDirectories("/dummy_directory")
            val path = getPath("/dummy_directory")
            assertThat(path.exists()).isTrue
            assertThat(path.isRegularFile()).isFalse
            assertThat(path.isDirectory()).isTrue
        }

    @Test
    fun `GIVEN file does not exist on the server WHEN calling getPath THEN path exists is false`() =
        withSftpServer {
            val path = getPath("/dummy_directory/dummy_file.bin")
            assertThat(path.exists()).isFalse
            assertThat(path.isRegularFile()).isFalse
        }


    // Server shutdown tests
    @Test
    fun `WHEN a test successfully finished THEN SFTP server is shutdown`() {
        val portCapture = AtomicInteger()
        withSftpServer { portCapture.set(port) }
        assertConnectionToSftpServerNotPossible(portCapture.get())
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
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
    fun `WHEN setting a negative port THEN correct exception thrown`() {
        assertThatThrownBy {
            withSftpServer(port = -1) { }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Port cannot be set to -1 because only ports between 0 and 65535 are valid."
            )
    }

    @Test
    fun `WHEN setting zero port THEN port set to random number`() {
        val portCapture = AtomicInteger()
        withSftpServer(port = 0) {
            portCapture.set(port)
        }
        assertThat(portCapture.get()).isBetween(1, 65535)
    }

    @Test
    fun `WHEN setting port to 1024 THEN server can be run`() {
        // In a perfect world I would test to set port to 1 but the lowest
        // port that can be used by a non-root user is 1024
        withSftpServer(port = 1024) { }
    }

    @Test
    fun `WHEN setting port to 65535 THEN server can be run`() =
        withSftpServer(port = 65535) {
            connectToServerAtPort(65535)
        }

    @Test
    fun `WHEN setting port greater than 65535 THEN correct exception thrown`() {
        assertThatThrownBy {
            withSftpServer(port = 65536) {}
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Port cannot be set to 65536 because only ports between 0 and 65535 are valid."
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
                "Failed to call getPort() because withSftpServer is closed."
            )
    }

    // Cleanup tests

    @Test
    fun `GIVEN file in root directory WHEN call deleteAllFilesAndDirectories THEN file in root directory is deleted`() =
        withSftpServer {
            uploadFile("/dummy_file.bin", DUMMY_CONTENT)
            deleteAllFilesAndDirectories()
            assertFileDoesNotExist("/dummy_file.bin")
        }

    @Test
    fun `GIVEN file in sub-directory WHEN call deleteAllFilesAndDirectories THEN file in sub-directory is deleted`() =
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
