package com.greenbird.utilihive.stubs.sftp

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
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

    var port: Int
        /**
         * Returns the port of the SFTP server.
         *
         * @return the port of the SFTP server.
         */
        get() {
            verifyWithSftpServerIsNotFinished("call getPort()")
            return server.port
        }
        /**
         * Set the port of the SFTP server. The SFTP server is restarted when you change port.
         * Value 0 means choosing any available port automatically.
         * @param port the port. Must be between 0 and 65535, where 0 means choose automatically.
         * @throws IllegalArgumentException if the port is not between 0 and 65535.
         * @throws IllegalStateException if the server cannot be restarted.
         */
        @Suppress("MagicNumber")
        set(port) {
            require(port in 0..65535) {
                ("Port cannot be set to $port because only ports between 0 and 65535 are valid.")
            }
            verifyWithSftpServerIsNotFinished("set port")
            restartServer(port)
        }

    companion object {
        fun withSftpServer(block: SftpServerTestContext.() -> Unit) {
            val server = SftpServerTestContext(createFileSystem())
            server.start(0)
            server.use(block)
        }

        private val RANDOM = Random()

        @Throws(IOException::class)
        private fun createFileSystem(): FileSystem {
            return MemoryFileSystemBuilder.newLinux().build("SftpServerTestContext-" + RANDOM.nextInt())
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
     * @return the server itself.
     */
    fun addUser(username: String, password: String): SftpServerTestContext {
        usernamesAndPasswords[username] = password
        return this
    }

    private fun restartServer(port: Int) =
        try {
            server.stop()
            start(port)
        } catch (e: IOException) {
            throw IllegalStateException("The SFTP server cannot be restarted.", e)
        }

    /**
     * Puts a text file to the SFTP folder. The file is available by the specified path.
     * @param path the path to the file
     * @param content the file's content
     * @param encoding the encoding of the file
     * @throws IOException if the file cannot be written
     */
    @Throws(IOException::class)
    fun putFile(
        path: String,
        content: String,
        encoding: Charset
    ) {
        val contentAsBytes = content.toByteArray(encoding)
        putFile(path, contentAsBytes)
    }

    /**
     * Puts a file to the SFTP folder. The file is available by the specified path.
     * @param path the path to the file
     * @param content the file's content
     * @throws IOException if the file cannot be written
     */
    @Throws(IOException::class)
    fun putFile(
        path: String,
        content: ByteArray
    ) {
        verifyWithSftpServerIsNotFinished("upload file")
        val pathAsObject = fileSystem.getPath(path)
        ensureDirectoryOfPathExists(pathAsObject)
        Files.write(pathAsObject, content)
    }

    /**
     * Puts a file to the SFTP folder. The file is available by the specified
     * path. The file's content is read from an `InputStream`.
     * @param path the path to the file
     * @param inputStream an `InputStream` that provides the file's content
     * @throws IOException if the file cannot be written or the input stream cannot be read
     */
    @Throws(IOException::class)
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
     * @throws IOException if the directory cannot be created
     */
    @Throws(IOException::class)
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
     * @throws IOException if at least one directory cannot be created.
     */
    @Throws(IOException::class)
    fun createDirectories(
        vararg paths: String
    ) {
        for (path in paths)
            createDirectory(path)
    }

    /**
     * Gets a text file's content from the SFTP server. The content is decoded
     * using the specified encoding.
     * @param path the path to the file
     * @param encoding the file's encoding
     * @return the content of the text file
     * @throws IOException if the file cannot be read
     */
    @Throws(IOException::class)
    fun getFileContent(
        path: String,
        encoding: Charset
    ): String = getFileContent(path).toString(encoding)

    /**
     * Gets a file from the SFTP server.
     * @param path the path to the file
     * @return the content of the file
     * @throws IOException if the file cannot be read
     */
    @Throws(IOException::class)
    fun getFileContent(
        path: String
    ): ByteArray {
        verifyWithSftpServerIsNotFinished("download file")
        val pathAsObject = fileSystem.getPath(path)
        return Files.readAllBytes(pathAsObject)
    }

    /**
     * Checks the existence of a file. Returns `true` iff the file exists
     * and it is not a directory.
     * @param path the path to the file
     * @return `true` iff the file exists and it is not a directory
     */
    fun existsFile(
        path: String
    ): Boolean {
        verifyWithSftpServerIsNotFinished("check existence of file")
        val pathAsObject = fileSystem.getPath(path)
        return Files.exists(pathAsObject) && !Files.isDirectory(pathAsObject)
    }

    /**
     * Deletes all files and directories.
     * @throws IOException if an I/O error is thrown while deleting the files
     * and directories
     */
    @Throws(IOException::class)
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

    @Throws(IOException::class)
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

    @Throws(IOException::class)
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

        @Throws(IOException::class)
        override fun newWatchService(): WatchService = fileSystem.newWatchService()
    }

    private class DoNotCloseFactory(val fileSystem: FileSystem) : FileSystemFactory {
        override fun getUserHomeDir(session: SessionContext): Path? = null

        override fun createFileSystem(session: SessionContext): FileSystem = DoNotClose(fileSystem)
    }
}