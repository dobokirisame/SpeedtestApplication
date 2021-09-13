package ru.scoltech.openran.speedtest.task.impl

import io.swagger.client.model.ServerAddr
import ru.scoltech.openran.speedtest.backend.IperfException
import ru.scoltech.openran.speedtest.backend.IperfRunner
import ru.scoltech.openran.speedtest.parser.IperfOutputParser
import ru.scoltech.openran.speedtest.task.FatalException
import ru.scoltech.openran.speedtest.task.Task
import ru.scoltech.openran.speedtest.util.IdleTaskKiller
import ru.scoltech.openran.speedtest.util.Promise
import ru.scoltech.openran.speedtest.util.TaskKiller
import java.io.IOException
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class StartIperfTask(
    private val writableDir: String,
    private val args: String,
    private val speedParser: IperfOutputParser,
    private val idleTimeoutMillis: Long,
    private val onStart: () -> Unit,
    private val onSpeedUpdate: (LongSummaryStatistics, Long) -> Unit,
    private val onFinish: (LongSummaryStatistics) -> Unit,
    private val onLog: (String, String, Exception?) -> Unit,
) : Task<ServerAddr, ServerAddr> {
    override fun prepare(
        argument: ServerAddr,
        killer: TaskKiller
    ): Promise<(ServerAddr) -> Unit, (String, Exception?) -> Unit> = Promise { onSuccess, _ ->
        val idleTaskKiller = IdleTaskKiller()
        val processor = IperfOutputProcessor(idleTaskKiller) { onSuccess?.invoke(argument) }

        val iperfRunner = IperfRunner.Builder(writableDir)
            .stdoutLinesHandler(processor::onIperfStdoutLine)
            .stderrLinesHandler(processor::onIperfStderrLine)
            .onFinishCallback(processor::onIperfFinish)
            .build()

        onStart()
        while (true) {
            try {
                // TODO validate not to have -c and -p in command
                iperfRunner.start(
                    "-c ${argument.ip} -p ${argument.portIperf} $args"
                )

                val task = {
                    try {
                        iperfRunner.sendSigKill()
                    } catch (e: IperfException) {
                        onLog(LOG_TAG, "Could not stop iPerf", e)
                    }
                }
                idleTaskKiller.registerBlocking(idleTimeoutMillis, task)
                killer.register(task)
                break
            } catch (e: InterruptedException) {
                onLog(LOG_TAG, "Interrupted iPerf start. Ignoring...", e)
            } catch (e: IperfException) {
                throw FatalException("Could not start iPerf", e)
            }
        }
    }

    private inner class IperfOutputProcessor(
        private val idleTaskKiller: IdleTaskKiller,
        private val onFinish: () -> Unit,
    ) {
        private val lock = ReentrantLock()
        private val speedStatistics = LongSummaryStatistics()

        fun onIperfStdoutLine(line: String) {
            onLog("iPerf stdout", line, null)
            idleTaskKiller.updateTaskState()
            val speed = try {
                speedParser.parseSpeed(line)
            } catch (e: IOException) {
                onLog("Speed parser", "Invalid stdout format", e)
                return
            }

            lock.withLock {
                speedStatistics.accept(speed)
                onSpeedUpdate(speedStatistics, speed)
            }
        }

        fun onIperfStderrLine(line: String) {
            onLog("iPerf stderr", line, null)
            idleTaskKiller.updateTaskState()
        }

        fun onIperfFinish() {
            lock.withLock {
                onFinish(speedStatistics)
            }
            onFinish()
        }
    }

    companion object {
        const val LOG_TAG = "StartIperfTask"
    }
}
