# The Spark build

`tripatha.spark` loads a Tripatha store on Accumulo as a Spark job. It is the loader's bulk build (`run.sh build`, [SCALE-STUDY.md](../SCALE-STUDY.md) O3) spread over a cluster's executors instead of one machine's threads. It reads one of two inputs:

- **`generate`:** the loader's generated dataset (the same entities, triples and options as `run.sh load` and `build`), into a store that `run.sh prepare` prepared;
- **`files`:** N-Triples and N-Quads files (`.nt`, `.nq`, `.ntriples`, `.nquads`, optionally `.gz` or `.bz2`; RDF 1.2 included), into a new store, or with `--append` into one that holds statements.

It needs Spark 4.2 on Java 25 (Spark 4.2 runs on Hadoop 3.5, whose client works on Java 25) and Accumulo 2.1.

## How it works

1. **The driver plans.** It checks the store (for `generate`, that `prepare` made it for the same dataset and indexes), samples the input, and fixes each table's files.
   - **On a new store,** it adds split points as `prepare` does: each table's estimated size on disk over `--tablet-size`, at least 4 × tablet servers − 1.
   - **Each table's files** are its tablets, cut further at rows of the sample so that a file holds about `--file-size` of cells, with at most 50 files per tablet.
2. **The executors write the cells `load` would write.** Each task writes its statements through Tripatha's own DAO, over a store that only captures what the DAO writes, so every cell is the one an online load writes. That includes every index and counter, the datatype markers and the graph markers.
3. **Spark's shuffle sorts the cells.** Each task first adds up its counter deltas across its write calls. The shuffle then only sorts each partition in Accumulo's key order, and each file's writer adds up what is left and keeps a put's newest version. A sort alone costs less than Spark's own combining, which hashes every cell.
4. **Each partition becomes one RFile** under `--work-dir`, written with its table's configuration. Totals of 1 are left out in the six counter families but `p`, as the loader's build leaves them out (the planner reads a missing counter as 1), and only when the totals are the store's: a new store or a generated dataset.
5. **The driver imports the files,** table by table, with load plans that name each file's rows.

Only the finished RFiles go to S3. The shuffle's spills stay on the executors' local disks, and the executors need no access to Accumulo or its credentials, only to the work directory's file system.

**The work directory records the job:** `job.properties` holds its input's fingerprint, its timestamp and its state, and `manifest.properties` the files' rows.
- **The write phase** can be run again: it starts over and writes the same cells. There is one timestamp per job, and files are named after their table and range, never after a task.
- **Once an import has started,** the files are never written again, since Accumulo adds imported counters to what a table holds. A file that is gone was imported, so `--phase import` after a failure imports what is left.

**Blank nodes:** a label is scoped to its file, but one file is read in parts by different tasks. Each label is kept and prefixed with the file's id (a hash of its path, length and modification time), so the parts of a file agree on a label and two files never share one.

**Repeated statements:** without `--distinct`, a statement that appears twice is stored once but counted twice, as with `load`. `--distinct` removes repeats first, as the store tells statements apart (by their spo cell), at the cost of one more shuffle.

**Bad lines** (a line that doesn't parse, or a statement the store refuses) are logged and left out, up to `--max-bad-lines` (0 by default), and then the job stops.

## Requirements on the cluster

- **The work directory must be on one of Accumulo's volumes** (`instance.volumes`): Accumulo imports only from a file system it stores on, and moves the files into the table. With Accumulo on S3, use a prefix of the same bucket, for instance `s3a://accumulo/tripatha-bulk/run-1`.
- **The executors need Hadoop's S3 connector** (`hadoop-aws` 3.5.0 and the AWS SDK bundle it names) and its settings. With Ceph or another S3-compatible store, path-style access is usually needed:

  ```
  spark.hadoop.fs.s3a.endpoint            https://rgw.example.internal
  spark.hadoop.fs.s3a.path.style.access   true
  spark.hadoop.fs.s3a.endpoint.region     us-east-1
  ```

  Credentials go through the usual S3A providers: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` from a Kubernetes secret, or `fs.s3a.access.key` and `fs.s3a.secret.key`. **The executors need them as well as the driver:** they write the RFiles. If `smoke` fails against Ceph with checksum or HTTP 400 errors, see Hadoop's notes on [S3A with third-party stores](https://hadoop.apache.org/docs/r3.5.0/hadoop-aws/tools/hadoop-aws/third_party_stores.html).
- **The executors need local disk for the shuffle:** about 150 bytes per triple across the executors, compressed, plus the sort's spills (at 500M triples, about 100 GB). Kubernetes' default `emptyDir` and ephemeral-storage limits can evict pods in the middle of a job. Give the executors volumes named `spark-local-dir-*`, for instance a persistent volume claim per executor (Spark's [local storage](https://spark.apache.org/docs/4.2.1/running-on-kubernetes.html#local-storage)).
- **The driver needs Accumulo:** ZooKeeper and the tablet servers, `--accumulo-properties FILE` (the loader's client properties; the password can come from `TRIPATHA_ACCUMULO_PASSWORD`), and an account that can create tables, add splits and bulk-import. It holds the plan's sample (up to 200,000 rows per table): give it 4 GB (`spark.driver.memory`).
- **No speculative execution** (`spark.speculation`, off by default): two attempts of a task would write one file at once. The job refuses to start with it on.

## Build

```bash
mvn -pl tripatha.spark -am package -DskipTests -DskipITs
# the job's jar: tripatha.spark/target/tripatha.spark-1.0.0-SNAPSHOT-job.jar
```

The jar holds Tripatha, Accumulo's client and RDF4J. Spark, Scala, Hadoop and the logging are the cluster's. Thrift, Guava, Gson, Protobuf and Caffeine are relocated under `org.tripatha.shaded`, since Spark's own jars carry other versions.

## Run on Kubernetes

**First, the smoke test.** Each executor writes a small RFile under the work directory, and the driver imports the files into a scratch table, reads them back and drops the table. It prints each executor's Java and Hadoop versions and file system, and it fails where a build would: executors that can't run the code or reach S3, or a directory Accumulo can't import from.

```bash
spark-submit \
  --master k8s://https://KUBERNETES_API:6443 --deploy-mode cluster \
  --name tripatha-smoke \
  --class org.tripatha.spark.SparkBuild \
  --conf spark.kubernetes.container.image=YOUR_SPARK_4.2_JAVA_25_IMAGE \
  --conf spark.driver.memory=4g \
  --conf spark.kubernetes.driver.secrets.tripatha-accumulo=/etc/tripatha \
  --conf spark.kubernetes.driver.secretKeyRef.TRIPATHA_ACCUMULO_PASSWORD=tripatha-accumulo:password \
  --conf spark.kubernetes.driver.secretKeyRef.AWS_ACCESS_KEY_ID=s3-credentials:access-key \
  --conf spark.kubernetes.driver.secretKeyRef.AWS_SECRET_ACCESS_KEY=s3-credentials:secret-key \
  --conf spark.kubernetes.executor.secretKeyRef.AWS_ACCESS_KEY_ID=s3-credentials:access-key \
  --conf spark.kubernetes.executor.secretKeyRef.AWS_SECRET_ACCESS_KEY=s3-credentials:secret-key \
  --conf spark.hadoop.fs.s3a.endpoint=https://rgw.example.internal \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  s3a://jobs/tripatha.spark-1.0.0-SNAPSHOT-job.jar \
  smoke --table-prefix t500m_ --accumulo-properties /etc/tripatha/accumulo-client.properties \
        --work-dir s3a://accumulo/tripatha-bulk/smoke
```

**Then the load.** For the loader's dataset at 500M triples, prepare the store first with the loader, then run the same dataset options in the job:

```bash
tripatha.loader/run.sh prepare --table-prefix t500m_ --accumulo-properties accumulo-client.properties --triples 500M

spark-submit ...the same --conf as above... \
  --conf spark.executor.instances=16 --conf spark.executor.cores=4 --conf spark.executor.memory=12g \
  --conf spark.kubernetes.executor.volumes.persistentVolumeClaim.spark-local-dir-1.options.claimName=OnDemand \
  --conf spark.kubernetes.executor.volumes.persistentVolumeClaim.spark-local-dir-1.options.storageClass=YOUR_STORAGE_CLASS \
  --conf spark.kubernetes.executor.volumes.persistentVolumeClaim.spark-local-dir-1.options.sizeLimit=100Gi \
  --conf spark.kubernetes.executor.volumes.persistentVolumeClaim.spark-local-dir-1.mount.path=/data \
  --conf spark.kubernetes.executor.volumes.persistentVolumeClaim.spark-local-dir-1.mount.readOnly=false \
  s3a://jobs/tripatha.spark-1.0.0-SNAPSHOT-job.jar \
  generate --table-prefix t500m_ --accumulo-properties /etc/tripatha/accumulo-client.properties --triples 500M \
           --work-dir s3a://accumulo/tripatha-bulk/t500m
```

For RDF files, a new store is created and split by the job itself:

```bash
spark-submit ... s3a://jobs/tripatha.spark-1.0.0-SNAPSHOT-job.jar \
  files --table-prefix dump_ --accumulo-properties /etc/tripatha/accumulo-client.properties \
        --input s3a://datasets/dump/ --work-dir s3a://accumulo/tripatha-bulk/dump --text-index
```

The endpoint then uses the same table prefix and index settings. Delete the work directory once the job prints `Import: … can be deleted`.

Notes:
- **Compressed input:** a `.gz` file can't be split, so one task reads it whole. Many files, or uncompressed or `.bz2` ones, read in parallel.
- **Format:** N-Triples and N-Quads are read line by line. Convert Turtle, RDF/XML or JSON-LD to N-Quads first (for instance with `riot` or `rapper`).
- **Labels:** N-Quads has no place for a visibility label, so `--visibility EXPR` gives every statement of the job the same one.
- **Sizes:** `--file-size` (MB of cells before compression, 1024 by default) sets the size of a file and of a reduce task. A smaller size makes more, shorter reduce tasks, and more files per tablet (at most 50). The shuffle moves a little more than the 3.5 cells per statement the files hold (counters not yet added up across tasks), roughly 100 bytes each before Spark compresses them, and spills them to the executors' local disks.
- **Executors:** 4 cores and 12 GB each is a reasonable start. A task holds its counters to add up (about 80 MB) besides Spark's sort buffers.

## Measured

At 10M triples on one machine (12 cores, Spark in local mode on 10, the Accumulo test cluster), 2026-11-21:

| | Time |
|---|---|
| The loader's `build`, 10 threads | 50.8 s |
| `generate` | 63.5 s: map 13.6, reduce 43.8, import 1.5 |
| `files`: WatDiv sf100, 10,950,947 triples in 1.55 GB of N-Triples | 52.3 s: sample 0.7, map 14.2, reduce 28.5 |

On one machine the job costs 1.25 times the loader's build. On a cluster both stages spread over every executor. Details in [SCALE-STUDY.md](../SCALE-STUDY.md) section 4.28.

## Try it on one machine

`LocalCluster` (test sources) starts a throwaway Accumulo whose volume is the local disk, and writes its client properties:

```bash
mvn -pl tripatha.spark -am install -DskipTests -DskipITs
mvn -pl tripatha.spark dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/cp.txt
java -cp tripatha.spark/target/test-classes:$(cat /tmp/cp.txt) org.tripatha.spark.LocalCluster /tmp/acc &

spark-submit --master 'local[8]' --class org.tripatha.spark.SparkBuild tripatha.spark/target/tripatha.spark-1.0.0-SNAPSHOT-job.jar \
  smoke --table-prefix try_ --accumulo-properties /tmp/acc/accumulo-client.properties --work-dir /tmp/acc-work
```

## Tests

`SparkBuildIT` runs the job on local Spark against the test cluster (Accumulo only).
- **`generate`** writes every cell the loader's own build writes, table by table: statements, every index, counters, text counts and graph markers.
- **`files`** writes every cell a DAO load of the same statements writes. It covers blank nodes across splits and across files, graphs, triple terms, a base direction, a label, a repeated statement, a bad line, `--distinct`, a write run again after a failure, and the refusal of a store that holds statements.
- **`smoke`** passes, and drops its table.

`SparkJava25SpikeTest` is the first check: Spark 4.2's tasks run Tripatha's encoding on Java 25.
