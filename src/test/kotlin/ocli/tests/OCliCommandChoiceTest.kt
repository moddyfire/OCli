package ocli.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ocli.*
import java.io.File

interface Command

data class PushCommand(val source: String) : Command
data class PullCommand(val target: String): Command

class OCliCommandChoiceTest : FunSpec({

    test("sub command") {

        data class Commands(
            val push: PushCommand,
            val pull: PullCommand
        )

        data class Data(
            val count: Int,
            @OCliOneOf(Commands::class) val cmd: Command
        )
        val builder = OCli.builder<Data>()
        val data = builder.build("--count", "1", "push", "--source=Alice")

        data shouldBe Data(1, PushCommand("Alice"))
    }

})

