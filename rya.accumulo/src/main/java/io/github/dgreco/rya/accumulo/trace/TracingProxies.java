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
package io.github.dgreco.rya.accumulo.trace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;

/** Dynamic proxies that report Accumulo client operations to the current {@link Trace}. */
final class TracingProxies {
    private TracingProxies() {
    }

    /** Marks our proxies, so a client is never wrapped twice. */
    interface Traced {
    }

    static AccumuloClient client(final AccumuloClient client) {
        if (client instanceof Traced) {
            return client;
        }
        return proxy(AccumuloClient.class, client, new ClientHandler(client));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Object target, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(TracingProxies.class.getClassLoader(), new Class<?>[] {type, Traced.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "traced " + target;
                        };
                    }
                    return handler.invoke(proxy, method, args);
                });
    }

    /** Calls the real object, rethrowing what it throws as is. */
    private static Object call(final Object target, final Method method, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ------------------------------------------------------------------ client

    private static final class ClientHandler implements InvocationHandler {
        private final AccumuloClient client;

        ClientHandler(final AccumuloClient client) {
            this.client = client;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final Object result = call(client, method, args);
            switch (method.getName()) {
                case "createMultiTableBatchWriter":
                    return proxy(MultiTableBatchWriter.class, result, new MultiWriterHandler((MultiTableBatchWriter) result));
                case "createBatchWriter":
                    return proxy(BatchWriter.class, result, new WriterHandler((BatchWriter) result, (String) args[0], true));
                default:
                    break;
            }
            final Trace trace = AccumuloTrace.current();
            if (trace == null) {
                return result;
            }
            final String table = args == null || args.length == 0 ? null : (String) args[0];
            final String auths = args != null && args.length > 1 && args[1] instanceof Authorizations a ? auths(a) : "the user's";
            return switch (method.getName()) {
                case "createScanner" -> proxy(Scanner.class, result, new ScanHandler(trace, (ScannerBase) result, "Scanner", table, auths));
                case "createBatchScanner" -> proxy(BatchScanner.class, result,
                        new ScanHandler(trace, (ScannerBase) result, "BatchScanner", table, auths));
                case "createBatchDeleter" -> proxy(BatchDeleter.class, result,
                        new ScanHandler(trace, (ScannerBase) result, "BatchDeleter", table, auths));
                default -> result;
            };
        }
    }

    private static String auths(final Authorizations a) {
        return a.isEmpty() ? "none" : a.toString();
    }

    // ------------------------------------------------------------------ scanners

    /** Records how a scanner is set up, and reports each scan it runs. Created only while a trace runs. */
    private static final class ScanHandler implements InvocationHandler {
        private final Trace trace;
        private final ScannerBase scanner;
        private final String op;
        private final String table;
        private final String auths;
        private final String reason;
        private final List<Range> ranges = new ArrayList<>();
        private final List<String> columns = new ArrayList<>();
        private final Map<String, IteratorSetting> iterators = new LinkedHashMap<>();
        private final List<CountingIterator> running = new ArrayList<>();

        ScanHandler(final Trace trace, final ScannerBase scanner, final String op, final String table, final String auths) {
            this.trace = trace;
            this.scanner = scanner;
            this.op = op;
            this.table = table;
            this.auths = auths;
            this.reason = trace.reason();
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.isDefault()) {
                // stream(), forEach(...), fetchColumnFamily(CharSequence)...: through this proxy's own methods.
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            switch (method.getName()) {
                case "iterator":
                    return start((Iterator<?>) call(scanner, method, args));
                case "delete":
                    return delete(method, args);
                case "close":
                    try {
                        return call(scanner, method, args);
                    } finally {
                        endRunning("closed");
                    }
                default:
                    break;
            }
            final Object result = call(scanner, method, args);
            record(method, args);
            return result;
        }

        private synchronized void record(final Method method, final Object[] args) {
            switch (method.getName()) {
                case "setRange" -> {
                    ranges.clear();
                    ranges.add((Range) args[0]);
                }
                case "setRanges" -> {
                    ranges.clear();
                    ranges.addAll((Collection<Range>) args[0]);
                }
                case "fetchColumnFamily" -> columns.add("family " + TraceText.text((Text) args[0]));
                case "fetchColumn" -> {
                    if (args.length == 2) {
                        final String cq = TraceText.text((Text) args[1]);
                        columns.add("column " + TraceText.text((Text) args[0]) + " : " + (cq.isEmpty() ? "(empty qualifier)" : cq));
                    } else {
                        columns.add("column " + args[0]);
                    }
                }
                case "clearColumns" -> columns.clear();
                case "addScanIterator" -> {
                    final IteratorSetting s = (IteratorSetting) args[0];
                    iterators.put(s.getName(), s);
                }
                case "removeScanIterator" -> iterators.remove((String) args[0]);
                case "clearScanIterators" -> iterators.clear();
                default -> {
                }
            }
        }

        private synchronized int opened() {
            final List<String> shown = new ArrayList<>();
            final List<Range> all = ranges.isEmpty() && op.equals("Scanner") ? List.of(new Range()) : ranges;
            for (final Range r : all) {
                if (shown.size() >= Trace.MAX_RANGES) {
                    break;
                }
                shown.add(TraceText.range(r));
            }
            final List<String> its = new ArrayList<>();
            for (final IteratorSetting s : iterators.values()) {
                its.add(TraceText.iterator(s));
            }
            return trace.scanStarted(op, table, shown, all.size(), List.copyOf(columns), its, auths, reason);
        }

        private Iterator<?> start(final Iterator<?> real) {
            final CountingIterator it = new CountingIterator(trace, opened(), real);
            synchronized (this) {
                running.add(it);
            }
            return it;
        }

        private Object delete(final Method method, final Object[] args) throws Throwable {
            final int id = opened();
            final long t0 = System.nanoTime();
            try {
                return call(scanner, method, args);
            } finally {
                trace.scanEnded(id, -1, System.nanoTime() - t0, "deleted");
            }
        }

        private void endRunning(final String how) {
            final List<CountingIterator> its;
            synchronized (this) {
                its = new ArrayList<>(running);
                running.clear();
            }
            for (final CountingIterator it : its) {
                it.end(how);
            }
        }
    }

    /** Counts what a scan returns and the time spent in Accumulo; reports the end once. */
    private static final class CountingIterator implements Iterator<Map.Entry<Key, Value>> {
        private final Trace trace;
        private final int id;
        private final Iterator<Map.Entry<Key, Value>> real;
        private long entries;
        private long nanos;
        private boolean ended;

        @SuppressWarnings("unchecked")
        CountingIterator(final Trace trace, final int id, final Iterator<?> real) {
            this.trace = trace;
            this.id = id;
            this.real = (Iterator<Map.Entry<Key, Value>>) real;
        }

        @Override
        public boolean hasNext() {
            final long t0 = System.nanoTime();
            final boolean more;
            try {
                more = real.hasNext();
            } finally {
                add(System.nanoTime() - t0);
            }
            if (!more) {
                end("exhausted");
            }
            return more;
        }

        @Override
        public Map.Entry<Key, Value> next() {
            final long t0 = System.nanoTime();
            try {
                final Map.Entry<Key, Value> e = real.next();
                synchronized (this) {
                    entries++;
                }
                return e;
            } catch (final NoSuchElementException e) {
                end("exhausted");
                throw e;
            } finally {
                add(System.nanoTime() - t0);
            }
        }

        private synchronized void add(final long n) {
            nanos += n;
        }

        void end(final String how) {
            final long e;
            final long n;
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                e = entries;
                n = nanos;
            }
            trace.scanEnded(id, e, n, how);
        }
    }

    // ------------------------------------------------------------------ writers

    /**
     * Wraps writers whether or not a trace runs (the DAO creates them once, at startup), and
     * reports to the trace of the thread that adds or flushes.
     */
    private static final class MultiWriterHandler implements InvocationHandler {
        private final MultiTableBatchWriter writer;

        MultiWriterHandler(final MultiTableBatchWriter writer) {
            this.writer = writer;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getName().equals("getBatchWriter")) {
                final BatchWriter w = (BatchWriter) call(writer, method, args);
                return proxy(BatchWriter.class, w, new WriterHandler(w, (String) args[0], false));
            }
            if (method.getName().equals("flush") || method.getName().equals("close")) {
                return flushing(writer, method, args);
            }
            return call(writer, method, args);
        }
    }

    private static Object flushing(final Object writer, final Method method, final Object[] args) throws Throwable {
        final Trace trace = AccumuloTrace.current();
        final long t0 = System.nanoTime();
        try {
            return call(writer, method, args);
        } finally {
            if (trace != null) {
                trace.flushed(System.nanoTime() - t0);
            }
        }
    }

    private static final class WriterHandler implements InvocationHandler {
        private final BatchWriter writer;
        private final String table;
        /** Whether this writer's own flush sends its mutations (false inside a multi-table writer). */
        private final boolean ownFlush;

        WriterHandler(final BatchWriter writer, final String table, final boolean ownFlush) {
            this.writer = writer;
            this.table = table;
            this.ownFlush = ownFlush;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final Trace trace = AccumuloTrace.current();
            switch (method.getName()) {
                case "addMutation":
                    if (trace != null) {
                        trace.wrote(table, (Mutation) args[0]);
                    }
                    return call(writer, method, args);
                case "addMutations":
                    if (trace == null) {
                        return call(writer, method, args);
                    }
                    // The Iterable may be readable only once: count a copy and hand the copy on.
                    final List<Mutation> copy = new ArrayList<>();
                    for (final Object m : (Iterable<?>) args[0]) {
                        copy.add((Mutation) m);
                        trace.wrote(table, (Mutation) m);
                    }
                    return call(writer, method, new Object[] {copy});
                case "flush":
                case "close":
                    return ownFlush ? flushing(writer, method, args) : call(writer, method, args);
                default:
                    return call(writer, method, args);
            }
        }
    }
}
