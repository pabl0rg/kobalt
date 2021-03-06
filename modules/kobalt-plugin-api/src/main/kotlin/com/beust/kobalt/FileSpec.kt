package com.beust.kobalt

import com.beust.kobalt.misc.kobaltLog
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * Subclasses of IFileSpec can be turned into a list of files. There are two kings: FileSpec (a single file)
 * and GlobSpec (a spec defined by a glob, e.g. ** slash *Test.class)
 */
sealed class IFileSpec {
    abstract fun toFiles(baseDir: String?, filePath: String, excludes: List<Glob> = emptyList<Glob>()): List<File>

    class FileSpec(val spec: String) : IFileSpec() {
        override fun toFiles(baseDir: String?, filePath: String, excludes: List<Glob>) = listOf(File(spec))

        override fun toString() = spec
    }

    /**
     * A glob matcher, see http://docs.oracle.com/javase/7/docs/api/java/nio/file/FileSystem.html#getPathMatcher%28java.lang.String%29
     */
    class GlobSpec(val spec: List<String>) : IFileSpec() {

        constructor(spec: String) : this(arrayListOf(spec))

        private fun isIncluded(includeMatchers: Glob, excludes: List<Glob>, rel: Path) : Boolean {
            excludes.forEach {
                if (it.matches(rel)) {
                    kobaltLog(3, "Excluding ${rel.toFile()}")
                    return false
                }
            }
            if (includeMatchers.matches(rel)) {
                kobaltLog(3, "Including ${rel.toFile().path}")
                return true
            }
            kobaltLog(2, "Excluding ${rel.toFile()} (not matching any include pattern")
            return false
        }

        override fun toFiles(baseDir: String?, filePath: String, excludes: List<Glob>): List<File> {
            val result = arrayListOf<File>()
            val includes = Glob(*spec.toTypedArray())

            if (File(baseDir, filePath).isDirectory) {
                val orgRootDir = (if (File(filePath).isAbsolute) Paths.get(filePath)
                    else if (baseDir != null) Paths.get(baseDir, filePath)
                    else Paths.get(filePath)).run { normalize() }
                // Paths.get(".").normalize() returns an empty string, which is not a valid file :-(
                val rootDir = if (orgRootDir.toFile().path.isEmpty()) Paths.get("./") else orgRootDir
                if (rootDir.toFile().exists()) {
                    Files.walkFileTree(rootDir, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val path = p.normalize()
                            val rel = orgRootDir.relativize(path)
                            if (isIncluded(includes, excludes, path)) {
                                kobaltLog(3, "  including file " + rel.toFile() + " from rootDir $rootDir")
                                result.add(rel.toFile())
                            }
                            return FileVisitResult.CONTINUE
                        }
                    })
                } else {
                    throw AssertionError("Directory \"$rootDir\" should exist")
                }
            } else {
                if (isIncluded(includes, excludes, Paths.get(filePath))) {
                    result.add(File(filePath))
                }
            }

            return result
        }

        override fun toString(): String {
            var result = ""
            spec.apply {
                if (!isEmpty()) {
                    result += "Included files: " + joinToString { ", " }
                }
            }
            return result
        }
    }

}

/**
 * A Glob is a simple file name matcher.
 */
class Glob(vararg specs: String) {
    val matchers = prepareMatchers(specs.toList())

    private fun prepareMatchers(specs: List<String>): List<PathMatcher> =
            specs.map { it -> FileSystems.getDefault().getPathMatcher("glob:$it") }

    fun matches(s: String) = matches(Paths.get(s))

    fun matches(path: Path) = matchers.any { it.matches(path) }
}
