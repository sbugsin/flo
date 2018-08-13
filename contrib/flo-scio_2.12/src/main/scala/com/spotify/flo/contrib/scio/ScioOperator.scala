/*-
 * -\-\-
 * flo-scio
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
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
 * -/-/-
 */

package com.spotify.flo.contrib.scio

import com.spotify.flo.contrib.scio.ScioOperator.log
import com.spotify.flo.{EvalContext, FloTesting, TaskId, TaskOperator, TestContext}
import com.spotify.scio.ScioContext
import com.spotify.scio.testing.JobTest
import com.spotify.scio.testing.JobTest.BeamOptions
import org.apache.beam.runners.dataflow.DataflowPipelineJob
import org.apache.beam.sdk.options.{ApplicationNameOptions, PipelineOptions, PipelineOptionsFactory}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.util.{Failure, Try}

class ScioOperator[T] extends TaskOperator[ScioJobSpec.Provider[T], ScioJobSpec[_, T], T] {

  def provide(evalContext: EvalContext): ScioJobSpec.Provider[T] = {
    new ScioJobSpec.Provider(evalContext.currentTask().get().id())
  }

  override def perform(spec: ScioJobSpec[_, T], listener: TaskOperator.Listener): T = {
    spec.validate()
    if (FloTesting.isTest) {
      runTest(spec)
    } else {
      runProd(spec, listener)
    }
  }

  private def runTest[R](spec: ScioJobSpec[R, T]): T = {
    for (result <- ScioOperator.mock().results.get(spec.taskId)) {
      return spec.success(result.asInstanceOf[R])
    }

    for (jobTestSupplier <- ScioOperator.mock().jobTests.get(spec.taskId)) {

      // Set up pipeline
      val jobTest = jobTestSupplier()
      jobTest.setUp()
      val sc = scioContextForTest(jobTest.testId)
      sc.options.as(classOf[ApplicationNameOptions]).setAppName(jobTest.testId)
      spec.pipeline(sc)

      // Start job
      val scioResult = Try(sc.close())
      scioResult match {
        case Failure(t) => return spec.failure(t)
        case _ =>
      }

      // Wait for job to complete
      val done = Try(scioResult.get.waitUntilDone())
      done match {
        case Failure(t) => return spec.failure(t)
        case _ =>
      }

      // Handle result
      val result = Try(spec.result(sc, scioResult.get))
      result match {
        case Failure(t) => return spec.failure(t)
        case _ =>
      }

      // Success!
      jobTest.tearDown()
      return spec.success(result.get)
    }

    throw new AssertionError("Missing either mocked scio job result or JobTest, please set them up using either " +
      "ScioOperator.mock().result(...) or ScioOperator.mock().result().jobTest(...) before running the workflow")
  }

  private def scioContextForTest[U](testId: String) = {
    // ScioContext.forTest does not seem to allow specifying testId
    val opts = PipelineOptionsFactory
      .fromArgs("--appName=" + testId)
      .as(classOf[PipelineOptions])
    val sc = ScioContext(opts)
    if (!sc.isTest) {
      throw new AssertionError(s"Failed to create ScioContext for test with id ${testId}")
    }
    sc
  }

  private def runProd[R](spec: ScioJobSpec[R, T], listener: TaskOperator.Listener): T = {

    // Set up pipeline
    val sc = spec.options match {
      case None => ScioContext()
      case Some(options) => ScioContext(options())
    }
    spec.pipeline(sc)

    // Start job
    val scioResult = Try(sc.close())
    scioResult match {
      case Failure(t) => return spec.failure(t)
      case _ =>
    }

    // Report job id
    scioResult.get.internal match {
      case job: DataflowPipelineJob => reportDataflowJobId(spec.taskId, job.getJobId, listener)
      case _ =>
    }

    // Wait for job to complete
    val done = Try(scioResult.get.waitUntilDone())
    done match {
      case Failure(t) => return spec.failure(t)
      case _ =>
    }

    // Handle result
    val result = Try(spec.result(sc, scioResult.get))
    result match {
      case Failure(t) => return spec.failure(t)
      case _ =>
    }

    // Success!
    spec.success(result.get)
  }

  private def reportDataflowJobId(taskId: TaskId, jobId: String, listener: TaskOperator.Listener) {
    log.info("Started scio job (dataflow): {}", jobId)
    listener.meta(taskId, "dataflow-job-id", jobId);
  }
}

object ScioOperator {

  private val log: Logger = LoggerFactory.getLogger(classOf[ScioOperator[_]])

  private val MOCK = TestContext.key("mock", () => new Mocking())

  def mock(): Mocking = {
    MOCK.get()
  }

  class Mocking {
    private[scio] val results = mutable.Map[TaskId, Any]()
    private[scio] val jobTests = mutable.Map[TaskId, () => JobTest.Builder]()

    def result(id: TaskId, result: Any): Mocking = {
      results(id) = result
      this
    }

    def jobTest(id: TaskId)(setup: JobTest.Builder => JobTest.Builder)(implicit bm: BeamOptions): Mocking = {
      jobTests(id) = () => {
        // JobTest name may not contain dash
        val name = id.toString.replace("-", "_")
        val builder = JobTest(name)
        setup(builder)
        builder
      }
      this
    }
  }

  def apply[T](): ScioOperator[T] = new ScioOperator[T]()
}