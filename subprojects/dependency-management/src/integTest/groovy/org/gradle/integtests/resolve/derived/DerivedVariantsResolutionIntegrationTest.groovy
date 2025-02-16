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

package org.gradle.integtests.resolve.derived


import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.test.fixtures.server.http.MavenHttpModule
import spock.lang.IgnoreIf

import static org.gradle.api.internal.artifacts.dsl.DefaultComponentMetadataProcessor.FORCE_REALIZE

@IgnoreIf({ GradleContextualExecuter.configCache }) // ResolvedArtifactResult as task input
class DerivedVariantsResolutionIntegrationTest extends AbstractHttpDependencyResolutionTest {
    MavenHttpModule direct
    MavenHttpModule transitive

    def setup() {
        buildFile << """
            plugins {
                id 'java'
            }

            repositories {
                maven { url '$mavenHttpRepo.uri' }
            }

            dependencies {
                implementation 'test:direct:1.0'
            }

            abstract class Resolve extends DefaultTask {
                @InputFiles
                abstract ConfigurableFileCollection getArtifacts()

                @InputFiles
                abstract ConfigurableFileCollection getArtifactCollection()

                @Internal
                abstract SetProperty<ResolvedArtifactResult> getResolvedArtifacts()

                @Internal
                List<String> expectedFiles = []

                @Internal
                List<String> expectedVariants = []

                @TaskAction
                void assertThat() {
                    assert artifacts.files*.name == expectedFiles
                    assert artifactCollection.files*.name == expectedFiles
                    assert resolvedArtifacts.get()*.variant.displayName == expectedVariants
                }
            }

            task resolveSources(type: Resolve) {
                def artifactView = configurations.runtimeClasspath.incoming.artifactView {
                    lenient = true
                    withVariantReselection()
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.DOCUMENTATION))
                        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.EXTERNAL))
                        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType, DocsType.SOURCES))
                        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
                    }
                }
                artifacts.from(artifactView.files)
                artifactCollection.from(artifactView.artifacts.artifactFiles)
                resolvedArtifacts.set(artifactView.artifacts.resolvedArtifacts)
            }

            task resolveJavadoc(type: Resolve) {
                def artifactView = configurations.runtimeClasspath.incoming.artifactView {
                    lenient = true
                    withVariantReselection()
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.DOCUMENTATION))
                        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.EXTERNAL))
                        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType, DocsType.JAVADOC))
                        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
                    }
                }
                artifacts.from(artifactView.files)
                artifactCollection.from(artifactView.artifacts.artifactFiles)
                resolvedArtifacts.set(artifactView.artifacts.resolvedArtifacts)
            }
        """
        transitive = mavenHttpRepo.module("test", "transitive", "1.0")
        direct = mavenHttpRepo.module("test", "direct", "1.0")
        direct.dependsOn(transitive)
    }

    // region With Gradle Module Metadata
    def "direct has GMM and no sources or javadoc jars"() {
        transitive.withModuleMetadata()
        transitive.publish()
        direct.withModuleMetadata()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = []
                expectedVariants = []
            }
            resolveJavadoc {
                expectedFiles = []
                expectedVariants = []
            }
        """
        expect:
        direct.pom.expectGet()
        direct.moduleMetadata.expectGet()
        transitive.pom.expectGet()
        transitive.moduleMetadata.expectGet()

        succeeds( 'resolveSources', 'resolveJavadoc')
    }

    def "direct has GMM and has sources jar"() {
        transitive.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0.jar")
        }
        .variant("sources", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "sources",
                "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0-sources.jar")
        }
        transitive.withModuleMetadata()
        transitive.publish()

        direct.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("direct-1.0.jar")
        }
        .variant("sources", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "sources",
                "org.gradle.usage": "java-runtime"
        ]) {
            artifact("direct-1.0-sources.jar")
        }
        direct.withModuleMetadata()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = ['direct-1.0-sources.jar', 'transitive-1.0-sources.jar']
                expectedVariants = ['test:direct:1.0 variant sources', 'test:transitive:1.0 variant sources']
            }
        """
        expect:
        direct.pom.expectGet()
        direct.moduleMetadata.expectGet()
        transitive.pom.expectGet()
        transitive.moduleMetadata.expectGet()
        direct.artifact(classifier: "sources").expectGet()
        transitive.artifact(classifier: "sources").expectGet()

        succeeds( "resolveSources")
    }

    def "direct has GMM and has javadoc jar"() {
        transitive.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0.jar")
        }
            .variant("javadoc", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "javadoc",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("transitive-1.0-javadoc.jar")
            }
        transitive.withModuleMetadata()
        transitive.publish()

        direct.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("direct-1.0.jar")
        }
            .variant("javadoc", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "javadoc",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("direct-1.0-javadoc.jar")
            }
        direct.withModuleMetadata()
        direct.publish()

        buildFile << """
            resolveJavadoc {
                expectedFiles = ['direct-1.0-javadoc.jar', 'transitive-1.0-javadoc.jar']
                expectedVariants = ['test:direct:1.0 variant javadoc', 'test:transitive:1.0 variant javadoc']
            }
        """
        expect:
        direct.pom.expectGet()
        direct.moduleMetadata.expectGet()
        transitive.pom.expectGet()
        transitive.moduleMetadata.expectGet()
        direct.artifact(classifier: "javadoc").expectGet()
        transitive.artifact(classifier: "javadoc").expectGet()

        succeeds( "resolveJavadoc")
    }

    def "direct has GMM and has both sources and javadoc jars"() {
        transitive.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0.jar")
        }
            .variant("sources", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "sources",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("transitive-1.0-sources.jar")
            }
            .variant("javadoc", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "javadoc",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("transitive-1.0-javadoc.jar")
            }
        transitive.withModuleMetadata()
        transitive.publish()

        direct.adhocVariants().variant("jar", [
            "org.gradle.category": "library",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("direct-1.0.jar")
        }
            .variant("sources", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "sources",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("direct-1.0-sources.jar")
            }
            .variant("javadoc", [
                "org.gradle.category": "documentation",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype": "javadoc",
                "org.gradle.usage": "java-runtime"
            ]) {
                artifact("direct-1.0-javadoc.jar")
            }
        direct.withModuleMetadata()
        direct.publish()

        buildFile << """
            resolveJavadoc {
                expectedFiles = ['direct-1.0-javadoc.jar', 'transitive-1.0-javadoc.jar']
                expectedVariants = ['test:direct:1.0 variant javadoc', 'test:transitive:1.0 variant javadoc']
            }
        """
        expect:
        direct.pom.expectGet()
        direct.moduleMetadata.expectGet()
        transitive.pom.expectGet()
        transitive.moduleMetadata.expectGet()
        direct.artifact(classifier: 'javadoc').expectGet()
        transitive.artifact(classifier: 'javadoc').expectGet()

        succeeds( 'resolveJavadoc')

        and:
        buildFile << """
            resolveSources {
                expectedFiles = ['direct-1.0-sources.jar', 'transitive-1.0-sources.jar']
                expectedVariants = ['test:direct:1.0 variant sources', 'test:transitive:1.0 variant sources']
            }
        """

        // POMs and GMM are already cached; querying for sources should do minimal additional work to fetch sources jars
        direct.artifact(classifier: 'sources').expectGet()
        transitive.artifact(classifier: 'sources').expectGet()

        succeeds( 'resolveSources')
    }

    def "direct has GMM and no sources jar and transitive has GMM and has sources jar"() {
        transitive.adhocVariants().variant("jar", [
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0.jar")
        }.variant("sources", [
            "org.gradle.category": "documentation",
            "org.gradle.dependency.bundling": "external",
            "org.gradle.docstype": "sources",
            "org.gradle.usage": "java-runtime"
        ]) {
            artifact("transitive-1.0-sources.jar")
        }
        transitive.withModuleMetadata()
        transitive.publish()

        direct.withModuleMetadata()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = ['transitive-1.0-sources.jar']
                expectedVariants = ['test:transitive:1.0 variant sources']
            }
        """
        expect:
        direct.pom.expectGet()
        direct.moduleMetadata.expectGet()
        transitive.pom.expectGet()
        transitive.moduleMetadata.expectGet()
        transitive.artifact(classifier: "sources").expectGet()

        succeeds( "resolveSources")
    }
    // endregion

    // region Without Gradle Module Metadata
    @IgnoreIf({ FORCE_REALIZE })
    def "direct has no GMM and no sources or javadoc jars"() {
        transitive.publish()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = []
                expectedVariants = []
            }
            resolveJavadoc {
                expectedFiles = []
                expectedVariants = []
            }
        """
        expect:
        direct.pom.expectGet()
        transitive.pom.expectGet()
        direct.artifact(classifier: "sources").expectGetMissing()
        transitive.artifact(classifier: "sources").expectGetMissing()
        direct.artifact(classifier: "javadoc").expectGetMissing()
        transitive.artifact(classifier: "javadoc").expectGetMissing()

        succeeds( 'resolveSources', 'resolveJavadoc')
    }

    @IgnoreIf({ FORCE_REALIZE })
    def "direct has no GMM and has sources jar"() {
        direct.withSourceAndJavadoc()
        transitive.withSourceAndJavadoc()

        transitive.publish()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = ['direct-1.0-sources.jar', 'transitive-1.0-sources.jar']
                expectedVariants = ['test:direct:1.0 configuration sources', 'test:transitive:1.0 configuration sources']
            }
        """
        expect:
        direct.pom.expectGet()
        transitive.pom.expectGet()
        direct.artifact(classifier: "sources").expectGet()
        transitive.artifact(classifier: "sources").expectGet()

        succeeds("resolveSources")
    }

    @IgnoreIf({ FORCE_REALIZE })
    def "direct has no GMM and has javadoc jar"() {
        direct.withSourceAndJavadoc()
        transitive.withSourceAndJavadoc()

        transitive.publish()
        direct.publish()

        buildFile << """
            resolveJavadoc {
                expectedFiles = ['direct-1.0-javadoc.jar', 'transitive-1.0-javadoc.jar']
                expectedVariants = ['test:direct:1.0 configuration javadoc', 'test:transitive:1.0 configuration javadoc']
            }
        """
        expect:
        direct.pom.expectGet()
        transitive.pom.expectGet()
        direct.artifact(classifier: "javadoc").expectGet()
        transitive.artifact(classifier: "javadoc").expectGet()

        succeeds("resolveJavadoc")
    }

    @IgnoreIf({ FORCE_REALIZE })
    def "direct has no GMM and has both sources and javadoc jars"() {
        direct.withSourceAndJavadoc()
        transitive.withSourceAndJavadoc()

        transitive.publish()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = ['direct-1.0-sources.jar', 'transitive-1.0-sources.jar']
                expectedVariants = ['test:direct:1.0 configuration sources', 'test:transitive:1.0 configuration sources']
            }
        """
        expect:
        direct.pom.expectGet()
        transitive.pom.expectGet()
        direct.artifact(classifier: "sources").expectGet()
        transitive.artifact(classifier: "sources").expectGet()

        succeeds("resolveSources")

        and:
        buildFile << """
            resolveJavadoc {
                expectedFiles = ['direct-1.0-javadoc.jar', 'transitive-1.0-javadoc.jar']
                expectedVariants = ['test:direct:1.0 configuration javadoc', 'test:transitive:1.0 configuration javadoc']
            }
        """

        // POMs and GMM are already cached; querying for javadoc should do minimal additional work to fetch javadoc jars
        direct.artifact(classifier: 'javadoc').expectGet()
        transitive.artifact(classifier: 'javadoc').expectGet()

        succeeds( 'resolveJavadoc')
    }

    @IgnoreIf({ FORCE_REALIZE })
    def "direct has no GMM and no sources jar and transitive has no GMM and has sources jar"() {
        transitive.withSourceAndJavadoc()
        transitive.publish()
        direct.publish()

        buildFile << """
            resolveSources {
                expectedFiles = ['transitive-1.0-sources.jar']
                expectedVariants = ['test:transitive:1.0 configuration sources']
            }
        """
        expect:
        direct.pom.expectGet()
        transitive.pom.expectGet()
        direct.artifact(classifier: "sources").expectGetMissing()
        transitive.artifact(classifier: "sources").expectGet()

        succeeds( "resolveSources")
    }
    // endregion
}
