/*
 * Copyright 2009 the original author or authors.
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
class TestExecutionPerformanceTest extends AbstractPerformanceTest {
    @Unroll("Project '#testProject'")
    def "test execution"() {
        given:
        runner.testProject = testProject
        runner.tasksToRun = ['cleanTest', 'test']
        runner.args = ['-q']
        runner.maxExecutionTimeRegression = maxExecutionTimeRegression
        runner.maxMemoryRegression = kbytes(3000)

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject         | maxExecutionTimeRegression
        "withTestNG"        | millis(1000)
        "withJUnit"         | millis(500)
        "withVerboseTestNG" | millis(500)
        "withVerboseJUnit"  | millis(500)
    }
}