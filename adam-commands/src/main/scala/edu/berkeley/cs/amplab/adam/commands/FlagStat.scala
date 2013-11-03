/*
 * Copyright (c) 2013. Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.berkeley.cs.amplab.adam.commands

import edu.berkeley.cs.amplab.adam.util.{Args4j, Args4jBase}
import edu.berkeley.cs.amplab.adam.rdd.AdamContext._
import org.kohsuke.args4j.Argument
import spark.{RDD, SparkContext}
import org.apache.hadoop.mapreduce.Job
import edu.berkeley.cs.amplab.adam.avro.ADAMRecord
import edu.berkeley.cs.amplab.adam.projections.Projection
import edu.berkeley.cs.amplab.adam.predicates.ADAMRecordField
import java.util.logging.{Level, Logger}

object FlagStat extends AdamCommandCompanion {
  val commandName: String = "flagstat"
  val commandDescription: String = "Prints statistics for ADAM data similar to samtools flagstat"

  def apply(cmdLine: Array[String]): AdamCommand = {
    new FlagStat(Args4j[FlagStatArgs](cmdLine))
  }
}

class FlagStatArgs extends Args4jBase with SparkArgs with ParquetArgs {
  @Argument(required = true, metaVar = "INPUT", usage = "The ADAM data to return stats for", index = 0)
  val inputPath: String = null
}

class FlagStat(protected val args: FlagStatArgs) extends AdamSparkCommand[FlagStatArgs] {
  val companion: AdamCommandCompanion = FlagStat

  val parquetHadoopLogger = Logger.getLogger("parquet.hadoop")
  parquetHadoopLogger.setLevel(Level.SEVERE)

  def run(sc: SparkContext, job: Job): Unit = {

    val projection = Projection(
      ADAMRecordField.readMapped, ADAMRecordField.mateMapped, ADAMRecordField.readPaired,
      ADAMRecordField.referenceId, ADAMRecordField.mateReferenceId,
      ADAMRecordField.duplicateRead, ADAMRecordField.readMapped, ADAMRecordField.mateMapped,
      ADAMRecordField.firstOfPair, ADAMRecordField.secondOfPair,
      ADAMRecordField.properPair, ADAMRecordField.mapq,
      ADAMRecordField.failedVendorQualityChecks)

    val adamFile: RDD[ADAMRecord] = sc.adamLoad(args.inputPath, projection = Some(projection))

    val (failedVendorQuality, passedVendorQuality) = adamFile.adamFlagStat()

    def percent(fraction: Long, total: Long) = if (total == 0) 0.0 else 100.00 * fraction.toFloat / total

    println( """
               |%d + %d in total (QC-passed reads + QC-failed reads)
               |%d + %d duplicates
               |%d + %d mapped (%.2f%%:%.2f%%)
               |%d + %d paired in sequencing
               |%d + %d read1
               |%d + %d read2
               |%d + %d properly paired (%.2f%%:%.2f%%)
               |%d + %d with itself and mate mapped
               |%d + %d singletons (%.2f%%:%.2f%%)
               |%d + %d with mate mapped to a different chr
               |%d + %d with mate mapped to a different chr (mapQ>=5)
             """.stripMargin('|').format(
      passedVendorQuality.total, failedVendorQuality.total,
      passedVendorQuality.duplicates, failedVendorQuality.duplicates,
      passedVendorQuality.mapped, failedVendorQuality.mapped,
      percent(passedVendorQuality.mapped, passedVendorQuality.total),
      percent(failedVendorQuality.mapped, failedVendorQuality.total),
      passedVendorQuality.pairedInSequencing, failedVendorQuality.pairedInSequencing,
      passedVendorQuality.read1, failedVendorQuality.read1,
      passedVendorQuality.read2, failedVendorQuality.read2,
      passedVendorQuality.properlyPaired, failedVendorQuality.properlyPaired,
      percent(passedVendorQuality.properlyPaired, passedVendorQuality.total),
      percent(failedVendorQuality.properlyPaired, failedVendorQuality.total),
      passedVendorQuality.withSelfAndMateMapped, failedVendorQuality.withSelfAndMateMapped,
      passedVendorQuality.singleton, failedVendorQuality.singleton,
      percent(passedVendorQuality.singleton, passedVendorQuality.total),
      percent(failedVendorQuality.singleton, failedVendorQuality.total),
      passedVendorQuality.withMateMappedToDiffChromosome, failedVendorQuality.withMateMappedToDiffChromosome,
      passedVendorQuality.withMateMappedToDiffChromosomeMapQ5, failedVendorQuality.withMateMappedToDiffChromosomeMapQ5))
  }

}
