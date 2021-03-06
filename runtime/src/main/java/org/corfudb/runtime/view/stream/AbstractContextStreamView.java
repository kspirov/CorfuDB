package org.corfudb.runtime.view.stream;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.logprotocol.LogEntry;
import org.corfudb.protocols.logprotocol.StreamCOWEntry;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.view.Address;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BiFunction;
import java.util.function.Function;

/** An abstract context stream view maintains contexts, which are used to
 * implement copy-on-write entries.
 *
 *  This implementation uses "contexts" to properly deal with copy-on-write
 * streams. Every time a stream is copied, a new context is created which
 * redirects requests to the source stream for the copy - each context
 * contains its own queue and pointers. Implementers of fillReadQueue() and
 * readAndUpdatePointers should be careful to use the id of the context,
 * rather than that of the stream view itself.
 *
 * Created by mwei on 1/6/17.
 */
@Slf4j
public abstract class AbstractContextStreamView<T extends AbstractStreamContext>
        implements IStreamView, AutoCloseable {

    /**
     * The ID of the stream.
     */
    @Getter
    final UUID ID;

    /**
     * The runtime the stream view was created with.
     */
    final CorfuRuntime runtime;

    /**
     * An ordered set of stream contexts, which store information
     * about a stream copied via copy-on-write entries. Streams which
     * have never been copied have only a single context.
     */
    final NavigableSet<T> streamContexts;

    /** A function which creates a base context, representing the original
     * stream.
     */
    final BiFunction<UUID, Long, T> baseContextFactory;

    /** Create a new abstract context stream view.
     *
     * @param runtime               The runtime.
     * @param id                    The id of the stream.
     * @param baseContextFactory    A function which generates a context,
     *                              given the stream id and a maximum global
     *                              address.
     */
    public AbstractContextStreamView(final CorfuRuntime runtime,
                                     final UUID id,
                                     final BiFunction<UUID, Long, T>
                                             baseContextFactory) {
        this.ID = id;
        this.runtime = runtime;
        this.streamContexts = new ConcurrentSkipListSet<>();
        this.baseContextFactory = baseContextFactory;
        this.streamContexts.add(baseContextFactory.apply(id, Address.MAX));
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void reset() {
        this.streamContexts.clear();
        this.streamContexts.add(baseContextFactory.apply(ID, Address.MAX));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {}

    /**
     * {@inheritDoc}
     */
    @Override
    final public synchronized ILogData nextUpTo(final long maxGlobal) {
        // Don't do anything if we've already exceeded the global
        // pointer.
        if (getCurrentContext().globalPointer > maxGlobal) {
            return null;
        }

        // Pop the context if it has changed.
        if (getCurrentContext().globalPointer >=
                getCurrentContext().maxGlobalAddress) {
            final T last = streamContexts.pollFirst();
            log.trace("Completed context {}@{}, removing.",
                    last.id, last.maxGlobalAddress);
        }

        // Get the next entry from the underlying implementation.
        final ILogData entry =
                getNextEntry(getCurrentContext(), maxGlobal);

        if (entry != null) {
            // Update the pointer.
            updatePointer(entry);

            // Process the next entry, checking if the context has changed.
            // If the context has changed, we read again, since this entry
            // does not contain any data, and we need to follow the new
            // context.
            if (processEntryForContext(entry)) {
                return nextUpTo(maxGlobal);
            }
        }

        // Return the entry.
        return entry;
    }

    /** {@inheritDoc}
     */
    @Override
    public final synchronized List<ILogData> remainingUpTo(long maxGlobal) {

        // Pop the context if it has changed.
        if (getCurrentContext().globalPointer >=
                getCurrentContext().maxGlobalAddress) {
            final T last = streamContexts.pollFirst();
            log.trace("Completed context {}@{}, removing.",
                    last.id, last.maxGlobalAddress);
        }

        final List<ILogData> entries = getNextEntries(getCurrentContext(), maxGlobal,
                this::doesEntryUpdateContext);

        // Nothing read, nothing to process.
        if (entries.size() == 0) {
            return entries;
        }

        // Check if the last entry updates the context.
        if (doesEntryUpdateContext(entries.get(entries.size() - 1)))
        {
            // The entry which updates the context must be the last one, so
            // process it
            processEntryForContext(entries.get(entries.size() - 1));

            // Remove the entry which updates the context
            entries.remove(entries.size() - 1);

            // do a read again, which will consume the inner context
            entries.addAll(remainingUpTo(maxGlobal));

            // and now read again, in case the context returned, to make
            // sure we get up to maxGlobal.
            entries.addAll(remainingUpTo(maxGlobal));
        }

        // Otherwise update the pointer with the last entry
        updatePointer(entries.get(entries.size() - 1));

        // And return the entries.
        return entries;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return getHasNext(getCurrentContext());
    }

    /** Return whether calling getNextEntry() may return more
     * entries, given the context.
     * @param context       The context to retrieve the next entry from.
     * @return              True, if getNextEntry() may return an entry.
     *                      False otherwise.
     */
    abstract protected boolean getHasNext(T context);

    /** Retrieve the next entry in the stream, given the context.
     *
     * @param context       The context to retrieve the next entry from.
     * @param maxGlobal     The maximum global address to read to.
     * @return
     */
    abstract protected ILogData getNextEntry(T context, long maxGlobal);

    /** Retrieve the next entries in the stream, given the context.
     *
     * This function is designed to implement a bulk read. In a bulk read,
     * one of the entries may cause the context to change - the implementation
     * should check if the entry changes the context and stop reading
     * if this occurs, returning the entry that caused contextCheckFn to return
     * true.
     *
     * The default implementation simply calls getNextEntry.
     *
     * @param context           The context to retrieve the next entry from.
     * @param maxGlobal         The maximum global address to read to.
     * @param contextCheckFn    A function which returns true if the entry changes the stream context.
     * @return
     */
    protected List<ILogData> getNextEntries(T context, long maxGlobal,
                                                     Function<ILogData, Boolean> contextCheckFn) {
        final List<ILogData> dataList = new ArrayList<>();
        ILogData thisData;
        while ((thisData = getNextEntry(context, maxGlobal)) != null) {
            // Add this read to the list of reads to return.
            dataList.add(thisData);

            // Update the pointer, because the underlying implementation
            // will expect it to be updated when we call getNextEntry() again.
            updatePointer(thisData);

            // If this entry changes the context, don't continue reading.
            if (contextCheckFn.apply(thisData)) {
                break;
            }
        }
        return dataList;
    }

    /** Check whether the given entry updates the context.
     *
     * @param data  The entry to check.
     * @return      True, if the entry will update the context.
     */
    protected boolean doesEntryUpdateContext(final ILogData data) {
        return data.containsStream(getCurrentContext().id) &&
                data.getPayload(runtime) instanceof StreamCOWEntry;
    }

    /** Update the global pointer, given an entry.
     *
     * @param data  The entry to use to update the pointer.
     */
    protected void updatePointer(final ILogData data) {
        // Update the global pointer, if it is data.
        if (data.getType() == DataType.DATA) {
            getCurrentContext().globalPointer =
                    data.getGlobalAddress();
        }
    }

    /** Check if the given entry adds a new context, and update
     * the global pointer.
     *
     * If it does, add it to the context stack. Otherwise,
     * pop the context.
     *
     * It is important that this method be called in order, since
     * it updates the global pointer and can change the global pointer.
     *
     * @param data  The entry to process.
     * @return      True, if this entry adds a context.
     */
    protected boolean processEntryForContext(final ILogData data) {
        if (data != null) {
            final Object payload = data.getPayload(runtime);
            // If this is a COW entry, we update the context as well.
            if (payload instanceof StreamCOWEntry) {
                StreamCOWEntry ce = (StreamCOWEntry) payload;
                streamContexts
                        .add(baseContextFactory.apply(ce.getOriginalStream(),
                                ce.getFollowUntil()));
                return true;
            }
        }
        return false;
    }

    /** Get the current context. */
    protected T getCurrentContext() {
        return streamContexts.first();
    }

}
