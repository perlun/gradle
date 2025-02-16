/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.integtests.resolve

import groovy.test.NotYetImplemented
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionReasons
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.DefaultComponentSelectionDescriptor
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.internal.Describables
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.ImmutableCapability
import org.gradle.internal.component.local.model.DefaultLibraryComponentSelector
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Issue

class DependencyManagementResultsAsInputsIntegrationTest extends AbstractHttpDependencyResolutionTest {

    def setup() {
        server.start()

        settingsFile << """
            includeBuild 'composite-lib'
            rootProject.name = 'root'
            include 'project-lib'
        """
        def variantDeclaration = { sysPropName ->
            """
                def myAttribute = Attribute.of("my.attribute.name", String)
                dependencies.attributesSchema { attribute(myAttribute) }
                configurations {
                    runtimeElements {
                        attributes { attribute(myAttribute, System.getProperty('$sysPropName', 'default-value')) }
                    }
                }
            """
        }
        file('composite-lib/settings.gradle') << ""
        file('composite-lib/build.gradle') << """
            plugins { id 'java-library' }
            group = 'composite-lib'
            ${variantDeclaration('compositeLibAttrValue')}
        """
        def util = mavenHttpRepo.module("org.external", "external-util").publish().allowAll()
        mavenHttpRepo.module("org.external", "external-lib")
            .dependsOn(util)
            .publish()
            .allowAll()
        mavenHttpRepo.module("org.external", "external-lib2")
            .dependsOn(util)
            .withModuleMetadata()
            .publish()
            .allowAll()
        mavenHttpRepo.module("org.external", "external-tool").publish().allowAll()
        file('lib/file-lib.jar') << 'content'
        buildFile << """
            project(':project-lib') {
                apply plugin: 'java-library'
                ${variantDeclaration('projectLibAttrValue')}
            }
            apply plugin: 'java-library'
            repositories { maven { url "${mavenHttpRepo.uri}" } }
            dependencies {
                implementation('org.external:external-lib:1.0') {
                    because(System.getProperty('selectionReason', 'original'))
                }
                implementation 'org.external:external-lib2:1.0'
                implementation project('project-lib')
                implementation files('lib/file-lib.jar')
                implementation 'composite-lib:composite-lib'
            }

            @CacheableRule
            abstract class ChangingAttributeRule implements ComponentMetadataRule {
                final String attrValue
                @Inject ChangingAttributeRule(String attrValue) { this.attrValue = attrValue }
                void execute(ComponentMetadataContext context) {
                    context.details.allVariants {
                        attributes.attribute(Attribute.of("my.attribute.name", String), attrValue)
                    }
                }
            }

            dependencies {
                components {
                    withModule('org.external:external-lib', ChangingAttributeRule) {
                        params(System.getProperty('externalLibAttrValue', 'default-value'))
                    }
                }
            }
        """
        withOriginalSourceIn("project-lib")
        withOriginalSourceIn("composite-lib")
    }

    def "can not use ResolvedArtifactResult as task input"() {
        given:
        buildFile << """
            abstract class TaskWithInput extends DefaultTask {

                @Input
                abstract SetProperty<ResolvedArtifactResult> getInput();

                @OutputFile
                abstract RegularFileProperty getOutputFile()
            }

            tasks.register('verify', TaskWithInput) {
                input.set(configurations.runtimeClasspath.incoming.artifacts.resolvedArtifacts)
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    println(input.get())
                }
            }
        """

        when:
        fails "verify"

        then:
        failureDescriptionStartsWith("Execution failed for task ':verify'.")
        failureHasCause("Cannot fingerprint input property 'input'")
    }

    def "can use #type as task input"() {
        given:
        buildFile << """
            import ${DefaultModuleIdentifier.name}
            import ${DefaultModuleVersionIdentifier.name}
            import ${DefaultModuleComponentIdentifier.name}
            import ${ImmutableCapability.name}
            import ${DefaultModuleComponentArtifactIdentifier.name}
            import ${ImmutableAttributesFactory.name}
            import ${DefaultResolvedVariantResult.name}
            import ${Describables.name}
            import ${DefaultComponentSelectionDescriptor.name}
            import ${ComponentSelectionReasons.name}
            import ${DefaultLibraryComponentSelector.name}

            abstract class TaskWithInput extends DefaultTask {

                @Input
                abstract Property<$type> getInput()

                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @TaskAction
                def action() {
                    println(input.get())
                }
            }

            tasks.register("verify", TaskWithInput) {
                outputFile.set(layout.buildDirectory.file('output.txt'))
                input.set($factory)
            }
        """

        when:
        succeeds("verify", "-Dn=foo")

        then:
        executedAndNotSkipped(":verify")

        when:
        succeeds("verify", "-Dn=foo")

        then:
        skipped(":verify")

        when:
        succeeds("verify", "-Dn=bar")

        then:
        executedAndNotSkipped(":verify")

        where:
        type                           | factory
        // For ResolvedArtifactResult
        "Attribute"                    | "Attribute.of(System.getProperty('n'), String)"
        "AttributeContainer"           | "services.get(ImmutableAttributesFactory).of(Attribute.of('some', String.class), System.getProperty('n'))"
        "Capability"                   | "new ImmutableCapability('group', System.getProperty('n'), '1.0')"
        "ModuleComponentIdentifier"    | "new DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId('group', System.getProperty('n')),'1.0')"
        "ComponentArtifactIdentifier"  | "new DefaultModuleComponentArtifactIdentifier(new DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId('group', System.getProperty('n')),'1.0'), System.getProperty('n') + '-1.0.jar', 'jar', null)"
        "ResolvedVariantResult"        | "new DefaultResolvedVariantResult(new DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId('group', System.getProperty('n')), '1.0'), Describables.of('variantName'), services.get(ImmutableAttributesFactory).of(Attribute.of('some', String.class), System.getProperty('n')), [new ImmutableCapability('group', System.getProperty('n'), '1.0')], null)"
        // For ResolvedComponentResult
        "ModuleVersionIdentifier"      | "DefaultModuleVersionIdentifier.newId('group', System.getProperty('n'), '1.0')"
//        "ResolvedComponentResult"      | "null"
//        "DependencyResult"             | "null"
        "ComponentSelector"            | "new DefaultLibraryComponentSelector(':sub', System.getProperty('n'))"
        "ComponentSelectionReason"     | "ComponentSelectionReasons.of(ComponentSelectionReasons.REQUESTED.withDescription(Describables.of('csd-' + System.getProperty('n'))))"
        "ComponentSelectionDescriptor" | "new DefaultComponentSelectionDescriptor(ComponentSelectionCause.REQUESTED, Describables.of('csd-' + System.getProperty('n')))"
    }

    def "can map ResolvedArtifactResult file as task input"() {
        given:
        buildFile << """
            abstract class TaskWithFilesInput extends DefaultTask {

                @InputFiles
                abstract ConfigurableFileCollection getInputFiles()

                @OutputFile
                abstract RegularFileProperty getOutputFile()
            }

            tasks.register('verify', TaskWithFilesInput) {
                inputFiles.from(configurations.runtimeClasspath.incoming.artifacts.resolvedArtifacts.map { it.collect { it.file } })
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    println(inputFiles.files)
                }
            }
        """

        when:
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when:
        withChangedSourceIn("project-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when:
        withChangedSourceIn("composite-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":composite-lib:jar", ":verify"

        when:
        withNewExternalDependency()
        succeeds ":verify"

        then:
        skipped ":project-lib:jar", ":composite-lib:jar"
        executedAndNotSkipped ":verify"
    }

    def "can map ResolvedArtifactResult #inputProperty as task input"() {
        given:
        buildFile << """
            abstract class TaskWithResultInput extends DefaultTask {

                @Input
                abstract ListProperty<${inputType}> getInput()

                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @TaskAction void action() {
                    println(input.get())
                }
            }

            tasks.register("verify", TaskWithResultInput) {
                def resolvedArtifacts = configurations.runtimeClasspath.incoming.artifacts.resolvedArtifacts
                input.set(resolvedArtifacts.map { it.collect { it.${inputProperty} } })
                outputFile.set(layout.buildDirectory.file('output.txt'))
            }
        """

        when:
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify", "-i"

        then:
        skipped ":project-lib:jar", ":verify"

        when: "changing project library source code"
        withChangedSourceIn("project-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar"
        skipped ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when: "changing composite library source code"
        withChangedSourceIn("composite-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":composite-lib:jar"
        skipped ":verify"

        when: "adding a new external dependency"
        withNewExternalDependency()
        succeeds ":verify"

        then:
        skipped ":project-lib:jar", ":composite-lib:jar"
        executedAndNotSkipped ":verify"

        when: "changing project library variant metadata"
        succeeds "verify", "-DprojectLibAttrValue=new-value"

        then:
        if (inputProperty == "variant") {
            skipped ":project-lib:jar", ":composite-lib:jar"
            executedAndNotSkipped ":verify"
        } else {
            skipped ":project-lib:jar", ":composite-lib:jar", ":verify"
        }

        when: "changing included library variant metadata"
        succeeds "verify", "-DcompositeLibAttrValue=new-value"

        then:
        if (inputProperty == "variant") {
            skipped ":project-lib:jar", ":composite-lib:jar"
            executedAndNotSkipped ":verify"
        } else {
            skipped ":project-lib:jar", ":composite-lib:jar", ":verify"
        }

        when: "changing external library variant metadata"
        succeeds "verify", "-DexternalLibAttrValue=new-value"

        then:
        if (inputProperty == "variant") {
            skipped ":project-lib:jar", ":composite-lib:jar"
            executedAndNotSkipped ":verify"
        } else {
            skipped ":project-lib:jar", ":composite-lib:jar", ":verify"
        }

        where:
        inputProperty | inputType
        "id"          | "ComponentArtifactIdentifier"
        "type"        | "Class<? extends Artifact>"
        "variant"     | "ResolvedVariantResult"
    }

    @NotYetImplemented
    @Issue("https://github.com/gradle/gradle/issues/13590")
    def "can combine files and metadata from ResolvedArtifactResult as nested task inputs"() {
        given:
        buildFile << """
            class ResolvedArtifactBean {

                @InputFile
                File file

                @Input
                ComponentArtifactIdentifier id

                @Input
                Class<? extends Artifact> type

                @Input
                ResolvedVariantResult variant
            }

            abstract class TaskWithFilesAndMetadataInput extends DefaultTask {

                @Nested
                abstract SetProperty<ResolvedArtifactBean>> getResArtifacts()

                @OutputFile
                abstract RegularFileProperty getOutputFile()
            }

            tasks.register('verify', TaskWithFilesAndMetadataInput) {
                def resolvedArtifacts = configurations.runtimeClasspath.incoming.artifacts.resolvedArtifacts
                resArtifacts.set(
                    resolvedArtifacts.map { arts ->
                        arts.collect { art ->
                            objects.newInstance(ResolvedArtifactBean).tap { bean ->
                                bean.file = art.file
                                bean.id = art.id
                                bean.type = art.type
                                bean.variant = art.variant
                            }
                        }
                    }
                )
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    resArtifacts.get().each { art ->
                        println("\${art.file} - \${art.id} - \${art.type} - \${art.variant}")
                    }
                }
            }
        """

        when:
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when:
        withChangedSourceIn("project-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when:
        withChangedSourceIn("composite-lib")
        succeeds "verify"

        then:
        executedAndNotSkipped ":composite-lib:jar", ":verify"

        when:
        withNewExternalDependency()
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":composite-lib:jar"
        executedAndNotSkipped ":verify"
    }

    def "can use ResolvedComponentResult result as task input"() {
        given:
        buildFile << """
            abstract class TaskWithGraphInput extends DefaultTask {

                @Input
                abstract Property<ResolvedComponentResult> getDepGraphRoot()

                @OutputFile
                abstract RegularFileProperty getOutputFile()
            }

            tasks.register('verify', TaskWithGraphInput) {
                depGraphRoot.set(configurations.runtimeClasspath.incoming.resolutionResult.rootComponent)
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    println(depGraphRoot.get())
                }
            }
        """

        when:
        succeeds "verify"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when:
        succeeds "verify"

        then:
        skipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when:
        withChangedSourceIn("project-lib")
        succeeds "verify"

        then:
        skipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when:
        withChangedSourceIn("composite-lib")
        succeeds "verify"

        then:
        skipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when:
        withNewExternalDependency()
        succeeds "verify"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when:
        succeeds "verify"

        then:
        skipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when: "changing selection reason"
        succeeds "verify", "-DselectionReason=changed"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when: "changing project library variant metadata"
        succeeds "verify", "-DprojectLibAttrValue=new-value"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when: "changing included library variant metadata"
        succeeds "verify", "-DcompositeLibAttrValue=new-value"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"

        when: "changing external library variant metadata"
        succeeds "verify", "-DexternalLibAttrValue=new-value"

        then:
        executedAndNotSkipped ":verify"
        notExecuted ":project-lib:jar", ":composite-lib:jar"
    }

    private void withOriginalSourceIn(String basePath) {
        sourceFileIn(basePath).tap {
            text = """
                class Main {}
            """.stripIndent()
            makeOlder()
        }
    }

    private void withChangedSourceIn(String basePath) {
        sourceFileIn(basePath).text = """
            class Main {
                public static void main(String[] args) {}
            }
        """.stripIndent()
    }

    private TestFile sourceFileIn(String basePath) {
        return file("$basePath/src/main/java/Main.java")
    }

    private void withNewExternalDependency() {
        buildFile << """
            dependencies { implementation 'org.external:external-tool:1.0' }
        """
    }
}
