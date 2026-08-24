package me.hletrd.telecampro.gl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderProgramCompileTest {
    @Test
    fun `shipping GLES program compiles links and exposes every required interface`() {
        val workspace = Files.createTempDirectory("telecam-glsl-")
        val vertex = workspace.resolve("telecam.vert")
        val fragment = workspace.resolve("telecam.frag")
        Files.writeString(vertex, Shaders.VERTEX)
        Files.writeString(fragment, Shaders.FRAGMENT)
        val validator = executableValidator(workspace)

        compile(validator, "vert", vertex)
        compile(validator, "frag", fragment)
        val linked = ProcessBuilder(validator.toString(), "-l", vertex.toString(), fragment.toString())
            .redirectErrorStream(true)
            .start()
        val linkOutput = linked.inputStream.bufferedReader().readText()
        assertEquals("GLSL link failed:\n$linkOutput", 0, linked.waitFor())

        val combined = Shaders.VERTEX + "\n" + Shaders.FRAGMENT
        assertTrue("required shader interface is missing: ${missingInterface(combined)}", missingInterface(combined).isEmpty())
    }

    @Test
    fun `validator rejects syntax and stage interface mutations`() {
        val workspace = Files.createTempDirectory("telecam-glsl-mutations-")
        val validator = executableValidator(workspace)
        val brokenVertex = workspace.resolve("syntax.vert")
        Files.writeString(brokenVertex, Shaders.VERTEX.replaceFirst("void main() {", "void main( {"))
        assertTrue(run(validator, "-S", "vert", brokenVertex.toString()).first != 0)

        val vertex = workspace.resolve("interface.vert")
        val fragment = workspace.resolve("interface.frag")
        Files.writeString(vertex, Shaders.VERTEX)
        Files.writeString(
            fragment,
            Shaders.FRAGMENT.replaceFirst("varying vec2 vTexCoord;", "varying vec3 vTexCoord;"),
        )
        assertTrue(run(validator, "-l", vertex.toString(), fragment.toString()).first != 0)
    }

    @Test
    fun `required interface audit rejects a renamed runtime uniform`() {
        val renamed = (Shaders.VERTEX + "\n" + Shaders.FRAGMENT)
            .replace("uDigitalGain", "uRenamedDigitalGain")

        assertEquals(setOf("uDigitalGain"), missingInterface(renamed))
    }

    private fun compile(validator: Path, stage: String, source: Path) {
        val (exit, output) = run(validator, "-S", stage, source.toString())
        assertEquals("GLSL $stage compilation failed:\n$output", 0, exit)
    }

    private fun run(validator: Path, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(validator.toString(), *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun missingInterface(source: String): Set<String> = REQUIRED_INTERFACE.filterTo(mutableSetOf()) { name ->
        !Regex("\\b$name\\b").containsMatchIn(source)
    }

    private fun executableValidator(workspace: Path): Path {
        val roots = buildList {
            System.getenv("ANDROID_SDK_ROOT")?.let { add(Path.of(it)) }
            System.getenv("ANDROID_HOME")?.let { add(Path.of(it)) }
            add(Path.of("/opt/homebrew/share/android-commandlinetools"))
            add(Path.of(System.getProperty("user.home"), "Library/Android/sdk"))
        }.distinct()
        val source = roots.asSequence()
            .map { it.resolve("emulator/lib64/vulkan/glslangValidator") }
            .firstOrNull(Files::isRegularFile)
            ?: error("Android Emulator glslangValidator is required by the host shader gate")
        if (Files.isExecutable(source)) return source

        // Homebrew's emulator archive can preserve the validator bytes without their executable
        // bit. Never mutate the shared SDK: make this test's private executable copy instead.
        val copy = workspace.resolve("glslangValidator")
        Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES)
        Files.setPosixFilePermissions(
            copy,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return copy
    }

    private companion object {
        val REQUIRED_INTERFACE = setOf(
            "aPosition", "aTexCoord", "uMvp", "uTexMatrix", "uTexture", "uTransfer",
            "uSourceHlg", "uPeaking", "uPeakThreshold", "uPeakColor", "uZebra",
            "uZebraThreshold", "uFalseColor", "uTexel", "uDigitalGain",
        )
    }
}
