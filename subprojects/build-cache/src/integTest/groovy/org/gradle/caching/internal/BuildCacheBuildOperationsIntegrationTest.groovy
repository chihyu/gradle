/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.caching.internal

import org.gradle.caching.BuildCacheException
import org.gradle.caching.internal.operations.BuildCacheArchivePackBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheArchiveUnpackBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteLoadBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteStoreBuildOperationType
import org.gradle.caching.local.internal.DefaultBuildCacheTempFileStore
import org.gradle.caching.local.internal.LocalBuildCacheService
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.DirectoryBuildCacheFixture
import org.gradle.internal.io.NullOutputStream
import org.gradle.util.TextUtil
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
class BuildCacheBuildOperationsIntegrationTest extends AbstractIntegrationSpec implements DirectoryBuildCacheFixture {

    @Shared
    String localCacheClass = "LocalBuildCache"
    @Shared
    String remoteCacheClass = "RemoteBuildCache"

    def operations = new BuildOperationsFixture(executer, testDirectoryProvider)

    void local(String loadBody, String storeBody) {
        register(localCacheClass, loadBody, storeBody, true)
    }

    void remote(String loadBody, String storeBody) {
        register(remoteCacheClass, loadBody, storeBody)
    }

    def setup() {
        executer.beforeExecute { it.withBuildCacheEnabled() }
    }

    void register(String className, String loadBody, String storeBody, boolean isLocal = false) {
        settingsFile << """
            class ${className} extends AbstractBuildCache {}
            class ${className}ServiceFactory implements BuildCacheServiceFactory<${className}> {
                ${className}Service createBuildCacheService(${className} configuration, Describer describer) {
                    return new ${className}Service(configuration)
                }
            }
            class ${className}Service implements BuildCacheService ${isLocal ? ", ${LocalBuildCacheService.name}" : ""} {
                ${className}Service(${className} configuration) {
                }
    
                @Override
                boolean load(BuildCacheKey key, BuildCacheEntryReader reader) throws BuildCacheException {
                    ${isLocal ? "" : loadBody ?: ""}
                }
    
                @Override
                void store(BuildCacheKey key, BuildCacheEntryWriter writer) throws BuildCacheException {
                    ${isLocal ? "" : storeBody ?: ""}
                }

                // @Override
                void load(BuildCacheKey key, Action<? super File> reader) {
                    ${isLocal ? loadBody ?: "" : ""}
                }
    
                // @Override
                void store(BuildCacheKey key, File file) {
                    ${isLocal ? storeBody ?: "" : ""}
                }
    
                void allocateTempFile(BuildCacheKey key, Action<? super File> action) {
                    new $DefaultBuildCacheTempFileStore.name(new File("${TextUtil.normaliseFileSeparators(file("tmp").absolutePath)}")).allocateTempFile(key, action)
                } 

                @Override
                void close() throws IOException {
                }
            }

            buildCache {
                registerBuildCacheService(${className}, ${className}ServiceFactory)
            }
        """
    }

    String cacheableTask() {
        """
            @CacheableTask
            class CustomTask extends DefaultTask {
            
                @Input
                String val = "foo"
                
                @Input
                List<String> paths = []
                 
                @OutputDirectory
                File dir = project.file("build/dir")

                @TaskAction
                void generate() {
                    paths.each {
                        def f = new File(dir, it)
                        f.parentFile.mkdirs()
                        f.text = val
                    }
                }
            }
        """
    }

    def "emits only pack/unpack operations for local"() {
        when:
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        succeeds("t")

        then:
        operations.none(BuildCacheRemoteLoadBuildOperationType)
        operations.none(BuildCacheRemoteStoreBuildOperationType)
        def packOp = operations.only(BuildCacheArchivePackBuildOperationType)

        packOp.details.cacheKey != null
        packOp.result.archiveSize == localCacheArtifact(packOp.details.cacheKey.toString()).length()
        packOp.result.archiveEntryCount == 4

        when:
        succeeds("clean", "t")

        then:
        operations.none(BuildCacheRemoteStoreBuildOperationType)
        def unpackOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)
        unpackOp.details.cacheKey == packOp.details.cacheKey

        // Not all of the tar.gz bytes need to be read in order to unpack the archive.
        // On Linux at least, the archive may have redundant padding bytes
        // Furthermore, the exact amount of padding appears to be non deterministic.
        def cacheArtifact = localCacheArtifact(unpackOp.details.cacheKey.toString())
        def sizeDiff = cacheArtifact.length() - unpackOp.details.archiveSize.toLong()
        sizeDiff > -100 && sizeDiff < 100

        unpackOp.result.archiveEntryCount == 4
    }

    def "records load failure"() {
        when:
        remote("throw new ${exceptionType.name}('!')", "writer.writeTo(new ${NullOutputStream.name}())")
        settingsFile << """
            buildCache { remote($remoteCacheClass) }
        """
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        executer.withStackTraceChecksDisabled()
        succeeds("t")

        then:
        def failedLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        failedLoadOp.details.cacheKey != null
        failedLoadOp.result == null
        failedLoadOp.failure == "${exceptionType.name}: !"

        where:
        exceptionType << [RuntimeException, IOException]
    }

    def "records store failure"() {
        when:
        remote("", "throw new ${exceptionType.name}('!')")
        settingsFile << """
            buildCache { 
                remote($remoteCacheClass).push = true 
            }
        """
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        executer.withStackTraceChecksDisabled()
        succeeds("t")

        then:
        def failedLoadOp = operations.only(BuildCacheRemoteStoreBuildOperationType)
        failedLoadOp.details.cacheKey != null
        failedLoadOp.result == null
        failedLoadOp.failure == "${exceptionType.name}: !"

        where:
        exceptionType << [RuntimeException, BuildCacheException, IOException]
    }

    def "records unpack failure"() {
        when:
        local("reader.execute(new File('not.there'))", "writer.writeTo(new ${NullOutputStream.name}())")
        settingsFile << """
            buildCache { local($localCacheClass) }
        """
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        executer.withStackTraceChecksDisabled()
        succeeds("t")

        then:
        def failedLoadOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)
        failedLoadOp.details.cacheKey != null
        failedLoadOp.result == null
        failedLoadOp.failure =~ /org.gradle.api.UncheckedIOException:.* not.there/
    }

    def "records ops for miss then store"() {
        given:
        remote("", "writer.writeTo(new ${NullOutputStream.name}())")

        settingsFile << """
            buildCache {
                $config   
            }
        """

        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        when:
        succeeds("t")

        then:
        def remoteMissLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        def packOp = operations.only(BuildCacheArchivePackBuildOperationType)
        def remoteStoreOp = operations.only(BuildCacheRemoteStoreBuildOperationType)

        packOp.details.cacheKey == remoteStoreOp.details.cacheKey
        def localCacheArtifact = localCacheArtifact(packOp.details.cacheKey.toString())
        if (localStore) {
            assert packOp.result.archiveSize == localCacheArtifact.length()
        } else {
            assert !localCacheArtifact.exists()
        }

        packOp.result.archiveEntryCount == 4
        remoteStoreOp.details.archiveSize == packOp.result.archiveSize

        operations.orderedSerialSiblings(remoteMissLoadOp, packOp, remoteStoreOp)

        where:
        config << [
            "remote($remoteCacheClass) { push = true }",
            "local.push = false; remote($remoteCacheClass) { push = true }",
            "local.enabled = false; remote($remoteCacheClass) { push = true }",
            "local($remoteCacheClass) { push = true }; remote($remoteCacheClass) { push = true }; "
        ]
        localStore << [
            true, false, false, false
        ]
    }

    def "records ops for remote hit"() {
        given:
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """
        succeeds("t")
        remote("", "writer.writeTo(new ${NullOutputStream.name}())")
        def initialPackOp = operations.only(BuildCacheArchivePackBuildOperationType)
        def artifactFileCopy = file("artifact")
        // move it out of the local for us to use
        assert localCacheArtifact(initialPackOp.details.cacheKey.toString()).renameTo(artifactFileCopy)
        assert !localCacheArtifact(initialPackOp.details.cacheKey.toString()).exists()

        when:
        settingsFile.text = ""
        remote("reader.readFrom(new File('${TextUtil.normaliseFileSeparators(artifactFileCopy.absolutePath)}').newInputStream())", "writer.writeTo(new ${NullOutputStream.name}())")
        settingsFile << """
            buildCache {
                ${localCacheConfiguration()}
                $config   
            }
        """

        succeeds("clean", "t")

        then:
        def remoteHitLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        def unpackOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)

        unpackOp.details.cacheKey == remoteHitLoadOp.details.cacheKey
        def localCacheArtifact = localCacheArtifact(remoteHitLoadOp.details.cacheKey.toString())
        if (localStore) {
            assert remoteHitLoadOp.result.archiveSize == localCacheArtifact.length()
        } else {
            assert !localCacheArtifact.exists()
        }

        unpackOp.result.archiveEntryCount == 4
        unpackOp.details.archiveSize == remoteHitLoadOp.result.archiveSize

        operations.orderedSerialSiblings(remoteHitLoadOp, unpackOp)

        where:
        config << [
            "remote($remoteCacheClass)",
            "local.push = false; remote($remoteCacheClass)",
            "local.enabled = false; remote($remoteCacheClass)",
        ]
        localStore << [
            true, false, false
        ]
    }

    def "does not emit operations for custom local cache implementations"() {
        given:
        remote("", "writer.writeTo(new ${NullOutputStream.name}())")

        settingsFile << """
            buildCache {
                local($remoteCacheClass)   
                remote($remoteCacheClass)   
            }
        """

        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        when:
        succeeds("t")

        then:
        def remoteMissLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        def packOp = operations.only(BuildCacheArchivePackBuildOperationType)

        packOp.details.cacheKey == remoteMissLoadOp.details.cacheKey
        def localCacheArtifact = localCacheArtifact(packOp.details.cacheKey.toString())
        !localCacheArtifact.exists()

        packOp.result.archiveEntryCount == 4

        operations.orderedSerialSiblings(remoteMissLoadOp, packOp)
    }

}
