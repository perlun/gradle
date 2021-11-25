/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import org.gradle.api.InvalidUserCodeException
import org.gradle.configuration.internal.UserCodeApplicationContext
import org.gradle.configurationcache.problems.DocumentationSection
import org.gradle.configurationcache.problems.ProblemsListener
import org.gradle.configurationcache.problems.PropertyProblem
import org.gradle.configurationcache.problems.StructuredMessage
import org.gradle.configurationcache.problems.location
import org.gradle.configurationcache.serialization.Workarounds
import org.gradle.internal.classpath.Instrumented
import org.gradle.internal.event.ListenerManager
import org.gradle.internal.operations.BuildOperationAncestryTracker
import org.gradle.internal.operations.CurrentBuildOperationRef
import org.gradle.internal.service.scopes.Scopes
import org.gradle.internal.service.scopes.ServiceScope
import java.util.Objects


private
val allowedProperties = setOf(
    "os.name",
    "os.version",
    "os.arch",
    "java.version",
    "java.version.date",
    "java.vendor",
    "java.vendor.url",
    "java.vendor.version",
    "java.specification.version",
    "java.specification.vendor",
    "java.specification.name",
    "java.vm.version",
    "java.vm.specification.version",
    "java.vm.specification.vendor",
    "java.vm.specification.name",
    "java.vm.version",
    "java.vm.vendor",
    "java.vm.name",
    "java.class.version",
    "java.home",
    "java.class.path",
    "java.library.path",
    "java.compiler",
    "file.separator",
    "path.separator",
    "line.separator",
    "user.name",
    "user.home",
    "java.runtime.version"
    // Not java.io.tmpdir and user.dir at this stage
)


@ServiceScope(Scopes.BuildTree::class)
class InstrumentedInputAccessListener(
    private val problems: ProblemsListener,
    private val userCodeContext: UserCodeApplicationContext,
    private val taskExecutionTracker: TaskExecutionTracker,
    private val buildOperationAncestryTracker: BuildOperationAncestryTracker,
    listenerManager: ListenerManager,
) : Instrumented.Listener {

    private
    val broadcast = listenerManager.getBroadcaster(UndeclaredBuildInputListener::class.java)

    private
    val currentBuildOperationRef = CurrentBuildOperationRef.instance()

    override fun systemPropertyQueried(key: String, value: Any?, consumer: String) {
        if (allowedProperties.contains(key) || Workarounds.canReadSystemProperty(consumer)) {
            return
        }
        broadcast.systemPropertyRead(key, value, consumer)
    }

    override fun envVariableQueried(key: String, value: String?, consumer: String) {
        if (Workarounds.canReadEnvironmentVariable(consumer)) {
            return
        }
        broadcast.envVariableRead(key, value, consumer)
    }

    override fun externalProcessStarted(command: String, consumer: String?) {
        if (taskExecutionTracker.isCurrentThreadExecutingTask()) {
            logger.info("Skip $command because it is invoked in a task")
            return
        }
        val currentBuildOperation = currentBuildOperationRef.get()
        if (currentBuildOperation != null) {
            val taskAncestorId = buildOperationAncestryTracker.findClosestMatchingAncestor(currentBuildOperation.id) { id ->
                taskExecutionTracker.currentRunningTaskOperations.find { ref -> Objects.equals(ref.id, id) } != null
            }
            if (taskAncestorId.isPresent) {
                val taskAncestor = taskExecutionTracker.currentRunningTaskOperations.find { ref -> Objects.equals(ref.id, taskAncestorId.get()) }
                logger.info("Skip $command because it is invoked in a child operation $currentBuildOperation of task $taskAncestor")
                return
            }
        }

        // Starting external process is always an error because the configuration cache cannot fingerprint it in general.
        val message = StructuredMessage.build {
            text("external process started ")
            reference(command)
        }
        val exception = InvalidUserCodeException(message.toString())
        problems.onProblem(PropertyProblem(userCodeContext.location(consumer), message, exception, DocumentationSection.RequirementsUndeclaredExternalProcessUse))
    }
}
