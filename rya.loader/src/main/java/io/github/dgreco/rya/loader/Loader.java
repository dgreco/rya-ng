/*
 * Copyright 2026 David Greco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.github.dgreco.rya.loader;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriterConfig;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.loader.gen.EntityGenerator;

/**
 * Loads this process's part of the dataset with {@code --threads} workers. Each worker has its
 * own DAO (one writer thread each; adds are synchronized per DAO), claims blocks of entities from
 * a shared cursor, writes each block with one {@code add} (which sums its statistics counters),
 * and flushes every commit interval; only flushed blocks are recorded in the progress file.
 */
public final class Loader {

    /** What a load did. */
    public record Result(long blocks, long committedBlocks, long triples, long millis, Throwable failure, boolean interrupted) {
        public boolean complete() {
            return failure == null && !interrupted && committedBlocks == blocks;
        }
    }

    private final AccumuloClient client;
    private final LoaderOptions options;
    private final PrintStream out;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong generatedTriples = new AtomicLong();
    private final AtomicLong generatedEntities = new AtomicLong();
    private RateLimiter limiter;

    public Loader(final AccumuloClient client, final LoaderOptions options, final PrintStream out) {
        this.client = client;
        this.options = options;
        this.out = out;
    }

    /** Asks the workers to finish their current block, flush, record progress and stop. */
    public void requestStop() {
        stop.set(true);
    }

    public Result run() throws Exception {
        StoreCheck.forLoad(client, options);
        final EntityGenerator generator = new EntityGenerator(options.genConfig());
        final long[] range = options.partRange();
        final int blockSize = options.blockSize();
        final long blocks = (range[1] - range[0] + blockSize - 1) / blockSize;
        final String fingerprint = options.genConfig().fingerprint() + "|" + options.tablePrefix() + "|" + range[0] + "-"
                + range[1] + "|" + blockSize + "|" + options.textIndex() + options.geoIndex() + options.textPredicates()
                + "|" + options.stats();
        final Progress progress = Progress.open(options.progressFile(), fingerprint, blocks, options.resume());
        limiter = options.maxRate() > 0 ? new RateLimiter(options.maxRate()) : null;

        out.printf(Locale.ROOT, "Loading entities [%,d, %,d) of %,d (part %d of %d) with %d threads%s%n", range[0], range[1],
                options.genConfig().entities(), options.part(), options.parts(), options.threads(),
                progress.committedBlocks() > 0 ? ", resuming after " + progress.committedBlocks() + " done blocks" : "");

        final AtomicLong cursor = new AtomicLong();
        final long start = System.currentTimeMillis();
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < options.threads(); t++) {
            final Thread w = new Thread(() -> work(generator, range[0], range[1], blockSize, blocks, cursor, progress),
                    "rya-loader-" + t);
            workers.add(w);
            w.start();
        }
        final Reporter reporter = new Reporter(progress, range, blockSize, start);
        while (workers.stream().anyMatch(Thread::isAlive)) {
            for (final Thread w : workers) {
                w.join(options.reportIntervalMs());
                reporter.report(false);
            }
        }
        reporter.report(true);
        final long millis = System.currentTimeMillis() - start;
        final Result result = new Result(blocks, progress.committedBlocks(), progress.committedTriples(), millis, failure.get(),
                stop.get() && failure.get() == null && !progress.complete());
        if (!result.complete()) {
            out.printf(Locale.ROOT, "%s. Progress is in %s; continue with the same command plus --resume.%n",
                    result.failure() != null ? "Load failed: " + result.failure() : "Load stopped", progress.file());
        } else {
            out.printf(Locale.ROOT, "Done: %,d triples in %s (%,d triples/s).%n", result.triples(), Reporter.duration(millis),
                    millis == 0 ? 0 : result.triples() * 1000 / millis);
        }
        return result;
    }

    private void work(final EntityGenerator generator, final long first, final long last, final int blockSize,
            final long blocks, final AtomicLong cursor, final Progress progress) {
        final AccumuloRdfConfiguration conf = options.rdfConf();
        conf.setFlush(false);
        final AccumuloRyaDAO dao = new AccumuloRyaDAO(client, conf);
        dao.setBatchWriterConfig(new BatchWriterConfig()
                .setMaxMemory(options.batchMemoryBytes())
                .setMaxWriteThreads(options.writerThreads())
                .setMaxLatency(options.maxLatencyMs(), TimeUnit.MILLISECONDS)
                .setTimeout(options.writerTimeoutMs(), TimeUnit.MILLISECONDS)
                .setDurability(options.durability()));
        final List<Long> pending = new ArrayList<>();
        long pendingTriples = 0;
        boolean failed = false;
        try {
            dao.init();
            long lastCommit = System.currentTimeMillis();
            while (!stop.get()) {
                final long block = cursor.getAndIncrement();
                if (block >= blocks) {
                    break;
                }
                if (progress.isCommitted(block)) {
                    continue;
                }
                final long from = first + block * blockSize;
                final long end = Math.min(from + blockSize, last);
                final List<RyaStatement> statements = new ArrayList<>((int) (end - from) * 9);
                for (long e = from; e < end; e++) {
                    statements.addAll(generator.generate(e));
                }
                if (limiter != null) {
                    limiter.acquire(statements.size());
                }
                dao.add(statements.iterator());
                pending.add(block);
                pendingTriples += statements.size();
                generatedTriples.addAndGet(statements.size());
                generatedEntities.addAndGet(end - from);
                if (System.currentTimeMillis() - lastCommit >= options.commitIntervalMs()) {
                    dao.flush();
                    progress.commit(pending, pendingTriples);
                    pending.clear();
                    pendingTriples = 0;
                    lastCommit = System.currentTimeMillis();
                }
            }
            dao.flush();
            progress.commit(pending, pendingTriples);
        } catch (final Throwable t) {
            failed = true;
            failure.compareAndSet(null, t);
            stop.set(true);
        } finally {
            try {
                dao.destroy();
            } catch (final RuntimeException e) {
                if (!failed) {
                    failure.compareAndSet(null, e);
                    stop.set(true);
                }
            }
        }
    }

    /** Progress lines: entities and triples, rates, ETA, and where a resume would restart. */
    private final class Reporter {
        private final Progress progress;
        private final long[] range;
        private final int blockSize;
        private final long start;
        private long lastTime;
        private long lastTriples;

        Reporter(final Progress progress, final long[] range, final int blockSize, final long start) {
            this.progress = progress;
            this.range = range;
            this.blockSize = blockSize;
            this.start = start;
            this.lastTime = start;
        }

        void report(final boolean last) {
            final long now = System.currentTimeMillis();
            if (!last && now - lastTime < options.reportIntervalMs()) {
                return;
            }
            final long triples = generatedTriples.get();
            final long entities = generatedEntities.get();
            final long total = range[1] - range[0];
            final double nowRate = (triples - lastTriples) * 1000.0 / Math.max(1, now - lastTime);
            final double avgRate = triples * 1000.0 / Math.max(1, now - start);
            final double done = Math.min(1.0, (double) progress.committedBlocks() * blockSize / Math.max(1, total));
            final long resumeAt = range[0] + progress.watermark() * blockSize;
            final String eta = avgRate <= 0 || done >= 1 ? "-" : duration((long) ((1 - done) / Math.max(1e-9, done)
                    * (now - start)));
            out.printf(Locale.ROOT, "[part %d/%d] %s/%s entities (%.1f%%) | %s triples | %s t/s now, %s avg | ETA %s | resume at %,d%n",
                    options.part(), options.parts(), human(entities), human(total), 100 * done, human(triples),
                    human((long) nowRate), human((long) avgRate), eta, Math.min(resumeAt, range[1]));
            lastTime = now;
            lastTriples = triples;
        }

        static String human(final long n) {
            if (n >= 1_000_000_000L) {
                return String.format(Locale.ROOT, "%.2fB", n / 1e9);
            }
            if (n >= 1_000_000L) {
                return String.format(Locale.ROOT, "%.1fM", n / 1e6);
            }
            if (n >= 10_000L) {
                return String.format(Locale.ROOT, "%.0fk", n / 1e3);
            }
            return Long.toString(n);
        }

        static String duration(final long millis) {
            final long s = millis / 1000;
            if (s >= 3600) {
                return String.format(Locale.ROOT, "%dh%02dm", s / 3600, (s % 3600) / 60);
            }
            if (s >= 60) {
                return String.format(Locale.ROOT, "%dm%02ds", s / 60, s % 60);
            }
            return s + "s";
        }
    }

    /** Spreads writes so this process stays under a number of triples per second. */
    static final class RateLimiter {
        private final double perMilli;
        private final long start = System.nanoTime();
        private long issued;

        RateLimiter(final long perSecond) {
            this.perMilli = perSecond / 1000.0;
        }

        void acquire(final long triples) throws InterruptedException {
            final long waitMillis;
            synchronized (this) {
                issued += triples;
                final double elapsed = (System.nanoTime() - start) / 1e6;
                waitMillis = (long) (issued / perMilli - elapsed);
            }
            if (waitMillis > 0) {
                Thread.sleep(waitMillis);
            }
        }
    }
}
