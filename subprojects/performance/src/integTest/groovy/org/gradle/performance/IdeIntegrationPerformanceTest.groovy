/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.performance

import org.gradle.performance.fixture.AbstractPerformanceTest
import spock.lang.Unroll

import static org.gradle.performance.measure.DataAmount.kbytes
import static org.gradle.performance.measure.Duration.millis

/**
 * by Szczepan Faber, created at: 2/9/12
 */
class IdeIntegrationPerformanceTest extends AbstractPerformanceTest {
    @Unroll("Project '#testProject' eclipse")
    def "eclipse"() {
        given:
        runner.testProject = testProject
        runner.tasksToRun = ['eclipse']
        runner.maxExecutionTimeRegression = maxExecutionTimeRegression
        runner.maxMemoryRegression = kbytes(3000)

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject       | maxExecutionTimeRegression
        "small"           | millis(500)
        "multi"           | millis(1500)
        "lotDependencies" | millis(3000)
    }

    @Unroll("Project '#testProject' idea")
    def "idea"() {
        given:
        runner.testProject = testProject
        runner.tasksToRun = ['idea']
        runner.maxExecutionTimeRegression = maxExecutionTimeRegression
        runner.maxMemoryRegression = kbytes(3000)

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject       | maxExecutionTimeRegression
        "small"           | millis(500)
        "multi"           | millis(1500)
        "lotDependencies" | millis(3000)
    }
}