package com.redis.trino;

import static com.google.common.base.Verify.verify;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.redis.lettucemod.search.AggregateWithCursorResults;

import io.airlift.log.Logger;
import io.airlift.slice.SliceOutput;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.type.Type;

public class RediSearchPageSourceAggregate implements ConnectorPageSource {

	private static final Logger log = Logger.get(RediSearchPageSourceAggregate.class);

	private static final int ROWS_PER_REQUEST = 1024;

	private final RediSearchPageSourceResultWriter writer = new RediSearchPageSourceResultWriter();
	private final List<String> columnNames;
	private final List<Type> columnTypes;
	private final CursorIterator iterator;
	private Map<String, Object> currentDoc;
	private long count;
	private boolean finished;

	private final PageBuilder pageBuilder;

	public RediSearchPageSourceAggregate(RediSearchSession rediSearchSession, RediSearchTableHandle tableHandle,
			List<RediSearchColumnHandle> columns) {
		this.iterator = new CursorIterator(rediSearchSession, tableHandle);
		this.columnNames = columns.stream().map(RediSearchColumnHandle::getName).collect(toList());
		this.columnTypes = columns.stream().map(RediSearchColumnHandle::getType).collect(toList());
		this.currentDoc = null;
		this.pageBuilder = new PageBuilder(columnTypes);
	}

	@Override
	public long getCompletedBytes() {
		return count;
	}

	@Override
	public long getReadTimeNanos() {
		return 0;
	}

	@Override
	public boolean isFinished() {
		return finished;
	}

	@Override
	public long getMemoryUsage() {
		return 0L;
	}

	@Override
	public Page getNextPage() {
		verify(pageBuilder.isEmpty());
		count = 0;
		for (int i = 0; i < ROWS_PER_REQUEST; i++) {
			if (!iterator.hasNext()) {
				finished = true;
				break;
			}
			currentDoc = iterator.next();
			count++;

			pageBuilder.declarePosition();
			for (int column = 0; column < columnTypes.size(); column++) {
				BlockBuilder output = pageBuilder.getBlockBuilder(column);
				Object value = currentDoc.get(columnNames.get(column));
				if (value == null) {
					output.appendNull();
				} else {
					writer.appendTo(columnTypes.get(column), value.toString(), output);
				}
			}
		}
		Page page = pageBuilder.build();
		pageBuilder.reset();
		return page;
	}

	public static JsonGenerator createJsonGenerator(JsonFactory factory, SliceOutput output) throws IOException {
		return factory.createGenerator((OutputStream) output);
	}

	@Override
	public void close() {
		try {
			iterator.close();
		} catch (Exception e) {
			log.error("Could not close cursor iterator", e);
		}
	}

	private static class CursorIterator implements Iterator<Map<String, Object>>, AutoCloseable {

		private final RediSearchSession session;
		private final RediSearchTableHandle tableHandle;
		private Iterator<Map<String, Object>> iterator;
		private long cursor;

		public CursorIterator(RediSearchSession session, RediSearchTableHandle tableHandle) {
			this.session = session;
			this.tableHandle = tableHandle;
			read(session.aggregate(tableHandle));
		}

		private void read(AggregateWithCursorResults<String> results) {
			this.iterator = results.iterator();
			this.cursor = results.getCursor();
		}

		@Override
		public boolean hasNext() {
			while (!iterator.hasNext()) {
				if (cursor == 0) {
					return false;
				}
				read(session.cursorRead(tableHandle, cursor));
			}
			return true;
		}

		@Override
		public Map<String, Object> next() {
			return iterator.next();
		}

		@Override
		public void close() throws Exception {
			session.cursorDelete(tableHandle, cursor);
		}

	}
}