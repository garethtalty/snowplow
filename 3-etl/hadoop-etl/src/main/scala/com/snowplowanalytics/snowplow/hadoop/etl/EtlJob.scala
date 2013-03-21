/*
 * Copyright (c) 2012-2013 SnowPlow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.hadoop.etl

// Scalaz
import scalaz._
import Scalaz._

// Scalding
import com.twitter.scalding._

// This project
import inputs.CollectorLoader
import enrichments.EnrichmentManager
import outputs.CanonicalOutput

/**
 * Holds constructs to help build the ETL job's data
 * flow (see below).
 */ 
object EtlJob {

  /**
   * A helper method to take a ValidatedCanonicalOutput
   * and flatMap it into a ValidatedCanonicalOutput.
   *
   * We have to do some unboxing because enrichEvent
   * expects a raw CanonicalInput as its argument, not
   * a MaybeCanonicalInput.
   *
   * @param input The ValidatedCanonicalInput
   * @return the ValidatedCanonicalOutput. Thanks to
   *         flatMap, will include any validation errors
   *         contained within the ValidatedCanonicalInput
   */
  def toCanonicalOutput(input: ValidatedMaybeCanonicalInput): ValidatedMaybeCanonicalOutput = {
    input.flatMap {
      _.cata(EnrichmentManager.enrichEvent(_).map(_.some),
             none.success)
    }
  }
}

/**
 * The SnowPlow ETL job, written in Scalding
 * (the Scala DSL on top of Cascading).
 */ 
class EtlJob(args: Args) extends Job(args) {

  // Job configuration. Scalaz recommends using fold()
  // for unpicking a Validation
  val etlConfig = EtlJobConfig.loadConfigFrom(args).fold(
    e => throw FatalEtlError(e),
    c => c)

  // Aliases for our job
  val input = MultipleTextLineFiles(etlConfig.inFolder)
  val goodOutput = Tsv(etlConfig.outFolder)
  val badOutput = JsonLine(etlConfig.badFolder)

  // Wait until we're on the nodes to instantiate
  // TODO: can we tidy this up
  lazy val loader = CollectorLoader.getLoader(etlConfig.inFormat) match {
    case Success(s) => s
    case Failure(f) => throw FatalEtlError(f)
  }

  // Scalding data pipeline
  val common = input
    .read
    .map('line -> 'output) { l: String =>
      EtlJob.toCanonicalOutput(loader.toCanonicalInput(l))
    }

  // Handle bad rows
  val bad = common
    .flatMap('output -> 'errors) { o: ValidatedMaybeCanonicalOutput => o.fold(
      e => Some(e.toList), // Nel -> Some(List)
      c => None)
    }
    .project('line, 'errors)
    .write(badOutput) // JSON containing line and error(s)

  // Handle good rows
  val good = common
    .flatMapTo('output -> 'good) { o: ValidatedMaybeCanonicalOutput =>
      o match {
        case Success(Some(s)) => Some(s)
        case _ => None // Drop errors *and* blank rows
      }
    }
    .unpackTo[CanonicalOutput]('good -> '*)
    .project('page_url) // We don't have space to store the raw page URL in Redshift _currently_
    .write(goodOutput)
}