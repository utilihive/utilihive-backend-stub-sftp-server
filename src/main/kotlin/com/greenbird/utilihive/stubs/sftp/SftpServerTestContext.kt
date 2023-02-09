package com.greenbird.utilihive.stubs.sftp

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.Charsets.UTF_8

@Suppress("TooManyFunctions")
class SftpServerTestContext
/**
 * `SftpServerTestContext` cannot be created manually. It is always provided
 * to a builder by [.withSftpServer].
 * @param fileSystem the file system that is used for storing the files
 */
private constructor(private val fileSystem: FileSystem) : Closeable {

    private lateinit var server: SshServer
    private var withSftpServerFinished = false
    private val usernamesAndPasswords: MutableMap<String, String> = HashMap()

    val port: Int
        /**
         * Returns the port of the SFTP server.
         *
         * @return the port of the SFTP server.
         */
        get() {
            verifyWithSftpServerIsNotFinished("call getPort()")
            return server.port
        }

    companion object {
        fun withSftpServer(port: Int = 0, block: SftpServerTestContext.() -> Unit) {
            @Suppress("MagicNumber")
            require(port in 0..65535) {
                ("Port cannot be set to $port because only ports between 0 and 65535 are valid.")
            }
            val server = SftpServerTestContext(createFileSystem())
            server.start(port)
            server.use(block)
        }

        private val SEQUENCE = AtomicInteger()

        private fun createFileSystem(): FileSystem {
            return MemoryFileSystemBuilder.newLinux().build(
                "SftpServerTestContext-"
                        + SEQUENCE.incrementAndGet()
            )
        }
    }

    override fun close() {
        withSftpServerFinished = true
        server.stop()
    }

    /**
     * Register a username with its password. After registering a username
     * it is only possible to connect to the server with one of the registered
     * username/password pairs.
     *
     * If `addUser` is called multiple times with the same username but
     * different passwords then the last password is effective.
     * @param username the username.
     * @param password the password for the specified username.
     * @return the current server context.
     */
    fun addUser(username: String, password: String): SftpServerTestContext {
        usernamesAndPasswords[username] = password
        return this
    }

    /**
     * Puts a text file to the SFTP folder. The file is available by the specified path.
     * @param path the path to the file
     * @param content the file's content
     * @param encoding the encoding of the file
     */
    fun putFile(
        path: String,
        content: String,
        encoding: Charset = UTF_8
    ) {
        val contentAsBytes = content.toByteArray(encoding)
        putFile(path, ByteArrayInputStream(contentAsBytes))
    }

    /**
     * Puts a file to the SFTP folder. The file is available by the specified
     * path. The file's content is read from an `InputStream`.
     * @param path the path to the file
     * @param inputStream an `InputStream` that provides the file's content
     */
    fun putFile(
        path: String,
        inputStream: InputStream
    ) {
        verifyWithSftpServerIsNotFinished("upload file")
        val pathAsObject = fileSystem.getPath(path)
        ensureDirectoryOfPathExists(pathAsObject)
        Files.copy(inputStream, pathAsObject)
    }

    /**
     * Creates a directory on the SFTP server.
     * @param path the directory's path
     */
    fun createDirectory(
        path: String
    ) {
        verifyWithSftpServerIsNotFinished("create directory")
        val pathAsObject = fileSystem.getPath(path)
        Files.createDirectories(pathAsObject)
    }

    /**
     * Create multiple directories on the SFTP server.
     * @param paths the directories' paths.
     */
    fun createDirectories(
        vararg paths: String
    ) {
        for (path in paths)
            createDirectory(path)
    }

    /**
     * Gets a text file's content from the SFTP server. The content is decoded
     * using the specified encoding (UTF-8 if not specified).
     * @param path the path to the file
     * @param encoding the file's encoding
     * @return the content of the text file
     */
    fun getFileText(
        path: String,
        encoding: Charset = UTF_8
    ): String = getFileBytes(path).toString(encoding)

    /**
     * Gets a file from the SFTP server.
     * @param path the path to the file
     * @return the byte array with content of the file
     */
    fun getFileBytes(
        path: String
    ): ByteArray {
        verifyWithSftpServerIsNotFinished("download file")
        val pathAsObject = fileSystem.getPath(path)
        return Files.readAllBytes(pathAsObject)
    }

    /**
     * Converts a path string, or a sequence of strings that when joined form
     * a path string, to a Path. If more does not specify any elements then
     * the value of the first parameter is the path string to convert.
     * If more specifies one or more elements, then each non-empty string,
     * including first, is considered to be a sequence of name elements (see Path)
     * and is joined to form a path string. Strings are joined using '/' as the separator.
     * For example, if getPath("/foo","bar","gus") is invoked, then the path string
     * "/foo/bar/gus" is converted to a Path. A Path representing an empty path is returned
     * if first is the empty string and more does not contain any non-empty strings.
     */
    fun getPath(first: String, vararg more: String): Path {
        verifyWithSftpServerIsNotFinished("get path")
        return fileSystem.getPath(first, *more)
    }

    /**
     * Deletes all files and directories.
     */
    fun deleteAllFilesAndDirectories() {
        for (directory in fileSystem.rootDirectories)
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (dir.parent != null) Files.delete(dir)
                    return super.postVisitDirectory(dir, exc)
                }
            })
    }

    private fun start(port: Int): Closeable {
        val server = SshServer.setUpDefaultServer()
        server.port = port
        server.keyPairProvider = SimpleGeneratorHostKeyProvider()
        server.passwordAuthenticator =
            PasswordAuthenticator { username: String, password: String, _: ServerSession ->
                authenticate(username, password)
            }
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        /* When a channel is closed SshServer calls close() on the file system.
         * In order to use the file system for multiple channels/sessions we
         * have to use a file system wrapper whose close() does nothing.
         */
        server.fileSystemFactory = DoNotCloseFactory(fileSystem)
        server.start()
        this.server = server
        return Closeable { this.server.close() }
    }

    private fun authenticate(username: String, password: String): Boolean =
        usernamesAndPasswords.isEmpty() || usernamesAndPasswords[username] == password

    private fun ensureDirectoryOfPathExists(path: Path) {
        val directory = path.parent
        if (directory != null && directory != path.root)
            Files.createDirectories(directory)
    }

    private fun verifyWithSftpServerIsNotFinished(task: String) =
        check(!withSftpServerFinished) { "Failed to $task because withSftpServer is already finished." }

    private class DoNotClose(val fileSystem: FileSystem) : FileSystem() {
        override fun provider(): FileSystemProvider = fileSystem.provider()

        override fun close() = Unit //will not be closed

        override fun isOpen(): Boolean = fileSystem.isOpen

        override fun isReadOnly(): Boolean = fileSystem.isReadOnly

        override fun getSeparator(): String = fileSystem.separator

        override fun getRootDirectories(): Iterable<Path> = fileSystem.rootDirectories

        override fun getFileStores(): Iterable<FileStore> = fileSystem.fileStores

        override fun supportedFileAttributeViews(): Set<String> =
            fileSystem.supportedFileAttributeViews()

        override fun getPath(first: String, vararg more: String): Path =
            fileSystem.getPath(first, *more)

        override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
            fileSystem.getPathMatcher(syntaxAndPattern)

        override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
            fileSystem.userPrincipalLookupService

        override fun newWatchService(): WatchService = fileSystem.newWatchService()
    }

    private class DoNotCloseFactory(val fileSystem: FileSystem) : FileSystemFactory {
        override fun getUserHomeDir(session: SessionContext): Path? = null

        override fun createFileSystem(session: SessionContext): FileSystem = DoNotClose(fileSystem)
    }
}
