/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.rdd.variant

import com.google.common.io.Files
import htsjdk.samtools.ValidationStringency
import htsjdk.variant.vcf.VCFHeaderLine
import java.io.File
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{ Dataset, SQLContext }
import org.bdgenomics.adam.converters.VariantContextConverter
import org.bdgenomics.adam.models.{
  Coverage,
  ReferenceRegion,
  SequenceRecord,
  VariantContext
}
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.contig.NucleotideContigFragmentRDD
import org.bdgenomics.adam.rdd.feature.{ CoverageRDD, FeatureRDD }
import org.bdgenomics.adam.rdd.fragment.FragmentRDD
import org.bdgenomics.adam.rdd.read.AlignmentRecordRDD
import org.bdgenomics.adam.sql.{
  AlignmentRecord => AlignmentRecordProduct,
  Feature => FeatureProduct,
  Fragment => FragmentProduct,
  Genotype => GenotypeProduct,
  NucleotideContigFragment => NucleotideContigFragmentProduct,
  Variant => VariantProduct,
  VariantContext => VariantContextProduct
}
import org.bdgenomics.adam.util.ADAMFunSuite
import org.bdgenomics.formats.avro._

object GenotypeRDDSuite extends Serializable {

  def covFn(g: Genotype): Coverage = {
    Coverage(g.getContigName,
      g.getStart,
      g.getEnd,
      1)
  }

  def featFn(g: Genotype): Feature = {
    Feature.newBuilder
      .setContigName(g.getContigName)
      .setStart(g.getStart)
      .setEnd(g.getEnd)
      .build
  }

  def fragFn(g: Genotype): Fragment = {
    Fragment.newBuilder
      .setName(g.getContigName)
      .build
  }

  def ncfFn(g: Genotype): NucleotideContigFragment = {
    NucleotideContigFragment.newBuilder
      .setContigName(g.getContigName)
      .build
  }

  def readFn(g: Genotype): AlignmentRecord = {
    AlignmentRecord.newBuilder
      .setContigName(g.getContigName)
      .setStart(g.getStart)
      .setEnd(g.getEnd)
      .build
  }

  def varFn(g: Genotype): Variant = {
    Variant.newBuilder
      .setContigName(g.getContigName)
      .setStart(g.getStart)
      .setEnd(g.getEnd)
      .build
  }

  def vcFn(g: Genotype): VariantContext = {
    VariantContext(Variant.newBuilder
      .setContigName(g.getContigName)
      .setStart(g.getStart)
      .setEnd(g.getEnd)
      .build)
  }
}

class GenotypeRDDSuite extends ADAMFunSuite {

  val tempDir = Files.createTempDir()

  sparkTest("union two genotype rdds together") {
    val genotype1 = sc.loadGenotypes(testFile("gvcf_dir/gvcf_multiallelic.g.vcf"))
    val genotype2 = sc.loadGenotypes(testFile("small.vcf"))
    val union = genotype1.union(genotype2)
    assert(union.rdd.count === (genotype1.rdd.count + genotype2.rdd.count))
    assert(union.sequences.size === (genotype1.sequences.size + genotype2.sequences.size))
    assert(union.samples.size === 4)
  }

  sparkTest("round trip to parquet") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val outputPath = tmpLocation()
    genotypes.saveAsParquet(outputPath)
    val unfilteredGenotypes = sc.loadGenotypes(outputPath)
    assert(unfilteredGenotypes.rdd.count === 18)

    val predicate = ReferenceRegion.createPredicate(ReferenceRegion("1", 14399L, 14400L),
      ReferenceRegion("1", 752720L, 757721L),
      ReferenceRegion("1", 752790L, 752793L))
    val filteredGenotypes = sc.loadParquetGenotypes(outputPath,
      optPredicate = Some(predicate))
    assert(filteredGenotypes.rdd.count === 9)
    val starts = filteredGenotypes.rdd.map(_.getStart).distinct.collect.toSet
    assert(starts.size === 3)
    assert(starts(14396L))
    assert(starts(752720L))
    assert(starts(752790L))
  }

  sparkTest("round trip to partitioned parquet") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val outputPath = tmpLocation()
    genotypes.saveAsPartitionedParquet(outputPath)
    val unfilteredGenotypes = sc.loadPartitionedParquetGenotypes(outputPath)
    assert(unfilteredGenotypes.rdd.count === 18)
  }

  sparkTest("use broadcast join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
    val targets = sc.loadFeatures(targetsPath)

    val jRdd = genotypes.broadcastRegionJoin(targets)

    assert(jRdd.rdd.count === 9L)
  }

  sparkTest("use right outer broadcast join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
    val targets = sc.loadFeatures(targetsPath)

    val jRdd = genotypes.rightOuterBroadcastRegionJoin(targets)

    val c = jRdd.rdd.collect
    assert(c.count(_._1.isEmpty) === 3)
    assert(c.count(_._1.isDefined) === 9)
  }

  sparkTest("use shuffle join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.shuffleRegionJoin(targets)
    val jRdd0 = genotypes.shuffleRegionJoin(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    assert(jRdd.rdd.count === 9L)
    assert(jRdd0.rdd.count === 9L)

    val joinedGenotypes: GenotypeRDD = jRdd
      .transmute[Genotype, GenotypeProduct, GenotypeRDD]((rdd: RDD[(Genotype, Feature)]) => {
        rdd.map(_._1)
      })
    val tempPath = tmpLocation(".adam")
    joinedGenotypes.saveAsParquet(tempPath)
    assert(sc.loadGenotypes(tempPath).rdd.count === 9)
  }

  sparkTest("use right outer shuffle join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.rightOuterShuffleRegionJoin(targets)
    val jRdd0 = genotypes.rightOuterShuffleRegionJoin(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    val c = jRdd.rdd.collect
    val c0 = jRdd0.rdd.collect
    assert(c.count(_._1.isEmpty) === 3)
    assert(c0.count(_._1.isEmpty) === 3)
    assert(c.count(_._1.isDefined) === 9)
    assert(c0.count(_._1.isDefined) === 9)
  }

  sparkTest("use left outer shuffle join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.leftOuterShuffleRegionJoin(targets)
    val jRdd0 = genotypes.leftOuterShuffleRegionJoin(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    val c = jRdd.rdd.collect
    val c0 = jRdd0.rdd.collect
    assert(c.count(_._2.isEmpty) === 9)
    assert(c0.count(_._2.isEmpty) === 9)
    assert(c.count(_._2.isDefined) === 9)
    assert(c0.count(_._2.isDefined) === 9)
  }

  sparkTest("use full outer shuffle join to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.fullOuterShuffleRegionJoin(targets)
    val jRdd0 = genotypes.fullOuterShuffleRegionJoin(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    val c = jRdd.rdd.collect
    val c0 = jRdd0.rdd.collect
    assert(c.count(t => t._1.isEmpty && t._2.isEmpty) === 0)
    assert(c0.count(t => t._1.isEmpty && t._2.isEmpty) === 0)
    assert(c.count(t => t._1.isDefined && t._2.isEmpty) === 9)
    assert(c0.count(t => t._1.isDefined && t._2.isEmpty) === 9)
    assert(c.count(t => t._1.isEmpty && t._2.isDefined) === 3)
    assert(c0.count(t => t._1.isEmpty && t._2.isDefined) === 3)
    assert(c.count(t => t._1.isDefined && t._2.isDefined) === 9)
    assert(c0.count(t => t._1.isDefined && t._2.isDefined) === 9)
  }

  sparkTest("use shuffle join with group by to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.shuffleRegionJoinAndGroupByLeft(targets)
    val jRdd0 = genotypes.shuffleRegionJoinAndGroupByLeft(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    val c = jRdd.rdd.collect
    val c0 = jRdd0.rdd.collect
    assert(c.size === 9)
    assert(c0.size === 9)
    assert(c.forall(_._2.size == 1))
    assert(c0.forall(_._2.size == 1))
  }

  sparkTest("use right outer shuffle join with group by to pull down genotypes mapped to targets") {
    val genotypesPath = testFile("small.vcf")
    val targetsPath = testFile("small.1.bed")

    val genotypes = sc.loadGenotypes(genotypesPath)
      .transform(_.repartition(1))
    val targets = sc.loadFeatures(targetsPath)
      .transform(_.repartition(1))

    val jRdd = genotypes.rightOuterShuffleRegionJoinAndGroupByLeft(targets)
    val jRdd0 = genotypes.rightOuterShuffleRegionJoinAndGroupByLeft(targets, optPartitions = Some(4), 0L)

    // we can't guarantee that we get exactly the number of partitions requested,
    // we get close though
    assert(jRdd.rdd.partitions.length === 1)
    assert(jRdd0.rdd.partitions.length === 4)

    val c = jRdd.rdd.collect
    val c0 = jRdd0.rdd.collect

    assert(c.count(_._1.isDefined) === 18)
    assert(c0.count(_._1.isDefined) === 18)
    assert(c.filter(_._1.isDefined).count(_._2.size == 1) === 9)
    assert(c0.filter(_._1.isDefined).count(_._2.size == 1) === 9)
    assert(c.filter(_._1.isDefined).count(_._2.isEmpty) === 9)
    assert(c0.filter(_._1.isDefined).count(_._2.isEmpty) === 9)
    assert(c.count(_._1.isEmpty) === 3)
    assert(c0.count(_._1.isEmpty) === 3)
    assert(c.filter(_._1.isEmpty).forall(_._2.size == 1))
    assert(c0.filter(_._1.isEmpty).forall(_._2.size == 1))
  }

  sparkTest("convert back to variant contexts") {
    val genotypesPath = testFile("small.vcf")
    val genotypes = sc.loadGenotypes(genotypesPath)
    val variantContexts = genotypes.toVariantContexts

    assert(variantContexts.sequences.containsReferenceName("1"))
    assert(variantContexts.samples.nonEmpty)

    val vcs = variantContexts.rdd.collect
    assert(vcs.size === 6)

    val vc = vcs.head
    assert(vc.position.referenceName === "1")
    assert(vc.variant.variant.contigName === "1")
    assert(vc.genotypes.nonEmpty)
  }

  sparkTest("load parquet to sql, save, re-read from avro") {
    def testMetadata(gRdd: GenotypeRDD) {
      val sequenceRdd = gRdd.addSequence(SequenceRecord("aSequence", 1000L))
      assert(sequenceRdd.sequences.containsReferenceName("aSequence"))

      val headerRdd = gRdd.addHeaderLine(new VCFHeaderLine("ABC", "123"))
      assert(headerRdd.headerLines.exists(_.getKey == "ABC"))

      val sampleRdd = gRdd.addSample(Sample.newBuilder
        .setSampleId("aSample")
        .build)
      assert(sampleRdd.samples.exists(_.getSampleId == "aSample"))
    }

    val inputPath = testFile("small.vcf")
    val outputPath = tmpLocation()
    val rrdd = sc.loadGenotypes(inputPath)
    testMetadata(rrdd)
    val rdd = rrdd.transformDataset(ds => ds) // no-op but force to sql
    testMetadata(rdd)
    assert(rdd.dataset.count === 18)
    assert(rdd.rdd.count === 18)
    rdd.saveAsParquet(outputPath)
    val rdd2 = sc.loadGenotypes(outputPath)
    testMetadata(rdd2)
    assert(rdd2.rdd.count === 18)
    assert(rdd2.dataset.count === 18)
    val outputPath2 = tmpLocation()
    rdd.transform(rdd => rdd) // no-op but force to rdd
      .saveAsParquet(outputPath2)
    val rdd3 = sc.loadGenotypes(outputPath2)
    assert(rdd3.rdd.count === 18)
    assert(rdd3.dataset.count === 18)
  }

  sparkTest("transform genotypes to contig rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(contigs: NucleotideContigFragmentRDD) {
      val tempPath = tmpLocation(".adam")
      contigs.saveAsParquet(tempPath)

      assert(sc.loadContigFragments(tempPath).rdd.count === 18)
    }

    val contigs: NucleotideContigFragmentRDD = genotypes.transmute[NucleotideContigFragment, NucleotideContigFragmentProduct, NucleotideContigFragmentRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.ncfFn)
      })

    checkSave(contigs)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val contigsDs: NucleotideContigFragmentRDD = genotypes.transmuteDataset[NucleotideContigFragment, NucleotideContigFragmentProduct, NucleotideContigFragmentRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => {
          NucleotideContigFragmentProduct.fromAvro(
            GenotypeRDDSuite.ncfFn(r.toAvro))
        })
      })

    checkSave(contigsDs)
  }

  sparkTest("transform genotypes to coverage rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(coverage: CoverageRDD) {
      val tempPath = tmpLocation(".bed")
      coverage.save(tempPath, false, false)

      assert(sc.loadCoverage(tempPath).rdd.count === 18)
    }

    val coverage: CoverageRDD = genotypes.transmute[Coverage, Coverage, CoverageRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.covFn)
      })

    checkSave(coverage)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val coverageDs: CoverageRDD = genotypes.transmuteDataset[Coverage, Coverage, CoverageRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => GenotypeRDDSuite.covFn(r.toAvro))
      })

    checkSave(coverageDs)
  }

  sparkTest("transform genotypes to feature rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(features: FeatureRDD) {
      val tempPath = tmpLocation(".bed")
      features.save(tempPath, false, false)

      assert(sc.loadFeatures(tempPath).rdd.count === 18)
    }

    val features: FeatureRDD = genotypes.transmute[Feature, FeatureProduct, FeatureRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.featFn)
      })

    checkSave(features)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val featureDs: FeatureRDD = genotypes.transmuteDataset[Feature, FeatureProduct, FeatureRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => {
          FeatureProduct.fromAvro(
            GenotypeRDDSuite.featFn(r.toAvro))
        })
      })

    checkSave(featureDs)
  }

  sparkTest("transform genotypes to fragment rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(fragments: FragmentRDD) {
      val tempPath = tmpLocation(".adam")
      fragments.saveAsParquet(tempPath)

      assert(sc.loadFragments(tempPath).rdd.count === 18)
    }

    val fragments: FragmentRDD = genotypes.transmute[Fragment, FragmentProduct, FragmentRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.fragFn)
      })

    checkSave(fragments)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val fragmentsDs: FragmentRDD = genotypes.transmuteDataset[Fragment, FragmentProduct, FragmentRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => {
          FragmentProduct.fromAvro(
            GenotypeRDDSuite.fragFn(r.toAvro))
        })
      })

    checkSave(fragmentsDs)
  }

  sparkTest("transform genotypes to read rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(reads: AlignmentRecordRDD) {
      val tempPath = tmpLocation(".adam")
      reads.saveAsParquet(tempPath)

      assert(sc.loadAlignments(tempPath).rdd.count === 18)
    }

    val reads: AlignmentRecordRDD = genotypes.transmute[AlignmentRecord, AlignmentRecordProduct, AlignmentRecordRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.readFn)
      })

    checkSave(reads)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val readsDs: AlignmentRecordRDD = genotypes.transmuteDataset[AlignmentRecord, AlignmentRecordProduct, AlignmentRecordRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => {
          AlignmentRecordProduct.fromAvro(
            GenotypeRDDSuite.readFn(r.toAvro))
        })
      })

    checkSave(readsDs)
  }

  sparkTest("transform genotypes to variant rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(variants: VariantRDD) {
      val tempPath = tmpLocation(".adam")
      variants.saveAsParquet(tempPath)

      assert(sc.loadVariants(tempPath).rdd.count === 18)
    }

    val variants: VariantRDD = genotypes.transmute[Variant, VariantProduct, VariantRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.varFn)
      })

    checkSave(variants)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val variantsDs: VariantRDD = genotypes.transmuteDataset[Variant, VariantProduct, VariantRDD](
      (ds: Dataset[GenotypeProduct]) => {
        ds.map(r => {
          VariantProduct.fromAvro(
            GenotypeRDDSuite.varFn(r.toAvro))
        })
      })

    checkSave(variantsDs)
  }

  sparkTest("transform genotypes to variant context rdd") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))

    def checkSave(variantContexts: VariantContextRDD) {
      assert(variantContexts.rdd.count === 18)
    }

    val variantContexts: VariantContextRDD = genotypes.transmute[VariantContext, VariantContextProduct, VariantContextRDD](
      (rdd: RDD[Genotype]) => {
        rdd.map(GenotypeRDDSuite.vcFn)
      })

    checkSave(variantContexts)
  }

  sparkTest("loading genotypes then converting to variants yields same output as loading variants") {
    VariantContextConverter.setNestAnnotationInGenotypesProperty(sc.hadoopConfiguration,
      true)

    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val variants = sc.loadVariants(testFile("small.vcf"))

    assert(genotypes.toVariants().dataset.count() === genotypes.dataset.count())

    val g2v = genotypes.toVariants(dedupe = true)
    val variantsFromGenotypes = g2v.rdd.collect
    assert(variantsFromGenotypes.size === variants.rdd.count())

    val directlyLoadedVariants = variants.rdd.collect().toSet

    assert(variantsFromGenotypes.size === directlyLoadedVariants.size)
    variantsFromGenotypes.foreach(v => assert(directlyLoadedVariants(v)))
  }

  sparkTest("filter RDD bound genotypes to genotype filters passed") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterToFiltersPassed().rdd.count() === 10)
  }

  sparkTest("filter dataset bound genotypes to genotype filters passed") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterToFiltersPassed().dataset.count() === 10)
  }

  sparkTest("filter RDD bound genotypes by genotype quality") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterByQuality(60).rdd.count() === 15)
  }

  sparkTest("filter dataset bound genotypes by genotype quality") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterByQuality(60).dataset.count() === 15)
  }

  sparkTest("filter RDD bound genotypes by read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterByReadDepth(60).rdd.count() === 1)
  }

  sparkTest("filter dataset bound genotypes by read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterByReadDepth(60).dataset.count() === 1)
  }

  sparkTest("filter RDD bound genotypes by alternate read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterByAlternateReadDepth(60).rdd.count() === 1)
  }

  sparkTest("filter dataset bound genotypes by alternate read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterByAlternateReadDepth(60).dataset.count() === 1)
  }

  sparkTest("filter RDD bound genotypes by reference read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterByReferenceReadDepth(30).rdd.count() === 2)
  }

  sparkTest("filter dataset bound genotypes by reference read depth") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterByReferenceReadDepth(30).dataset.count() === 2)
  }

  sparkTest("filter RDD bound genotypes by sample") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterToSample("NA12892").rdd.count() === 6)
  }

  sparkTest("filter dataset bound genotypes by sample") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterToSample("NA12892").dataset.count() === 6)
  }

  sparkTest("filter RDD bound genotypes by samples") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterToSamples(Seq("NA12892", "NA12891")).rdd.count() === 12)
  }

  sparkTest("filter dataset bound genotypes by samples") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterToSamples(Seq("NA12892", "NA12891")).dataset.count() === 12)
  }

  sparkTest("filter RDD bound no call genotypes") {
    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    assert(genotypes.filterNoCalls().rdd.count() === 3)
  }

  sparkTest("filter dataset no call genotypes") {
    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    val genotypesDs = genotypes.transformDataset(ds => ds)
    assert(genotypesDs.filterNoCalls().dataset.count() === 3)
  }

  sparkTest("round trip gVCF END attribute without nested variant annotations rdd bound") {
    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    val first = genotypes.sort.rdd.collect.head
    assert(first.end === 16157602L)
    assert(first.variant.end === 16157602L)
    assert(first.variant.annotation === null)

    val path = new File(tempDir, "test.gvcf.vcf")
    genotypes.copyVariantEndToAttribute.toVariantContexts.saveAsVcf(path.getAbsolutePath,
      asSingleFile = true,
      deferMerging = false,
      disableFastConcat = false,
      ValidationStringency.SILENT)

    val vcfGenotypes = sc.loadGenotypes("%s/test.gvcf.vcf".format(tempDir))
    val firstVcfGenotype = vcfGenotypes.sort.rdd.collect.head
    assert(firstVcfGenotype.end === 16157602L)
    // variant end copied to END attribute before writing, so this is correct
    assert(firstVcfGenotype.variant.end === 16157602L)
    // ...but annotations are not populated on read
    assert(firstVcfGenotype.variant.annotation === null)
  }

  sparkTest("round trip gVCF END attribute without nested variant annotations dataset bound") {
    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    val first = genotypes.sort.dataset.first
    assert(first.end === Some(16157602L))
    assert(first.variant.get.end === Some(16157602L))
    assert(first.variant.get.annotation.isEmpty)

    val path = new File(tempDir, "test.gvcf.vcf")
    genotypes.copyVariantEndToAttribute.toVariantContexts.saveAsVcf(path.getAbsolutePath,
      asSingleFile = true,
      deferMerging = false,
      disableFastConcat = false,
      ValidationStringency.SILENT)

    val vcfGenotypes = sc.loadGenotypes("%s/test.gvcf.vcf".format(tempDir))
    val firstVcfGenotype = vcfGenotypes.sort.dataset.first
    assert(firstVcfGenotype.end === Some(16157602L))
    // variant end copied to END attribute before writing, so this is correct
    assert(firstVcfGenotype.variant.get.end === Some(16157602L))
    // ...but annotations are not populated on read
    assert(first.variant.get.annotation.isEmpty)
  }

  sparkTest("round trip gVCF END attribute with nested variant annotations rdd bound") {
    VariantContextConverter.setNestAnnotationInGenotypesProperty(sc.hadoopConfiguration, true)

    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    val first = genotypes.sort.rdd.collect.head
    assert(first.end === 16157602L)
    assert(first.variant.end === 16157602L)
    assert(first.variant.annotation.attributes.get("END") === "16157602")

    val path = new File(tempDir, "test.gvcf.vcf")
    genotypes.toVariantContexts.saveAsVcf(path.getAbsolutePath,
      asSingleFile = true,
      deferMerging = false,
      disableFastConcat = false,
      ValidationStringency.SILENT)

    val vcfGenotypes = sc.loadGenotypes("%s/test.gvcf.vcf".format(tempDir))
    val firstVcfGenotype = vcfGenotypes.sort.rdd.collect.head
    assert(firstVcfGenotype.end === 16157602L)
    assert(firstVcfGenotype.variant.end === 16157602L)
    assert(firstVcfGenotype.variant.annotation.attributes.get("END") === "16157602")
  }

  sparkTest("round trip gVCF END attribute with nested variant annotations dataset bound") {
    VariantContextConverter.setNestAnnotationInGenotypesProperty(sc.hadoopConfiguration, true)

    val genotypes = sc.loadGenotypes(testFile("gvcf_multiallelic/multiallelic.vcf"))
    val first = genotypes.sort.dataset.first
    assert(first.end === Some(16157602L))
    assert(first.variant.get.end === Some(16157602L))
    assert(first.variant.get.annotation.get.attributes.get("END") === Some("16157602"))

    val path = new File(tempDir, "test.gvcf.vcf")
    genotypes.toVariantContexts.saveAsVcf(path.getAbsolutePath,
      asSingleFile = true,
      deferMerging = false,
      disableFastConcat = false,
      ValidationStringency.SILENT)

    val vcfGenotypes = sc.loadGenotypes("%s/test.gvcf.vcf".format(tempDir))
    val firstVcfGenotype = vcfGenotypes.sort.dataset.first
    assert(firstVcfGenotype.end === Some(16157602L))
    assert(firstVcfGenotype.variant.get.end === Some(16157602L))
    assert(firstVcfGenotype.variant.get.annotation.get.attributes.get("END") === Some("16157602"))
  }
}
