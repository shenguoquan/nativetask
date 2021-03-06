package org.apache.hadoop.mapred.nativetask.handlers;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.mapred.RawKeyValueIterator;
import org.apache.hadoop.mapred.nativetask.Constants;
import org.apache.hadoop.mapred.nativetask.DataReceiver;
import org.apache.hadoop.mapred.nativetask.NativeDataSource;
import org.apache.hadoop.mapred.nativetask.buffer.BufferType;
import org.apache.hadoop.mapred.nativetask.buffer.ByteBufferDataReader;
import org.apache.hadoop.mapred.nativetask.buffer.InputBuffer;
import org.apache.hadoop.mapred.nativetask.serde.SerializationFramework;
import org.apache.hadoop.mapred.nativetask.util.ReadWriteBuffer;
import org.apache.hadoop.util.Progress;

public class BufferPuller implements RawKeyValueIterator, DataReceiver {
  
  private static Log LOG = LogFactory.getLog(BufferPuller.class);

  public final static int KV_HEADER_LENGTH = Constants.SIZEOF_KV_LENGTH;

  byte[] keyBytes = new byte[0];
  byte[] valueBytes = new byte[0];

  private InputBuffer inputBuffer;
  private InputBuffer asideBuffer;

  int remain = 0;

  private ByteBufferDataReader nativeReader;

  DataInputBuffer keyBuffer = new DataInputBuffer();
  DataInputBuffer valueBuffer = new DataInputBuffer();

  private boolean noMoreData = false;

  private NativeDataSource input;
  private boolean closed = false;

  public BufferPuller(NativeDataSource handler) throws IOException {
    this.input = handler;
    this.inputBuffer = handler.getInputBuffer();
    nativeReader = new ByteBufferDataReader(null);
    this.asideBuffer = new InputBuffer(BufferType.HEAP_BUFFER, inputBuffer.capacity());
  }

  @Override
  public DataInputBuffer getKey() throws IOException {
    return keyBuffer;
  }

  @Override
  public DataInputBuffer getValue() throws IOException {
    return valueBuffer;
  }
  
  public void reset() {
    noMoreData = false;
  }

  @Override
  public boolean next() throws IOException {
    if (closed) {
      return false;
    }
    
    if (noMoreData) {
      return false;
    }
    final int asideRemain = asideBuffer.remaining();
    final int inputRemain = inputBuffer.remaining();

    if (asideRemain == 0 && inputRemain == 0) {
      input.loadData();
    }

    if (asideBuffer.remaining() > 0) {
      return nextKeyValue(asideBuffer);
    } else if (inputBuffer.remaining() > 0) {
      return nextKeyValue(inputBuffer);
    } else {
      noMoreData = true;
      return false;
    }
  }

  private boolean nextKeyValue(InputBuffer buffer) throws IOException {
    if (closed) {
      return false;
    }
    
    nativeReader.reset(buffer);

    final int keyLength = nativeReader.readInt();
    if (keyBytes.length < keyLength) {
      keyBytes = new byte[keyLength];
    }

    final int valueLength = nativeReader.readInt();
    if (valueBytes.length < valueLength) {
      valueBytes = new byte[valueLength];
    }
    
    nativeReader.read(keyBytes, 0, keyLength);
    nativeReader.read(valueBytes, 0, valueLength);

    keyBuffer.reset(keyBytes, keyLength);
    valueBuffer.reset(valueBytes, valueLength);

    return true;
  }

  @Override
  public boolean receiveData() throws IOException {
    if (closed) {
      return false;
    }
    
    final ByteBuffer input = inputBuffer.getByteBuffer();
    
    if (null != asideBuffer && asideBuffer.length() > 0) {
      if (asideBuffer.remaining() > 0) {
        final byte[] output = asideBuffer.getByteBuffer().array();
        final int write = Math.min(asideBuffer.remaining(), input.remaining());
        input.get(output, asideBuffer.position(), write);
        asideBuffer.position(asideBuffer.position() + write);
      }

      if (asideBuffer.remaining() == 0) {
        asideBuffer.position(0);
      }
    }

    if (input.remaining() == 0) {
      return true;
    }

    if (input.remaining() < KV_HEADER_LENGTH) {
      throw new IOException("incomplete data, input length is: " + input.remaining());
    }
    final int position = input.position();
    final int keyLength = input.getInt();
    final int valueLength = input.getInt();
    input.position(position);
    final int kvLength = keyLength + valueLength + KV_HEADER_LENGTH;
    final int remaining = input.remaining();

    if (kvLength > remaining) {
      if (null == asideBuffer || asideBuffer.capacity() < kvLength) {
        asideBuffer = new InputBuffer(BufferType.HEAP_BUFFER, kvLength);
      }
      asideBuffer.rewind(0, kvLength);

      input.get(asideBuffer.array(), 0, remaining);
      asideBuffer.position(remaining);
    }
    return true;
  }

  @Override
  public Progress getProgress() {
    return null;
  }
  
  /**
   * Closes the iterator so that the underlying streams can be closed.
   * 
   * @throws IOException
   */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    if (null != nativeReader) {
      nativeReader.close();
    }
    closed = true;
  }
}
